package org.tron.core.store;

import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.tron.common.utils.ByteUtil;
import org.tron.core.capsule.ContractStateCapsule;
import org.tron.core.db.TronStoreWithRevoking;
import org.tron.core.db2.common.WrappedByteArray;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
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

  public Map<WrappedByteArray, ContractStateCapsule> getCycleData(long cycleNumber) {
    return this.prefixQuery((cycleNumber + "-").getBytes());
  }

  public ContractStateCapsule getDayState(long cycleNum, byte[] addr) {
    return getIntervalData(cycleNum, 4, addr);
  }

  public ContractStateCapsule getWeekState(long cycleNum, byte[] addr) {
    return getIntervalData(cycleNum, 4 * 7, addr);
  }

  public ContractStateCapsule getIntervalData(long startCycleNum, long cycleCount, byte[] addr) {
    return getIntervalData(startCycleNum, cycleCount, addr, true);
  }

  public ContractStateCapsule getIntervalData(long startCycleNum, long cycleCount, byte[] addr,
                                              boolean clearDelegatedAccounts) {
    ContractStateCapsule total = new ContractStateCapsule(0);

    for (int i = 0; i < cycleCount; i++) {
      ContractStateCapsule data = get(addPrefix(startCycleNum + i, addr));
      if (data != null && clearDelegatedAccounts) {
          data.clearDelegatedAccounts();
      }
      total.merge(data);
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

  public Map<ByteString, ContractStateCapsule> getMergedDataWithinCycles(long cycleNumber, long cycleCount, boolean isContract) {
    Map<ByteString, ContractStateCapsule> result = new HashMap<>();
    for (int i = 0; i < cycleCount; i++) {
      byte[] cycleBytes = ((cycleNumber + i) + "-").getBytes();
      byte[] key = new byte[cycleBytes.length + 1];
      System.arraycopy(cycleBytes, 0, key, 0, cycleBytes.length);
      key[key.length - 1] = (byte) (isContract ? 0x41 : 0x42);
      Map<WrappedByteArray, ContractStateCapsule> contracts = this.prefixQuery(key);

      contracts.forEach((k, v) -> {
        byte[] addrBytes = Arrays.copyOfRange(k.getBytes(), 5, 26);
        addrBytes[0] = (byte) 0x41;
        ByteString addr = ByteString.copyFrom(addrBytes);
        v.clearDelegatedAccounts();
        if (result.containsKey(addr)) {
          result.get(addr).merge(v);
        } else {
          result.put(addr, v);
        }
      });
    }
    return result;
  }

  public static void main(String[] args) {
    Map<ByteString, Integer> m = new HashMap<>();
    m.put(ByteString.copyFrom("a".getBytes()), 1);
    System.out.println(m.containsKey(ByteString.copyFrom("a".getBytes())));
  }
}
