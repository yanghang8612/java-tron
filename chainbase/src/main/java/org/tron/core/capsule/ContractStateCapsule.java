package org.tron.core.capsule;

import static org.tron.core.Constant.DYNAMIC_ENERGY_DECREASE_DIVISION;
import static org.tron.core.Constant.DYNAMIC_ENERGY_FACTOR_DECIMAL;

import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.tron.core.store.DynamicPropertiesStore;
import org.tron.protos.contract.SmartContractOuterClass;
import org.tron.protos.contract.SmartContractOuterClass.ContractState;

import java.util.Map;

@Slf4j(topic = "capsule")
public class ContractStateCapsule implements ProtoCapsule<ContractState> {

  private ContractState contractState;

  public ContractStateCapsule(ContractState contractState) {
    this.contractState = contractState;
  }

  public ContractStateCapsule(byte[] data) {
    try {
      this.contractState = SmartContractOuterClass.ContractState.parseFrom(data);
    } catch (InvalidProtocolBufferException e) {
      // logger.debug(e.getMessage());
    }
  }

  public ContractStateCapsule(long currentCycle) {
    reset(currentCycle);
  }

  @Override
  public byte[] getData() {
    return this.contractState.toByteArray();
  }

  @Override
  public ContractState getInstance() {
    return this.contractState;
  }

  public long getEnergyUsage() {
    return this.contractState.getEnergyUsage();
  }

  public void setEnergyUsage(long value) {
    this.contractState = this.contractState.toBuilder().setEnergyUsage(value).build();
  }

  public void addEnergyUsage(long toAdd) {
    setEnergyUsage(getEnergyUsage() + toAdd);
  }

  public long getEnergyFactor() {
    return this.contractState.getEnergyFactor();
  }

  public void setEnergyFactor(long value) {
    this.contractState = this.contractState.toBuilder().setEnergyFactor(value).build();
  }

  public long getUpdateCycle() {
    return this.contractState.getUpdateCycle();
  }

  public void setUpdateCycle(long value) {
    this.contractState = this.contractState.toBuilder().setUpdateCycle(value).build();
  }

  public void addUpdateCycle(long toAdd) {
    setUpdateCycle(getUpdateCycle() + toAdd);
  }

  public long getAddressDbSize() {
    return this.getInstance().getAddressDbSize();
  }

  public void setAddressDbSize(long value) {
    this.contractState = this.contractState.toBuilder().setAddressDbSize(value).build();
  }

  public Map<String, Long> getNewAddressCountMap() {
    return this.getInstance().getNewAddressCountMap();
  }

  public void setNewAddressCountMap(Map<String, Long> map) {
    this.contractState = this.contractState.toBuilder().putAllNewAddressCount(map).build();
  }

  public void addNewAddressCount(SmartContractOuterClass.NewAddressTypeCode type) {
    long count = getNewAddressCountMap().getOrDefault(type.name(), 0L) + 1;
    this.contractState =
        this.contractState.toBuilder().putNewAddressCount(type.name(), count).build();
  }

  public long getTransactionDbSize() {
    return this.getInstance().getTransactionDbSize();
  }

  public void setTransactionDbSize(long value) {
    this.contractState = this.contractState.toBuilder().setTransactionDbSize(value).build();
  }

  public long getNewTransactionCount() {
    return this.getInstance().getNewTransactionCount();
  }

  public void addNewTransactionCount(long value) {
    this.contractState =
        this.contractState.toBuilder()
            .setNewTransactionCount(this.getNewTransactionCount() + value)
            .build();
  }

  public long getNewUsdtOwner() {
    return this.getInstance().getNewUsdtOwner();
  }

  public void addNewUsdtOwner() {
    this.contractState =
        this.contractState.toBuilder()
            .setNewUsdtOwner(this.getNewUsdtOwner() + 1)
            .build();
  }

  public void addNewUsdtOwner(long value) {
    this.contractState =
        this.contractState.toBuilder()
            .setNewUsdtOwner(this.getNewUsdtOwner() + value)
            .build();
  }

  public long getNewUsdtSender() {
    return this.getInstance().getNewUsdtSender();
  }

  public void addNewUsdtSender() {
    this.contractState =
        this.contractState.toBuilder()
            .setNewUsdtSender(this.getNewUsdtSender() + 1)
            .build();
  }

  public void addNewUsdtSender(long value) {
    this.contractState =
        this.contractState.toBuilder()
            .setNewUsdtSender(this.getNewUsdtSender() + value)
            .build();
  }

  public boolean ownedUsdt() {
    return this.contractState.getOwnedUsdt();
  }

  public void setOwnedUsdt(boolean owned) {
    this.contractState = this.contractState.toBuilder().setOwnedUsdt(owned).build();
  }

  public boolean sentUsdt() {
    return this.contractState.getSentUsdt();
  }

  public void setSentUsdt(boolean owned) {
    this.contractState = this.contractState.toBuilder().setSentUsdt(owned).build();
  }

  public boolean catchUpToCycle(DynamicPropertiesStore dps) {
    return catchUpToCycle(
        dps.getCurrentCycleNumber(),
        dps.getDynamicEnergyThreshold(),
        dps.getDynamicEnergyIncreaseFactor(),
        dps.getDynamicEnergyMaxFactor()
    );
  }

  public boolean catchUpToCycle(
      long newCycle, long threshold, long increaseFactor, long maxFactor
  ) {
    long lastCycle = getUpdateCycle();

    // Updated within this cycle
    if (lastCycle == newCycle) {
      return false;
    }

    // Guard judge and uninitialized state
    if (lastCycle > newCycle || lastCycle == 0L) {
      reset(newCycle);
      return true;
    }

    final long precisionFactor = DYNAMIC_ENERGY_FACTOR_DECIMAL;

    // Increase the last cycle
    // fix the threshold = 0 caused incompatible
    if (getEnergyUsage() > threshold) {
      lastCycle += 1;
      double increasePercent = 1 + (double) increaseFactor / precisionFactor;
      this.contractState = ContractState.newBuilder()
          .setUpdateCycle(lastCycle)
          .setEnergyFactor(Math.min(
              maxFactor,
              (long) ((getEnergyFactor() + precisionFactor) * increasePercent) - precisionFactor))
          .build();
    }

    // No need to decrease
    long cycleCount = newCycle - lastCycle;
    if (cycleCount <= 0) {
      return true;
    }

    // Calc the decrease percent (decrease factor [75% ~ 100%])
    double decreasePercent = Math.pow(
        1 - (double) increaseFactor / DYNAMIC_ENERGY_DECREASE_DIVISION / precisionFactor,
        cycleCount
    );

    // Decrease to this cycle
    // (If long time no tx and factor is 100%,
    //  we just calc it again and result factor is still 100%.
    //  That means we merge this special case to normal cases)
    this.contractState = ContractState.newBuilder()
        .setUpdateCycle(newCycle)
        .setEnergyFactor(Math.max(
            0,
            (long) ((getEnergyFactor() + precisionFactor) * decreasePercent) - precisionFactor))
        .build();

    return true;
  }

  public void reset(long latestCycle) {
    this.contractState = ContractState.newBuilder()
        .setUpdateCycle(latestCycle)
        .build();
  }

  @Override
  public String toString() {
    return "{\n" + contractState.toString() + '}';
  }
}
