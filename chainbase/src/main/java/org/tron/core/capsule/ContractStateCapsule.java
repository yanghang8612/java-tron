package org.tron.core.capsule;

import static org.tron.core.Constant.DYNAMIC_ENERGY_DECREASE_DIVISION;
import static org.tron.core.Constant.DYNAMIC_ENERGY_FACTOR_DECIMAL;

import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.tron.core.store.DynamicPropertiesStore;
import org.tron.protos.contract.SmartContractOuterClass;
import org.tron.protos.contract.SmartContractOuterClass.ContractState;

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

  public long getEnergyUsageTotal() {
    return this.getInstance().getEnergyUsageTotal();
  }

  public void addEnergyUsageTotal(long toAdd) {
    this.contractState = this.contractState.toBuilder()
        .setEnergyUsageTotal(this.contractState.getEnergyUsageTotal() + toAdd)
        .build();
  }

  public long getEnergyPenaltyTotal() {
    return this.getInstance().getEnergyPenaltyTotal();
  }

  public void addEnergyPenaltyTotal(long toAdd) {
    this.contractState = this.contractState.toBuilder()
        .setEnergyPenaltyTotal(this.contractState.getEnergyPenaltyTotal() + toAdd)
        .build();
  }

  public long getEnergyPenaltyFailed() {
    return this.getInstance().getEnergyPenaltyFailed();
  }

  public void addEnergyPenaltyFailed(long toAdd) {
    this.contractState = this.contractState.toBuilder()
        .setEnergyPenaltyFailed(this.contractState.getEnergyPenaltyFailed() + toAdd)
        .build();
  }

  public long getTrxBurn() {
    return this.getInstance().getTrxBurn();
  }

  public void addTrxBurn(long toAdd) {
    this.contractState = this.contractState.toBuilder()
        .setTrxBurn(this.contractState.getTrxBurn() + toAdd)
        .build();
  }

  public long getTrxPenalty() {
    return this.getInstance().getTrxPenalty();
  }

  public void addTrxPenalty(long toAdd) {
    this.contractState = this.contractState.toBuilder()
        .setTrxPenalty(this.contractState.getTrxPenalty() + toAdd)
        .build();
  }

  public long getTxTotalCount() {
    return this.getInstance().getTxTotalCount();
  }

  public void addTxTotalCount() {
    this.contractState = this.contractState.toBuilder()
        .setTxTotalCount(this.contractState.getTxTotalCount() + 1)
        .build();
  }

  public long getTxOOECount() {
    return this.getInstance().getTxOoeCount();
  }

  public void addTxOOECount() {
    this.contractState = this.contractState.toBuilder()
        .setTxOoeCount(this.contractState.getTxOoeCount() + 1)
        .build();
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
