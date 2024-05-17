package org.tron.core.store;

import java.util.Objects;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.tron.common.utils.ByteUtil;
import org.tron.core.capsule.ContractStateCapsule;
import org.tron.core.db.TronStoreWithRevoking;

@Slf4j(topic = "DB")
@Component
public class ContractStateStore extends TronStoreWithRevoking<ContractStateCapsule> {

  private static final byte[] ADDR_AND_TX = "ADDR_AND_TX".getBytes();

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

  public ContractStateCapsule getAddrAndTxRecord(long cycleNumber) {
    return getUnchecked(addPrefix(cycleNumber, ADDR_AND_TX));
  }

  public ContractStateCapsule getAddrAndTxRecord() {
    return getUnchecked(getCurrentPrefixKey(ADDR_AND_TX));
  }

  public void setAddrAndTxRecord(ContractStateCapsule capsule) {
    revokingDB.put(getCurrentPrefixKey(ADDR_AND_TX), capsule.getData());
  }

  public ContractStateCapsule getAccountRecord(byte[] addr) {
    addr[0] = (byte) 0x42;
    return getUnchecked(addr);
  }

  public void setAccountRecord(byte[] addr, ContractStateCapsule capsule) {
    addr[0] = (byte) 0x42;
    revokingDB.put(addr, capsule.getData());
  }

  private byte[] addPrefix(long cycleNumber, byte[] key) {
    return ByteUtil.merge((cycleNumber + "-").getBytes(), key);
  }

  private byte[] getCurrentPrefixKey(byte[] key) {
    return addPrefix(dps.getCurrentCycleNumber(), key);
  }
}
