package org.tron.core.service;

import static org.tron.core.config.Parameter.ChainConstant.TRX_PRECISION;

import com.google.protobuf.ByteString;
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
import org.tron.core.store.StakerIndexStore;
import org.tron.core.store.StakerStatStore;
import org.tron.core.store.TrackerStore;
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

  private final StakerIndexStore stakerIndexStore;

  private final DelegatedResourceStore delegatedResourceStore;

  private final DelegatedResourceAccountIndexStore delegatedResourceAccountIndexStore;

  private final DynamicPropertiesStore dynamicPropertiesStore;

  private final TrackerStore trackerStore;

  // 主线程单线程写(init 一次性扫 + 稳态期 AccountStore.put hook),ConcurrentHashMap 为防御性选择
  private final Set<ByteString> stakers = ConcurrentHashMap.newKeySet();

  // 只存 stakedForEnergy(Long),不再存完整 AccountCapsule
  private final Map<ByteString, Long> stakerStakedForEnergy = new ConcurrentHashMap<>();

  // 仅主线程写(updateMEU 由 AccountStore.put hook 触发),每个 cycle 结束 clear
  private final Map<ByteString, Long> accountMEUs = new HashMap<>();

  private volatile boolean initialized;

  @Autowired
  public TopDelegatorService(StakerStatStore stakerStatStore,
                             StakerIndexStore stakerIndexStore,
                             DelegatedResourceStore delegatedResourceStore,
                             DelegatedResourceAccountIndexStore delegatedResourceAccountIndexStore,
                             DynamicPropertiesStore dynamicPropertiesStore,
                             TrackerStore trackerStore) {
    this.stakerStatStore = stakerStatStore;
    this.stakerIndexStore = stakerIndexStore;
    this.delegatedResourceStore = delegatedResourceStore;
    this.delegatedResourceAccountIndexStore = delegatedResourceAccountIndexStore;
    this.dynamicPropertiesStore = dynamicPropertiesStore;
    this.trackerStore = trackerStore;
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
    this.stakers.clear();
    this.stakerStakedForEnergy.clear();
    this.accountMEUs.clear();
    this.dynamicPropertiesStore.removeMEUs();

    if (stakerIndexStore.isReady()) {
      List<Map.Entry<ByteString, Long>> indexedStakers = stakerIndexStore.loadStakers();
      for (Map.Entry<ByteString, Long> entry : indexedStakers) {
        stakers.add(entry.getKey());
        stakerStakedForEnergy.put(entry.getKey(), entry.getValue());
      }
      initialized = true;
      long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
      logger.info("TopDelegatorService init loaded staker index in {}ms, stakers={}",
          elapsedMs, stakers.size());
      if (!trackerStore.hasTotalNetWeight2()) {
        initTotalWeight2(accountStore);
      }
      return;
    }

    logger.info("TopDelegatorService init: staker index missing, streaming account scan starting");
    stakerIndexStore.clearIndex();

    long total = 0;
    boolean initTotal2 = !trackerStore.hasTotalNetWeight2();
    long netWeight2 = 0;
    long energyWeight2 = 0;
    Iterator<Map.Entry<byte[], AccountCapsule>> it = accountStore.iterator();
    try {
      while (it.hasNext()) {
        Map.Entry<byte[], AccountCapsule> e = it.next();
        total++;
        if (initTotal2) {
          long bandwidth = e.getValue().getFrozenV2BalanceForBandwidth()
              + e.getValue().getDelegatedFrozenV2BalanceForBandwidth();
          long energy = e.getValue().getFrozenV2BalanceForEnergy()
              + e.getValue().getDelegatedFrozenV2BalanceForEnergy();
          if (bandwidth > 0) {
            netWeight2 += bandwidth / TRX_PRECISION;
          }
          if (energy > 0) {
            energyWeight2 += energy / TRX_PRECISION;
          }
        }
        long staked = e.getValue().getAllStakedTRXForEnergy();
        if (staked > 0) {
          ByteString addr = ByteString.copyFrom(e.getKey());
          stakers.add(addr);
          stakerStakedForEnergy.put(addr, staked);
          stakerIndexStore.updateStaker(addr, 0, staked);
        }
        if (total % 1_000_000 == 0) {
          logger.info("TopDelegatorService init progress: {}M accounts processed, stakers so far: {}",
              total / 1_000_000, stakers.size());
        }
      }
    } finally {
      if (it instanceof java.io.Closeable) {
        try {
          ((java.io.Closeable) it).close();
        } catch (Exception e) {
          logger.error("Close account iterator.", e);
        }
      }
    }
    stakerIndexStore.markReady();
    if (initTotal2) {
      trackerStore.saveTotalEnergyWeight2(energyWeight2);
      trackerStore.saveTotalNetWeight2(netWeight2);
      logger.info("TopDelegatorService init built stake2.0 weight, net={}, energy={}",
          netWeight2, energyWeight2);
    }
    initialized = true;

    long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
    logger.info("TopDelegatorService init built staker index in {}ms, processed={}, stakers={}",
        elapsedMs, total, stakers.size());
  }

  private void initTotalWeight2(AccountStore accountStore) {
    logger.info("TopDelegatorService init: tracker total2 missing, streaming account scan starting");
    long total = 0;
    long netWeight2 = 0;
    long energyWeight2 = 0;
    Iterator<Map.Entry<byte[], AccountCapsule>> it = accountStore.iterator();
    try {
      while (it.hasNext()) {
        AccountCapsule account = it.next().getValue();
        total++;
        long bandwidth = account.getFrozenV2BalanceForBandwidth()
            + account.getDelegatedFrozenV2BalanceForBandwidth();
        long energy = account.getFrozenV2BalanceForEnergy()
            + account.getDelegatedFrozenV2BalanceForEnergy();
        if (bandwidth > 0) {
          netWeight2 += bandwidth / TRX_PRECISION;
        }
        if (energy > 0) {
          energyWeight2 += energy / TRX_PRECISION;
        }
        if (total % 1_000_000 == 0) {
          logger.info("TopDelegatorService total2 init progress: {}M accounts processed",
              total / 1_000_000);
        }
      }
    } finally {
      if (it instanceof java.io.Closeable) {
        try {
          ((java.io.Closeable) it).close();
        } catch (Exception e) {
          logger.error("Close account iterator.", e);
        }
      }
    }
    trackerStore.saveTotalEnergyWeight2(energyWeight2);
    trackerStore.saveTotalNetWeight2(netWeight2);
    logger.info("TopDelegatorService total2 init done, processed={}, net={}, energy={}",
        total, netWeight2, energyWeight2);
  }

  public void addStaker(AccountCapsule accountCap) {
    updateStaker(accountCap);
  }

  public void removeStaker(AccountCapsule accountCap) {
    if (accountCap != null) {
      removeStaker(accountCap.getAddress());
    }
  }

  public void removeStaker(ByteString address) {
    if (!initialized) {
      return;
    }
    long oldStake = stakerIndexStore.getStake(address);
    if (oldStake > 0) {
      stakers.remove(address);
      stakerStakedForEnergy.remove(address);
      stakerIndexStore.removeStaker(address, oldStake);
    }
  }

  public void updateStaker(AccountCapsule accountCapsule) {
    if (!initialized || accountCapsule == null) {
      return;
    }
    ByteString address = accountCapsule.getAddress();
    long newStake = accountCapsule.getAllStakedTRXForEnergy();
    Long oldStakeValue = stakerStakedForEnergy.get(address);
    if (newStake == 0 && oldStakeValue == null) {
      return;
    }
    long oldStake = oldStakeValue == null ? 0L : oldStakeValue;
    if (oldStake == newStake) {
      return;
    }
    if (newStake == 0) {
      stakers.remove(address);
      stakerStakedForEnergy.remove(address);
    } else {
      stakers.add(address);
      stakerStakedForEnergy.put(address, newStake);
    }
    stakerIndexStore.updateStaker(address, oldStake, newStake);
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

    List<Map.Entry<ByteString, Long>> stakerList = stakerIndexStore.getTopStakers(1000);

    logger.info("TopDelegatorService loaded top stakers, total={}", stakerList.size());

    for (int i = 0; i < stakerList.size(); i++) {
      Map.Entry<ByteString, Long> entry = stakerList.get(i);
      ByteString stakerAddr = entry.getKey();
      long staked = entry.getValue();
      byte[] staker = stakerAddr.toByteArray();
      logger.debug("TopDelegatorService doStats, Staker: {}, Staked TRX for Energy: {}",
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
