package org.tron.common.logsfilter.capsule;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.tron.common.entity.AssetTransferInfo;
import org.tron.common.logsfilter.trigger.BalanceTrackerTrigger;
import org.tron.common.logsfilter.trigger.Trigger;

import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = false)
public class TransferTrackerTrigger extends BalanceTrackerTrigger {

  List<AssetTransferInfo> trxAssetTransferInfoList = new ArrayList<>();

  List<AssetTransferInfo> trc10AssetTransferInfoList = new ArrayList<>();

  List<AssetTransferInfo> trc20AssetTransferInfoList = new ArrayList<>();

  List<AssetTransferInfo> trc721AssetTransferInfoList = new ArrayList<>();

  TransferTrackerTrigger() {
    super();
    setTriggerName(Trigger.TRANSFER_TRIGGER_NAME);
  }
}
