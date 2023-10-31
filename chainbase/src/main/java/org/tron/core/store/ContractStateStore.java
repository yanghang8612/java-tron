package org.tron.core.store;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.tron.common.utils.ByteUtil;
import org.tron.core.capsule.ContractStateCapsule;
import org.tron.core.db.TronStoreWithRevoking;

import java.util.Objects;

@Slf4j(topic = "DB")
@Component
public class ContractStateStore extends TronStoreWithRevoking<ContractStateCapsule> {

  @Autowired
  private DynamicPropertiesStore dps;

  @Autowired
  private ContractStateStore(@Value("contract-state") String dbName) {
    super(dbName);
  }

  @Override
  public ContractStateCapsule get(byte[] key) {
    return getUnchecked(key);
  }

  @Override
  public void put(byte[] key, ContractStateCapsule item) {
    if (Objects.isNull(key) || Objects.isNull(item)) {
      return;
    }

    revokingDB.put(key, item.getData());
  }

  public ContractStateCapsule getByCycle(byte[] key, long cycleNumber) {
    return getUnchecked(addPrefix(cycleNumber, key));
  }

  public ContractStateCapsule getTotalRecord() {
    return getUnchecked(addPrefix(dps.getCurrentCycleNumber(), "total".getBytes()));
  }

  public void setTotalRecord(ContractStateCapsule item) {
    revokingDB.put(addPrefix(dps.getCurrentCycleNumber(), "total".getBytes()), item.getData());
  }

  public ContractStateCapsule getSmallUSDTRecord() {
    return getUnchecked(addPrefix(dps.getCurrentCycleNumber(), "small".getBytes()));
  }

  public void setSmallUSDTRecord(ContractStateCapsule item) {
    revokingDB.put(addPrefix(dps.getCurrentCycleNumber(), "small".getBytes()), item.getData());
  }

  public ContractStateCapsule getBigUSDTRecord() {
      return getUnchecked(addPrefix(dps.getCurrentCycleNumber(), "big".getBytes()));
  }

  public void setBigUSDTRecord(ContractStateCapsule item) {
      revokingDB.put(addPrefix(dps.getCurrentCycleNumber(), "big".getBytes()), item.getData());
  }

  public ContractStateCapsule getAccountRecord(byte[] addr) {
    addr[0] = (byte) 0x42;
    return getUnchecked(addPrefix(dps.getCurrentCycleNumber(), addr));
  }

  public void setAccountRecord(byte[] addr, ContractStateCapsule item) {
    addr[0] = (byte) 0x42;
    revokingDB.put(addPrefix(dps.getCurrentCycleNumber(), addr), item.getData());
  }

  public ContractStateCapsule getContractRecord(byte[] addr) {
    return getUnchecked(addPrefix(dps.getCurrentCycleNumber(), addr));
  }

  public void setContractRecord(byte[] addr, ContractStateCapsule item) {
    revokingDB.put(addPrefix(dps.getCurrentCycleNumber(), addr), item.getData());
  }

  public void recordEnergyAndGasPrice(long energyPrice, long gasPrice) {
    ContractStateCapsule total = getTotalRecord();
    if (total == null) {
      total = new ContractStateCapsule(0);
    }
    total.setEnergyPrice(energyPrice);
    total.setGasPrice(gasPrice);
    setTotalRecord(total);
  }

  private byte[] addPrefix(long cycleNumber, byte[] key) {
    return ByteUtil.merge((cycleNumber + "-").getBytes(), key);
  }

  public ContractStateCapsule getDayState(long cycleNum, byte[] addr) {
    return getIntervalData(cycleNum, 4, addr);
  }

  public ContractStateCapsule getWeekState(long cycleNum, byte[] addr) {
    return getIntervalData(cycleNum, 4 * 7, addr);
  }

  public ContractStateCapsule getIntervalData(long startCycleNum, long cycleCount, byte[] addr) {
    ContractStateCapsule total = get(addPrefix(startCycleNum, addr));
    if (total == null) {
      return new ContractStateCapsule(0);
    }

    for (int i = 1; i < cycleCount; i++) {
      ContractStateCapsule csc = get(addPrefix(startCycleNum - i, addr));
      if (csc == null) {
        break;
      }
      total.merge(csc);
    }
    return total;
  }

  public ContractStateCapsule getMonthAvgState(long cycleNum, byte[] addr) {
    double trxBurn = 0;
    int trxCnt = 0;
    double energy = 0;
    int energyCnt = 0;
    double penalty = 0;
    int penaltyCnt = 0;
    for (int i = 0; i < 30; i++) {
      ContractStateCapsule dayState = getDayState(cycleNum - i * 4, addr);
      if (dayState.getTrxBurn() > 0) {
        trxCnt += 1;
        trxBurn += dayState.getEnergyPenaltyTotal();
      }
      if (dayState.getEnergyUsageTotal() > 0) {
        energyCnt += 1;
        energy += dayState.getEnergyPenaltyTotal();
      }
      if (dayState.getEnergyPenaltyTotal() > 0) {
        penaltyCnt += 1;
        penalty += dayState.getEnergyPenaltyTotal();
      }
    }
    ContractStateCapsule avg = new ContractStateCapsule(0);
    avg.addEnergyUsageTotal((long) (energy / energyCnt));
    avg.addEnergyPenaltyTotal((long) (penalty / penaltyCnt));
    avg.addTrxBurn((long) (trxBurn / trxCnt));
    return avg;
  }
}
