package org.tron.core.capsule;

import com.alibaba.fastjson.JSONObject;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.tron.core.store.DynamicPropertiesStore;
import org.tron.protos.contract.SmartContractOuterClass;
import org.tron.protos.contract.SmartContractOuterClass.ContractState;

import java.text.DecimalFormat;
import java.util.HashSet;
import java.util.Set;

import static org.tron.core.Constant.DYNAMIC_ENERGY_DECREASE_DIVISION;
import static org.tron.core.Constant.DYNAMIC_ENERGY_FACTOR_DECIMAL;

@Slf4j(topic = "capsule")
public class ContractStateCapsule implements ProtoCapsule<ContractState> {

  private ContractState contractState;

  private Set<String> delegatedAccounts;

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

  public void addTxTotalCount(long toAdd) {
    this.contractState = this.contractState.toBuilder()
        .setTxTotalCount(this.contractState.getTxTotalCount() + toAdd)
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

  public void addTxFailedCount(long toAdd) {
    this.contractState = this.contractState.toBuilder()
        .setTxFailedCount(this.contractState.getTxFailedCount() + toAdd)
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

  public void addTxOOECount(long toAdd) {
    this.contractState = this.contractState.toBuilder()
        .setTxOoeCount(this.contractState.getTxOoeCount() + toAdd)
        .build();
  }

  public long getEnergyPrice() {
    return this.getInstance().getUsdPerEnergy();
  }

  public void setEnergyPrice(long value) {
    this.contractState = this.contractState.toBuilder().setUsdPerEnergy(value).build();
  }

  public long getGasPrice() {
    return this.getInstance().getUsdPerGas();
  }

  public void setGasPrice(long value) {
    this.contractState = this.contractState.toBuilder().setUsdPerGas(value).build();
  }

  public long getTxCount() {
    return this.getInstance().getTxCount();
  }

  public void addTxCount() {
    this.contractState = this.contractState.toBuilder()
            .setTxCount(this.contractState.getTxCount() + 1)
            .build();
  }

  public void addTxCount(long toAdd) {
    this.contractState = this.contractState.toBuilder()
            .setTxCount(this.contractState.getTxCount() + toAdd)
            .build();
  }

  public long getTxTrxCount() {
    return this.getInstance().getTxTrxCount();
  }

  public void addTxTrxCount() {
      this.contractState = this.contractState.toBuilder()
              .setTxTrxCount(this.contractState.getTxTrxCount() + 1)
              .build();
  }

  public void addTxTrxCount(long toAdd) {
      this.contractState = this.contractState.toBuilder()
              .setTxTrxCount(this.contractState.getTxTrxCount() + toAdd)
              .build();
  }

  public long getTxTrc10Count() {
      return this.getInstance().getTxTrc10Count();
  }

  public void addTxTrc10Count() {
      this.contractState = this.contractState.toBuilder()
              .setTxTrc10Count(this.contractState.getTxTrc10Count() + 1)
              .build();
  }

  public void addTxTrc10Count(long toAdd) {
      this.contractState = this.contractState.toBuilder()
              .setTxTrc10Count(this.contractState.getTxTrc10Count() + toAdd)
              .build();
  }

  public long getTxTrc20Count() {
      return this.getInstance().getTxTrc20Count();
  }

  public void addTxTrc20Count() {
      this.contractState = this.contractState.toBuilder()
              .setTxTrc20Count(this.contractState.getTxTrc20Count() + 1)
              .build();
  }

  public void addTxTrc20Count(long toAdd) {
      this.contractState = this.contractState.toBuilder()
              .setTxTrc20Count(this.contractState.getTxTrc20Count() + toAdd)
              .build();
  }

  public long getBandwidthStake() {
    return this.getInstance().getBandwidthStake();
  }

  public void setBandwidthStake(long value) {
    this.contractState = this.contractState.toBuilder().setBandwidthStake(value).build();
  }

  public long getEnergyStake() {
    return this.getInstance().getEnergyStake();
  }

  public void setEnergyStake(long value) {
      this.contractState = this.contractState.toBuilder().setEnergyStake(value).build();
  }

  public long getBandwidthStake2() {
    return this.getInstance().getBandwidthStake2();
  }

  public void setBandwidthStake2(long value) {
      this.contractState = this.contractState.toBuilder().setBandwidthStake2(value).build();
  }

  public long getEnergyStake2() {
      return this.getInstance().getEnergyStake2();
  }

  public void setEnergyStake2(long value) {
      this.contractState = this.contractState.toBuilder().setEnergyStake2(value).build();
  }

  public long getStake2() {
    return this.getInstance().getStake2();
  }

  public void addStake2(long toAdd) {
    this.contractState = this.contractState.toBuilder()
            .setStake2(this.contractState.getStake2() + toAdd)
            .build();
  }

  public long getCancel() {
    return this.getInstance().getCancel();
  }

  public void addCancel(long toAdd) {
    this.contractState = this.contractState.toBuilder()
            .setCancel(this.contractState.getCancel() + toAdd)
            .build();
  }

  public long getUnstake() {
    return this.getInstance().getUnstake();
  }

  public void addUnstake(long toAdd) {
    this.contractState = this.contractState.toBuilder()
            .setUnstake(this.contractState.getUnstake() + toAdd)
            .build();
  }

  public long getUnstake2() {
    return this.getInstance().getUnstake2();
  }

  public void addUnstake2(long toAdd) {
    this.contractState = this.contractState.toBuilder()
            .setUnstake2(this.contractState.getUnstake2() + toAdd)
            .build();
  }

  public long getDelegatedAmount() {
    return this.getInstance().getDelegatedAmount();
  }

  public void addDelegatedAmount(long toAdd) {
      this.contractState = this.contractState.toBuilder()
          .setDelegatedAmount(this.contractState.getDelegatedAmount() + toAdd)
          .build();
  }

  public Set<String> getDelegatedAccounts() {
    if (delegatedAccounts == null) {
      delegatedAccounts = new HashSet<>(this.getInstance().getDelegatedAccountsList());
    }
    return delegatedAccounts;
  }

  public void addDelegatedAccountToInstance(String addr) {
    if (this.contractState.getDelegatedAccountsList().contains(addr)) {
      return;
    }
    this.contractState = this.contractState.toBuilder()
        .addDelegatedAccounts(addr)
        .build();
  }

  public void addDelegatedAccount(String addr) {
    this.getDelegatedAccounts().add(addr);
  }

  public void clearDelegatedAccounts() {
    this.contractState = this.contractState.toBuilder()
        .clearDelegatedAccounts()
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

  public void merge(ContractStateCapsule other) {
    if (other == null) {
      return;
    }
    this.addEnergyUsage(other.getEnergyUsage());
    this.addEnergyUsageTotal(other.getEnergyUsageTotal());
    this.addEnergyUsageFailed(other.getEnergyUsageFailed());
    this.addEnergyPenaltyTotal(other.getEnergyPenaltyTotal());
    this.addEnergyPenaltyFailed(other.getEnergyPenaltyFailed());
    this.addTrxBurn(other.getTrxBurn());
    this.addTrxPenalty(other.getTrxPenalty());
    this.addTxTotalCount(other.getTxTotalCount());
    this.addTxFailedCount(other.getTxFailedCount());
    this.addTxOOECount(other.getTxOOECount());
    this.addTxCount(other.getTxCount());
    this.addTxTrxCount(other.getTxTrxCount());
    this.addTxTrc10Count(other.getTxTrc10Count());
    this.addTxTrc20Count(other.getTxTrc20Count());
    this.setBandwidthStake(other.getBandwidthStake());
    this.setEnergyStake(other.getEnergyStake());
    this.setBandwidthStake2(other.getBandwidthStake2());
    this.setEnergyStake2(other.getEnergyStake2());
    this.addStake2(other.getStake2());
    this.addUnstake(other.getUnstake());
    this.addUnstake2(other.getUnstake2());
    this.addDelegatedAmount(other.getDelegatedAmount());
    other.getInstance().getDelegatedAccountsList().forEach(this::addDelegatedAccount);
  }

  @Override
  public String toString() {
    return "{\n" + contractState.toString() + '}';
  }

  public JSONObject toJsonObject() {
    JSONObject jsonObject = new JSONObject();
    if (this.getEnergyUsage() > 0) {
      jsonObject.put("energy_usage", this.getEnergyUsage());
    }
    if (this.getEnergyUsageTotal() > 0) {
      jsonObject.put("energy_usage_total", this.getEnergyUsageTotal());
    }
    if (this.getEnergyUsageFailed() > 0) {
      jsonObject.put("energy_usage_failed", this.getEnergyUsageFailed());
    }
    if (this.getEnergyPenaltyTotal() > 0) {
      jsonObject.put("energy_penalty_total", this.getEnergyPenaltyTotal());
    }
    if (this.getEnergyPenaltyFailed() > 0) {
      jsonObject.put("energy_penalty_failed", this.getEnergyPenaltyFailed());
    }
    if (this.getTrxBurn() > 0) {
      jsonObject.put("trx_burn", this.getTrxBurn());
    }
    if (this.getTrxPenalty() > 0) {
      jsonObject.put("trx_penalty", this.getTrxPenalty());
    }
    if (this.getTxTotalCount() > 0) {
      jsonObject.put("tx_total_count", this.getTxTotalCount());
    }
    if (this.getTxFailedCount() > 0) {
      jsonObject.put("tx_failed_count", this.getTxFailedCount());
    }
    if (this.getTxOOECount() > 0) {
      jsonObject.put("tx_ooe_count", this.getTxOOECount());
    }
    if (this.getTxCount() > 0) {
      jsonObject.put("tx_count", this.getTxCount());
    }
    if (this.getTxTrxCount() > 0) {
      jsonObject.put("tx_trx_count", this.getTxTrxCount());
    }
    if (this.getTxTrc10Count() > 0) {
      jsonObject.put("tx_trc10_count", this.getTxTrc10Count());
    }
    if (this.getTxTrc20Count() > 0) {
      jsonObject.put("tx_trc20_count", this.getTxTrc20Count());
    }
    if (this.getBandwidthStake() > 0) {
      jsonObject.put("bandwidth_stake", this.getBandwidthStake());
    }
    if (this.getEnergyStake() > 0) {
      jsonObject.put("energy_stake", this.getEnergyStake());
    }
    if (this.getBandwidthStake2() > 0) {
      jsonObject.put("bandwidth_stake2", this.getBandwidthStake2());
    }
    if (this.getEnergyStake2() > 0) {
      jsonObject.put("energy_stake2", this.getEnergyStake2());
    }
    if (this.getStake2() > 0) {
        jsonObject.put("stake2", this.getStake2());
    }
    if (this.getCancel() > 0) {
        jsonObject.put("cancel", this.getCancel());
    }
    if (this.getUnstake() > 0) {
        jsonObject.put("unstake", this.getUnstake());
    }
    if (this.getUnstake2() > 0) {
        jsonObject.put("unstake2", this.getUnstake2());
    }
    if (this.getDelegatedAmount() > 0) {
        jsonObject.put("delegated_amount", this.getDelegatedAmount());
    }
    if (!this.getDelegatedAccounts().isEmpty()) {
        jsonObject.put("delegated_accounts", this.getDelegatedAccounts());
    }
    return jsonObject;
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
