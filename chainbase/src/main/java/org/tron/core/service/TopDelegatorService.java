package org.tron.core.service;

import static org.tron.core.config.Parameter.ChainConstant.TRX_PRECISION;

import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.utils.StringUtil;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.DelegatedResourceAccountIndexCapsule;
import org.tron.core.capsule.DelegatedResourceCapsule;
import org.tron.core.db2.common.WrappedByteArray;
import org.tron.core.store.AccountStore;
import org.tron.core.store.DelegatedResourceAccountIndexStore;
import org.tron.core.store.DelegatedResourceStore;
import org.tron.core.store.DynamicPropertiesStore;
import org.tron.core.store.StakerStatStore;
import org.tron.protos.Protocol;

/**
 * fast-sync-stats: 移植自 track_dynamic_energy 的 staker 统计实现。
 *
 * 性能优化(相对参考):
 *  1. stakerCaps 不再存完整 AccountCapsule,只存 stakedForEnergy(Long),内存约 15-20× 缩减。
 *  2. init 用 256-prefix 分区并行扫账户表(线程池 = CPU 核数),启动时间 ~N× 缩短。
 *
 * 其他与参考一致;前缀查询保留尾下划线(避免 SS_1 误匹配 SS_10/100)。
 *
 * 设计要点:
 *  - in-memory 维护 {stakers, stakerStakedForEnergy, accountMEUs},避免每周期全表扫账户。
 *  - 由 AccountStore.put hook 同步增量:stake-for-energy 变化→add/remove/updateStaker;
 *    能量动作→updateMEU。
 *  - doStats 同步在 Manager 维护点(applyBlock 前)调用,getCurrentCycleNumber 返回
 *    刚结束的周期 N,故 stats 落到 SS_N_*,与读侧默认 currentCycle - 1 配合正确。
 */
@Component
@Slf4j(topic = "TopDelegatorService")
public class TopDelegatorService {

  private AccountStore accountStore;

  private final StakerStatStore stakerStatStore;

  private final DelegatedResourceStore delegatedResourceStore;

  private final DelegatedResourceAccountIndexStore delegatedResourceAccountIndexStore;

  private final DynamicPropertiesStore dynamicPropertiesStore;

  // 并发安全:init 期间多线程并行写,稳态期主线程从 AccountStore.put hook 单线程写
  private final Set<ByteString> stakers = ConcurrentHashMap.newKeySet();

  // 只存 stakedForEnergy(Long),不再存完整 AccountCapsule
  private final Map<ByteString, Long> stakerStakedForEnergy = new ConcurrentHashMap<>();

  // 仅主线程写(updateMEU 由 AccountStore.put hook 触发),每个 cycle 结束 clear
  private final Map<ByteString, Long> accountMEUs = new HashMap<>();

  @Autowired
  public TopDelegatorService(StakerStatStore stakerStatStore,
                             DelegatedResourceStore delegatedResourceStore,
                             DelegatedResourceAccountIndexStore delegatedResourceAccountIndexStore,
                             DynamicPropertiesStore dynamicPropertiesStore) {
    this.stakerStatStore = stakerStatStore;
    this.delegatedResourceStore = delegatedResourceStore;
    this.delegatedResourceAccountIndexStore = delegatedResourceAccountIndexStore;
    this.dynamicPropertiesStore = dynamicPropertiesStore;
  }

  /**
   * 启动时一次性扫账户表建 staker 索引。用 256 个 2-byte 前缀(0x41 0xXX)分区并行扫,
   * 线程池大小 = CPU 核数。每个 task 用 accountStore.prefixQuery 加载自己那一份(~1/256),
   * filter & 写入并发安全的 stakers / stakerStakedForEnergy。
   */
  public void init(AccountStore accountStore) {
    long startNanos = System.nanoTime();
    this.accountStore = accountStore;
    this.dynamicPropertiesStore.removeMEUs();

    int parallelism = Math.max(2, Runtime.getRuntime().availableProcessors());
    logger.info("TopDelegatorService init: parallel scan starting, parallelism={}", parallelism);

    ExecutorService pool = Executors.newFixedThreadPool(parallelism);
    AtomicLong totalProcessed = new AtomicLong(0);

    try {
      List<CompletableFuture<?>> futures = new ArrayList<>(256);
      for (int b = 0; b < 256; b++) {
        final byte[] prefix = new byte[]{0x41, (byte) b};
        futures.add(CompletableFuture.runAsync(() -> {
          Map<WrappedByteArray, AccountCapsule> partition = accountStore.prefixQuery(prefix);
          long localCount = 0;
          for (Map.Entry<WrappedByteArray, AccountCapsule> e : partition.entrySet()) {
            localCount++;
            AccountCapsule cap = e.getValue();
            long staked = cap.getAllStakedTRXForEnergy();
            if (staked > 0) {
              ByteString addr = ByteString.copyFrom(e.getKey().getBytes());
              stakers.add(addr);
              stakerStakedForEnergy.put(addr, staked);
            }
          }
          totalProcessed.addAndGet(localCount);
        }, pool));
      }
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    } finally {
      pool.shutdown();
    }

    long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
    logger.info("TopDelegatorService init done in {}ms, processed={}, stakers={}",
        elapsedMs, totalProcessed.get(), stakers.size());
  }

  public void addStaker(AccountCapsule accountCap) {
    stakers.add(accountCap.getAddress());
    stakerStakedForEnergy.put(accountCap.getAddress(), accountCap.getAllStakedTRXForEnergy());
  }

  public void removeStaker(AccountCapsule accountCap) {
    stakers.remove(accountCap.getAddress());
    stakerStakedForEnergy.remove(accountCap.getAddress());
  }

  public void updateStaker(AccountCapsule accountCapsule) {
    stakerStakedForEnergy.put(
        accountCapsule.getAddress(), accountCapsule.getAllStakedTRXForEnergy());
  }

  public long getMEU(ByteString address) {
    if (!accountMEUs.containsKey(address)) {
      AccountCapsule accountCap = accountStore.get(address.toByteArray());
      return calcMaxEnergyUtilization(accountCap);
    }
    return accountMEUs.get(address);
  }

  public void updateMEU(AccountCapsule accountCap) {
    long meu = calcMaxEnergyUtilization(accountCap);
    if (meu > accountMEUs.getOrDefault(accountCap.getAddress(), 0L)) {
      accountMEUs.put(accountCap.getAddress(), meu);
    }
  }

  private long calcMaxEnergyUtilization(AccountCapsule accountCap) {
    long currentUsage = accountCap.getRealEnergyUsage();
    long availableEnergy = (long) ((double) accountCap.getAllFrozenBalanceForEnergy() / TRX_PRECISION
        * dynamicPropertiesStore.getTotalEnergyCurrentLimit() / dynamicPropertiesStore.getTotalEnergyWeight());
    if (availableEnergy == 0) {
      return -1;
    }
    return currentUsage * 10_000 / availableEnergy;
  }

  public void doStats() {
    logger.info("TopDelegatorService doStats, Staker size: {}", stakers.size());

    // 直接从 stakedForEnergy 映射构建 (address, staked) 列表,O(|stakers|),无需读 capsule
    List<Map.Entry<ByteString, Long>> stakerList =
        new ArrayList<>(stakerStakedForEnergy.entrySet());
    stakerList.sort(Map.Entry.<ByteString, Long>comparingByValue().reversed());

    logger.info("TopDelegatorService finish sort, total={}", stakerList.size());

    for (int i = 0; i < 1000 && i < stakerList.size(); i++) {
      Map.Entry<ByteString, Long> entry = stakerList.get(i);
      ByteString stakerAddr = entry.getKey();
      long staked = entry.getValue();
      byte[] staker = stakerAddr.toByteArray();
      logger.info("TopDelegatorService doStats, Staker: {}, Staked TRX for Energy: {}",
          StringUtil.encode58Check(staker), staked);
      Map<ByteString, Long> delegateAmountMap = new HashMap<>();

      DelegatedResourceAccountIndexCapsule v1IndexCap =
          delegatedResourceAccountIndexStore.getIndex(staker);
      if (v1IndexCap != null) {
        for (ByteString to : v1IndexCap.getToAccountsList()) {
          byte[] dbKey = DelegatedResourceCapsule.createDbKey(staker, to.toByteArray());
          DelegatedResourceCapsule v1DelegateCap = delegatedResourceStore.get(dbKey);
          long delegateAmount =
              v1DelegateCap != null ? v1DelegateCap.getFrozenBalanceForEnergy() : 0;
          delegateAmountMap.put(to, delegateAmountMap.getOrDefault(to, 0L) + delegateAmount);
        }
      }

      DelegatedResourceAccountIndexCapsule v2IndexCap =
          delegatedResourceAccountIndexStore.getV2Index(staker);
      if (v2IndexCap != null) {
        for (ByteString to : v2IndexCap.getToAccountsList()) {
          byte[] dbKey = DelegatedResourceCapsule.createDbKeyV2(staker, to.toByteArray(), false);
          DelegatedResourceCapsule v2UnlockDelegateCap = delegatedResourceStore.get(dbKey);
          long v2UnlockDelegateAmount = v2UnlockDelegateCap != null
              ? v2UnlockDelegateCap.getFrozenBalanceForEnergy() : 0;
          delegateAmountMap.put(to,
              delegateAmountMap.getOrDefault(to, 0L) + v2UnlockDelegateAmount);

          dbKey = DelegatedResourceCapsule.createDbKeyV2(staker, to.toByteArray(), true);
          DelegatedResourceCapsule v2LockDelegateCap = delegatedResourceStore.get(dbKey);
          long v2LockDelegateAmount = v2LockDelegateCap != null
              ? v2LockDelegateCap.getFrozenBalanceForEnergy() : 0;
          delegateAmountMap.put(to,
              delegateAmountMap.getOrDefault(to, 0L) + v2LockDelegateAmount);
        }
      }

      Protocol.StakerStat.Builder stakerStatBuilder = Protocol.StakerStat.newBuilder();
      stakerStatBuilder.setAddress(stakerAddr);
      stakerStatBuilder.setStakedTrxForEnergy(staked);
      stakerStatBuilder.setMeu(getMEU(stakerAddr));
      for (Map.Entry<ByteString, Long> d : delegateAmountMap.entrySet()) {
        stakerStatBuilder.addDelegateStats(
            Protocol.StakerStat.DelegateStat.newBuilder()
                .setTo(d.getKey())
                .setAmount(d.getValue())
                .setMeu(getMEU(d.getKey()))
                .build());
      }
      stakerStatStore.recordStakerStat(staker, stakerStatBuilder.build().toByteArray());
    }

    logger.info("TopDelegatorService doStats finish");
    accountMEUs.clear();
  }
}
