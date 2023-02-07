package org.tron.core.capsule;

import static org.tron.core.Constant.DYNAMIC_ENERGY_DECREASE_DIVISION;
import static org.tron.core.Constant.DYNAMIC_ENERGY_FACTOR_DECIMAL;

import com.google.protobuf.InvalidProtocolBufferException;
import java.text.DecimalFormat;
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

  public long getEnergyUsageFailed() {
    return this.getInstance().getEnergyUsageFailed();
  }

  public void addEnergyUsageFailed(long toAdd) {
    this.contractState = this.contractState.toBuilder()
        .setEnergyUsageFailed(this.contractState.getEnergyUsageFailed() + toAdd)
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

  public long getTxFailedCount() {
    return this.getInstance().getTxFailedCount();
  }

  public void addTxFailedCount() {
    this.contractState = this.contractState.toBuilder()
        .setTxFailedCount(this.contractState.getTxFailedCount() + 1)
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

  public String toSlackMsg() {
    StringBuilder sb = new StringBuilder();
    DecimalFormat df = new DecimalFormat("#,###");
    if (this.getEnergyUsage() > 0) {
      sb.append("> `EnergyUsage`: ")
          .append(df.format(this.getEnergyUsage())).append("\n");
    }
    if (this.getEnergyFactor() > 0) {
      sb.append("> `EnergyFactor`: ")
          .append(df.format(this.getEnergyFactor())).append("\n");
    }
    if (this.getEnergyUsageTotal() > 0) {
      sb.append("> `EnergyUsageTotal`: ")
          .append(df.format(this.getEnergyUsageTotal())).append("\n");
    }
    if (this.getEnergyUsageFailed() > 0) {
      sb.append("> `EnergyUsageFailed`: ")
          .append(df.format(this.getEnergyUsageFailed())).append("\n");
    }
    if (this.getEnergyPenaltyTotal() > 0) {
      sb.append("> `EnergyPenaltyTotal`: ")
          .append(df.format(this.getEnergyPenaltyTotal())).append("\n");
    }
    if (this.getEnergyPenaltyFailed() > 0) {
      sb.append("> `EnergyPenaltyFailed`: ")
          .append(df.format(this.getEnergyPenaltyFailed())).append("\n");
    }
    if (this.getTrxBurn() > 0) {
      sb.append("> `TrxBurn`: ")
          .append(df.format(this.getTrxBurn() / 1000000)).append("\n");
    }
    if (this.getTrxPenalty() > 0) {
      sb.append("> `TrxPenalty`: ")
          .append(df.format(this.getTrxPenalty() / 1000000)).append("\n");
    }
    if (this.getTxTotalCount() > 0) {
      sb.append("> `TxTotalCount`: ")
          .append(df.format(this.getTxTotalCount())).append("\n");
    }
    if (this.getTxFailedCount() > 0) {
      sb.append("> `TxFailedCount`: ")
          .append(df.format(this.getTxFailedCount())).append("\n");
    }
    if (this.getTxOOECount() > 0) {
      sb.append("> `TxOOECount`: ")
          .append(df.format(this.getTxOOECount())).append("\n");
    }
    return sb.toString();
  }
}
