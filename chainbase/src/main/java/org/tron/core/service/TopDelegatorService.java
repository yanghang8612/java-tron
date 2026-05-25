package org.tron.core.service;

import com.google.protobuf.ByteString;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.DelegatedResourceAccountIndexCapsule;
import org.tron.core.capsule.DelegatedResourceCapsule;
import org.tron.core.store.AccountStore;
import org.tron.core.store.DelegatedResourceAccountIndexStore;
import org.tron.core.store.DelegatedResourceStore;
import org.tron.core.store.StakerStatStore;
import org.tron.protos.Protocol;

@Component
@Slf4j(topic = "TopDelegatorService")
public class TopDelegatorService {

  private static final int TOP_K = 1000;

  @Autowired
  private AccountStore accountStore;
  @Autowired
  private StakerStatStore stakerStatStore;
  @Autowired
  private DelegatedResourceStore delegatedResourceStore;
  @Autowired
  private DelegatedResourceAccountIndexStore delegatedResourceAccountIndexStore;

  // 周期内能量利用率峰值(基点),EnergyProcessor.useEnergy 实时写入
  private volatile ConcurrentHashMap<ByteString, Long> peakMeu = new ConcurrentHashMap<>();

  // 重入防护:避免上一周期 doStats 未跑完时下一周期又启动,造成重复全量扫描与互相覆盖
  private final AtomicBoolean running = new AtomicBoolean(false);

  /** 能量消耗点调用:复用 useEnergy 已算好的 usage 与 limit,免去重复计算 */
  public void recordPeakMeu(byte[] address, long energyUsage, long energyLimit) {
    if (energyLimit <= 0) {
      return;
    }
    long meu = energyUsage * 10_000 / energyLimit;
    peakMeu.merge(ByteString.copyFrom(address), meu, Math::max);
  }

  /**
   * 由 Manager 在主线程调用:抢占成功才启动本轮统计。放在主线程(而非 doStats 内)是为了:
   * 抢占失败时不换出 peakMeu,本周期样本继续累积、不丢失。
   */
  public boolean tryStartStats() {
    return running.compareAndSet(false, true);
  }

  /**
   * 线程创建/启动失败(如 OOM 无法创建原生线程)时由 Manager 调用:释放 running,
   * 否则 CAS 永不复位,后续所有周期的统计都会被永久跳过。
   */
  public void abortStats() {
    running.set(false);
  }

  /**
   * 由 Manager 在主线程调用换出本周期峰值快照。必须在主线程、维护触发点(交易已执行完、
   * cycle 尚未被 applyBlock 推进)调用,确保快照恰好对应本周期。
   */
  public Map<ByteString, Long> swapPeakMeu() {
    Map<ByteString, Long> snapshot = peakMeu;
    peakMeu = new ConcurrentHashMap<>();
    return snapshot;
  }

  private long getMeu(ByteString address, Map<ByteString, Long> snapshot) {
    return snapshot.getOrDefault(address, 0L);
  }

  /**
   * 维护周期末由 Manager 用独立线程调用。cycleNumber 与 snapshot 必须由主线程在 tryStartStats
   * 之后、applyBlock 推进 cycle 之前捕获并传入——doStats 跑在新线程,届时 cycle 已是 N+1,
   * 不能在此读 dps,否则统计会落到下一周期的 key。
   *
   * 一致性说明:本方法在独立线程遍历 live 的 account/delegation store,与主线程区块处理并发,
   * 底层迭代器无全局快照语义。故落库的质押额/代理记录是"最终一致的近似值"——扫描期间(下一
   * 周期区块处理时)被改动的账户可能反映为稍晚状态,不保证严格对应 cycleNumber。这是性能取舍:
   * 同步全量拷贝账户会阻塞区块处理,违背本功能"不阻塞主流程"的目标;而 top-K 多为大额稳定账户,
   * 秒级漂移可忽略。MEU 峰值与 cycleNumber 由主线程捕获,严格对应本周期,不受此近似影响。
   */
  public void doStats(long cycleNumber, Map<ByteString, Long> snapshot) {
    try {
      logger.info("doStats start, cycle {}", cycleNumber);
      // 小顶堆,按 stakedForEnergy 升序,容量 TOP_K
      PriorityQueue<Map.Entry<ByteString, Long>> heap =
          new PriorityQueue<>(Comparator.comparingLong(Map.Entry::getValue));
      long scanned = 0;
      Iterator<Map.Entry<byte[], AccountCapsule>> it = accountStore.iterator();
      while (it.hasNext()) {
        Map.Entry<byte[], AccountCapsule> e = it.next();
        long staked = e.getValue().getAllStakedTRXForEnergy();
        if (staked <= 0) {
          continue;
        }
        heap.offer(new AbstractMap.SimpleEntry<>(ByteString.copyFrom(e.getKey()), staked));
        if (heap.size() > TOP_K) {
          heap.poll();
        }
        if (++scanned % 1_000_000 == 0) {
          logger.info("doStats scanned {}", scanned);
        }
      }

      List<Map.Entry<ByteString, Long>> topList = new ArrayList<>(heap);
      topList.sort(Comparator.comparingLong((Map.Entry<ByteString, Long> x) -> x.getValue()).reversed());

      for (Map.Entry<ByteString, Long> entry : topList) {
        byte[] staker = entry.getKey().toByteArray();
        Map<ByteString, Long> delegateAmountMap = new HashMap<>();

        DelegatedResourceAccountIndexCapsule v1Index =
            delegatedResourceAccountIndexStore.getIndex(staker);
        if (v1Index != null) {
          for (ByteString to : v1Index.getToAccountsList()) {
            DelegatedResourceCapsule cap = delegatedResourceStore.get(
                DelegatedResourceCapsule.createDbKey(staker, to.toByteArray()));
            long amount = cap != null ? cap.getFrozenBalanceForEnergy() : 0;
            delegateAmountMap.merge(to, amount, Long::sum);
          }
        }
        DelegatedResourceAccountIndexCapsule v2Index =
            delegatedResourceAccountIndexStore.getV2Index(staker);
        if (v2Index != null) {
          for (ByteString to : v2Index.getToAccountsList()) {
            DelegatedResourceCapsule unlockCap = delegatedResourceStore.get(
                DelegatedResourceCapsule.createDbKeyV2(staker, to.toByteArray(), false));
            DelegatedResourceCapsule lockCap = delegatedResourceStore.get(
                DelegatedResourceCapsule.createDbKeyV2(staker, to.toByteArray(), true));
            long amount = (unlockCap != null ? unlockCap.getFrozenBalanceForEnergy() : 0)
                + (lockCap != null ? lockCap.getFrozenBalanceForEnergy() : 0);
            delegateAmountMap.merge(to, amount, Long::sum);
          }
        }

        Protocol.StakerStat.Builder b = Protocol.StakerStat.newBuilder()
            .setAddress(entry.getKey())
            .setStakedTrxForEnergy(entry.getValue())
            .setMeu(getMeu(entry.getKey(), snapshot));
        for (Map.Entry<ByteString, Long> d : delegateAmountMap.entrySet()) {
          b.addDelegateStats(Protocol.StakerStat.DelegateStat.newBuilder()
              .setTo(d.getKey())
              .setAmount(d.getValue())
              .setMeu(getMeu(d.getKey(), snapshot)));
        }
        stakerStatStore.recordStakerStat(staker, b.build().toByteArray(), cycleNumber);
      }
      logger.info("doStats finish, cycle {}, scanned {}, top {}", cycleNumber, scanned, topList.size());
    } finally {
      running.set(false);
    }
  }
}
