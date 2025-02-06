package org.tron.core.store;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.tron.common.utils.ByteArray;
import org.tron.core.capsule.BytesCapsule;
import org.tron.core.db.TronStoreWithRevoking;
import org.tron.protos.Protocol;

import java.util.ArrayList;
import java.util.List;

@Slf4j(topic = "DB")
@Component
public class StakerStatStore extends TronStoreWithRevoking<BytesCapsule> {

  @Autowired
  DynamicPropertiesStore dps;

  @Autowired
  private StakerStatStore(@Value("staker") String dbName) {
    super(dbName);
  }

  @Override
  public BytesCapsule get(byte[] key) {
    return getUnchecked(key);
  }

//  public void recordMaxEnergyUtilization(AccountCapsule accountCap) {
//    long cycleNumber = dps.getCurrentCycleNumber();
//    long currentEnergyUtilization = calcMaxEnergyUtilization(accountCap);
//    this.put(generateMaxEnergyUtilizationKey(accountCap.getAddress().toByteArray(), cycleNumber),
//        new BytesCapsule(ByteArray.fromLong(currentEnergyUtilization)));
//  }
//
//  public long getMaxEnergyUtilization(AccountCapsule accountCapsule) {
//    return getMaxEnergyUtilization(accountCapsule, dps.getCurrentCycleNumber());
//  }
//
//  public long getMaxEnergyUtilization(AccountCapsule accountCapsule, long cycleNumber) {
//    return Optional.ofNullable(getUnchecked(generateMaxEnergyUtilizationKey(accountCapsule.createDbKey(), cycleNumber)))
//        .map(BytesCapsule::getData)
//        .map(ByteArray::toLong)
//        .orElse(calcMaxEnergyUtilization(accountCapsule));
//  }
//
//  private long calcMaxEnergyUtilization(AccountCapsule accountCap) {
//    long currentUsage = accountCap.getRealEnergyUsage();
//    long availableEnergy = (long) ((double) accountCap.getAllFrozenBalanceForEnergy() / TRX_PRECISION
//        * dps.getTotalEnergyCurrentLimit() / dps.getTotalEnergyWeight());
//    if (availableEnergy == 0) {
//      return -1;
//    }
//    return currentUsage * 10_000 / availableEnergy;
//  }
//
//  private byte[] generateMaxEnergyUtilizationKey(byte[] address, long cycleNumber) {
//    return String.format("MEU_%d_%s", cycleNumber, ByteArray.toHexString(address)).getBytes();
//  }

  public void recordStakerStat(byte[] staker, byte[] stats) {
    this.put(generateStakerStatKey(staker, dps.getCurrentCycleNumber()), new BytesCapsule(stats));
  }

  public List<Protocol.StakerStat> getStakerStat(long cycleNumber) {
    List<Protocol.StakerStat> stats = new ArrayList<>();
    prefixQuery(("SS_" + cycleNumber).getBytes()).forEach((k, v) -> {
      try {
        stats.add(Protocol.StakerStat.parseFrom(v.getData()));
      } catch (Exception e) {
        logger.error("Failed to parse StakerStat", e);
      }
    });
    return stats;
  }

  private byte[] generateStakerStatKey(byte[] address, long cycleNumber) {
    return String.format("SS_%d_%s", cycleNumber, ByteArray.toHexString(address)).getBytes();
  }
}
