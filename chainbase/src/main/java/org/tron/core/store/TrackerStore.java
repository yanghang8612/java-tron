package org.tron.core.store;

import java.util.Arrays;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.tron.common.utils.ByteArray;
import org.tron.core.capsule.BytesCapsule;
import org.tron.core.db.TronStoreWithRevoking;

@Slf4j(topic = "DB")
@Component
public class TrackerStore extends TronStoreWithRevoking<BytesCapsule> {

  private static final byte[] TOTAL_NET_WEIGHT2 = "TOTAL_NET_WEIGHT2".getBytes();
  private static final byte[] TOTAL_ENERGY_WEIGHT2 = "TOTAL_ENERGY_WEIGHT2".getBytes();
  private static final byte[] LAST_PRUNED_BLOCK_NUM = "LAST_PRUNED_BLOCK_NUM".getBytes();

  @Autowired
  private TrackerStore(@Value("tracker") String dbName) {
    super(dbName);
  }

  @Override
  public BytesCapsule get(byte[] key) {
    return getUnchecked(key);
  }

  public boolean hasTotalNetWeight2() {
    return getUnchecked(TOTAL_NET_WEIGHT2) != null;
  }

  public void saveTotalNetWeight2(long totalNetWeight) {
    put(TOTAL_NET_WEIGHT2, new BytesCapsule(ByteArray.fromLong(totalNetWeight)));
  }

  public long getTotalNetWeight2() {
    return getLong(TOTAL_NET_WEIGHT2, 0L);
  }

  public void saveTotalEnergyWeight2(long totalEnergyWeight) {
    put(TOTAL_ENERGY_WEIGHT2, new BytesCapsule(ByteArray.fromLong(totalEnergyWeight)));
  }

  public long getTotalEnergyWeight2() {
    return getLong(TOTAL_ENERGY_WEIGHT2, 0L);
  }

  public void addTotalNetWeight2(long amount) {
    if (amount == 0) {
      return;
    }
    saveTotalNetWeight2(Math.max(0, getTotalNetWeight2() + amount));
  }

  public void addTotalEnergyWeight2(long amount) {
    if (amount == 0) {
      return;
    }
    saveTotalEnergyWeight2(Math.max(0, getTotalEnergyWeight2() + amount));
  }

  public long getCycleEndBlockNumber(long cycle) {
    return getLong(cycleEndKey(cycle), 0L);
  }

  public void saveCycleEndBlockNumber(long cycle, long number) {
    put(cycleEndKey(cycle), new BytesCapsule(ByteArray.fromLong(number)));
  }

  public void saveCycleStakeWeights(long cycle, long net2, long energy2,
      long totalNet, long totalEnergy) {
    byte[] data = new byte[32];
    System.arraycopy(ByteArray.fromLong(net2), 0, data, 0, 8);
    System.arraycopy(ByteArray.fromLong(energy2), 0, data, 8, 8);
    System.arraycopy(ByteArray.fromLong(totalNet), 0, data, 16, 8);
    System.arraycopy(ByteArray.fromLong(totalEnergy), 0, data, 24, 8);
    put(stakeWeightKey(cycle), new BytesCapsule(data));
  }

  public long[] getCycleStakeWeights(long cycle) {
    byte[] data = Optional.ofNullable(getUnchecked(stakeWeightKey(cycle)))
        .map(BytesCapsule::getData)
        .filter(d -> d.length == 32)
        .orElse(null);
    if (data == null) {
      return null;
    }
    return new long[] {
        ByteArray.toLong(Arrays.copyOfRange(data, 0, 8)),
        ByteArray.toLong(Arrays.copyOfRange(data, 8, 16)),
        ByteArray.toLong(Arrays.copyOfRange(data, 16, 24)),
        ByteArray.toLong(Arrays.copyOfRange(data, 24, 32))
    };
  }

  public long getLastPrunedBlockNum() {
    return getLong(LAST_PRUNED_BLOCK_NUM, -1L);
  }

  public void saveLastPrunedBlockNum(long n) {
    put(LAST_PRUNED_BLOCK_NUM, new BytesCapsule(ByteArray.fromLong(n)));
  }

  public void pruneCycle(long cycle) {
    delete(cycleEndKey(cycle));
    delete(stakeWeightKey(cycle));
  }

  private long getLong(byte[] key, long defaultValue) {
    return Optional.ofNullable(getUnchecked(key))
        .map(BytesCapsule::getData)
        .map(ByteArray::toLong)
        .orElse(defaultValue);
  }

  private byte[] cycleEndKey(long cycle) {
    return ("CYCLE_END_" + cycle).getBytes();
  }

  private byte[] stakeWeightKey(long cycle) {
    return ("SW_" + cycle).getBytes();
  }
}
