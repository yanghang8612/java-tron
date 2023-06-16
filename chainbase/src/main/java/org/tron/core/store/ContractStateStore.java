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
    revokingDB.put(addPrefix(dps.getCurrentCycleNumber(), key), item.getData());
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

  public void recordEnergyAndGasPrice(long energyPrice, long gasPrice) {
    ContractStateCapsule total = getTotalRecord();
    total.setEnergyPrice(energyPrice);
    total.setGasPrice(gasPrice);
    setTotalRecord(total);
  }

  private byte[] addPrefix(long cycleNumber, byte[] key) {
    return ByteUtil.merge((cycleNumber + "-").getBytes(), key);
  }

  public ContractStateCapsule getDayState(long cycleNum, byte[] addr) {
    ContractStateCapsule total = new ContractStateCapsule(0);
    for (int i = 0; i < 4; i++) {
      ContractStateCapsule csc = get(addPrefix(cycleNum - i, addr));
      if (csc == null) {
        break;
      }
      total.addEnergyUsage(csc.getEnergyUsage());
      total.addEnergyUsageTotal(csc.getEnergyUsageTotal());
      total.addEnergyUsageFailed(csc.getEnergyUsageFailed());
      total.addEnergyPenaltyTotal(csc.getEnergyPenaltyTotal());
      total.addEnergyPenaltyFailed(csc.getEnergyPenaltyFailed());
      total.addTrxBurn(csc.getTrxBurn());
      total.addTrxPenalty(csc.getTrxPenalty());
      total.addTxTotalCount(csc.getTxTotalCount());
      total.addTxFailedCount(csc.getTxFailedCount());
      total.addTxOOECount(csc.getTxOOECount());
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
