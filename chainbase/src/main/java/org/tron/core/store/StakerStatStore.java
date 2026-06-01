package org.tron.core.store;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.tron.common.utils.ByteArray;
import org.tron.core.capsule.BytesCapsule;
import org.tron.core.db.TronStoreWithRevoking;
import org.tron.protos.Protocol;

@Slf4j(topic = "DB")
@Component
public class StakerStatStore extends TronStoreWithRevoking<BytesCapsule> {

  @Autowired
  private DynamicPropertiesStore dps;

  @Autowired
  private StakerStatStore(@Value("staker") String dbName) {
    super(dbName);
  }

  @Override
  public BytesCapsule get(byte[] key) {
    return getUnchecked(key);
  }

  /**
   * 写入当前周期(由 dps.getCurrentCycleNumber() 读)的某 staker 统计。
   * 调用方应在维护点 doStats 同步链路里调用——此时 currentCycle 仍是刚结束的 N,
   * 与读侧默认 currentCycle - 1 配合,可正确取回最新已统计周期。
   */
  public void recordStakerStat(byte[] staker, byte[] stats) {
    recordStakerStat(staker, stats, dps.getCurrentCycleNumber());
  }

  public void recordStakerStat(byte[] staker, byte[] stats, long cycleNumber) {
    this.put(generateKey(staker, cycleNumber), new BytesCapsule(stats));
  }

  public List<Protocol.StakerStat> getStakerStat(long cycleNumber) {
    List<Protocol.StakerStat> stats = new ArrayList<>();
    // 前缀带尾部下划线,避免 "SS_1_" 误匹配 "SS_10_"/"SS_100_"
    prefixQuery(("SS_" + cycleNumber + "_").getBytes()).forEach((k, v) -> {
      try {
        stats.add(Protocol.StakerStat.parseFrom(v.getData()));
      } catch (Exception e) {
        logger.error("Failed to parse StakerStat", e);
      }
    });
    return stats;
  }

  public void pruneCycle(long cycleNumber) {
    prefixQuery(("SS_" + cycleNumber + "_").getBytes())
        .forEach((k, v) -> delete(k.getBytes()));
  }

  private byte[] generateKey(byte[] address, long cycleNumber) {
    return ("SS_" + cycleNumber + "_" + ByteArray.toHexString(address)).getBytes();
  }
}
