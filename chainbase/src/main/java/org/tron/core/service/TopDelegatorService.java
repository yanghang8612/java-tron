package org.tron.core.service;

import static org.tron.core.config.Parameter.ChainConstant.TRX_PRECISION;

import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.utils.StringUtil;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.DelegatedResourceAccountIndexCapsule;
import org.tron.core.capsule.DelegatedResourceCapsule;
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
 *  2. init 单线程流式遍历,内存恒定(主网量级安全);后续如需提速可做底层 LevelDB
 *     DBIterator 多线程 seek 分段。
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

  // 主线程单线程写(init 一次性扫 + 稳态期 AccountStore.put hook),ConcurrentHashMap 为防御性选择
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
   * 启动时一次性扫账户表建 staker 索引。单线程流式遍历,内存恒定(只持有当前一个 capsule),
   * 在主网量级(2-3 亿账户)下安全。
   *
   * 历史:曾尝试 256-prefix 分区并行(每 partition prefixQuery 全量 materialize 进 Map),
   * 在主网量级下 16 个 partition 同时 in-flight 峰值 ~12-16GB,易触发 OOM,故回退此版。
   * 如需提速,正确做法是底层 LevelDB DBIterator 多线程 seek 分段、流式不 materialize——后续再做。
   */
  public void init(AccountStore accountStore) {
    long startNanos = System.nanoTime();
    this.accountStore = accountStore;
    this.dynamicPropertiesStore.removeMEUs();

    logger.info("TopDelegatorService init: streaming scan starting (single-threaded, memory-safe)");

    long total = 0;
    Iterator<Map.Entry<byte[], AccountCapsule>> it = accountStore.iterator();
    while (it.hasNext()) {
      Map.Entry<byte[], AccountCapsule> e = it.next();
      total++;
      long staked = e.getValue().getAllStakedTRXForEnergy();
      if (staked > 0) {
        ByteString addr = ByteString.copyFrom(e.getKey());
        stakers.add(addr);
        stakerStakedForEnergy.put(addr, staked);
      }
      if (total % 1_000_000 == 0) {
        logger.info("TopDelegatorService init progress: {}M accounts processed, stakers so far: {}",
            total / 1_000_000, stakers.size());
      }
    }

    long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
    logger.info("TopDelegatorService init done in {}ms, processed={}, stakers={}",
        elapsedMs, total, stakers.size());
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
