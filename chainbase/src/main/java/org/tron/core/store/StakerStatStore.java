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
  private StakerStatStore(@Value("staker") String dbName) {
    super(dbName);
  }

  @Override
  public BytesCapsule get(byte[] key) {
    return getUnchecked(key);
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

  private byte[] generateKey(byte[] address, long cycleNumber) {
    return String.format("SS_%d_%s", cycleNumber, ByteArray.toHexString(address)).getBytes();
  }
}
