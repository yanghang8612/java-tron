package org.tron.common.logsfilter.capsule;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.tron.common.entity.AssetTransfer;
import org.tron.common.logsfilter.trigger.BalanceTrackerTrigger;

import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = false)
public class TransferTrackerTrigger extends BalanceTrackerTrigger {

  List<AssetTransfer> trxAssetTransferList = new ArrayList<>();

  List<AssetTransfer> trc10AssetTransferList = new ArrayList<>();

  List<AssetTransfer> trc20AssetTransferList = new ArrayList<>();

  List<AssetTransfer> trc721AssetTransferList = new ArrayList<>();


}
