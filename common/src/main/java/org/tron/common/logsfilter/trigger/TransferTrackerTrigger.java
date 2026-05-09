package org.tron.common.logsfilter.trigger;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.tron.common.entity.AssetTransferInfo;

/**
 * === TronLink Feature ===
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class TransferTrackerTrigger extends BalanceTrackerTrigger {

  List<AssetTransferInfo> trxAssetTransferInfoList = new ArrayList<>();

  List<AssetTransferInfo> trc10AssetTransferInfoList = new ArrayList<>();

  List<AssetTransferInfo> trc20AssetTransferInfoList = new ArrayList<>();

  List<AssetTransferInfo> trc721AssetTransferInfoList = new ArrayList<>();

  public TransferTrackerTrigger() {
    super();
    setTriggerName(Trigger.TRANSFER_TRIGGER_NAME);
  }
}
