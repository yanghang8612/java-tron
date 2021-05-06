package org.tron.common.logsfilter.capsule;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.tron.common.logsfilter.EventPluginLoader;
import org.tron.common.logsfilter.trigger.FreezeBalanceTrigger;
import org.tron.common.utils.StringUtil;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.db.accountchange.FreezeChangeRecord;
import org.tron.protos.contract.AssetIssueContractOuterClass;
import org.tron.protos.contract.BalanceContract;
import org.tron.protos.contract.Common;

import java.math.BigInteger;
import java.util.*;

@Slf4j
public class FreezeTrackerCapsule extends TriggerCapsule {

  @Getter
  @Setter
  private FreezeBalanceTrigger freezeBalanceTrigger;

  public FreezeTrackerCapsule(BlockCapsule block, Map<String, FreezeChangeRecord.FreezeInfo> freezeInfoMap) {
    freezeBalanceTrigger = new FreezeBalanceTrigger();
    freezeBalanceTrigger.setBlockHash(block.getBlockId().toString());
    freezeBalanceTrigger.setParentHash(block.getParentHash().toString());
    freezeBalanceTrigger.setBlockNumber(block.getNum());
    freezeBalanceTrigger.setTimeStamp(block.getTimeStamp());

    List<FreezeBalanceTrigger.FreezeBalance> freezeList = new LinkedList<>();

    freezeInfoMap.forEach((key, item) -> {
      if (item.getIncrementFreezeEnergyBalance() != 0) {
        FreezeBalanceTrigger.FreezeBalance freezeBalance = new FreezeBalanceTrigger.FreezeBalance();
        freezeBalance.setFromAddress(item.getFromAddress());
        freezeBalance.setToAddress(item.getToAddress());
        freezeBalance.setFreezeBalance(String.valueOf(item.getFreezeEnergyBalance()));
        freezeBalance.setExpireTime(String.valueOf(item.getExpireTimeForEnergy()));
        freezeBalance.setIncrementFreezeBalance(String.valueOf(item.getIncrementFreezeEnergyBalance()));
        freezeBalance.setIncrementExpireTime(String.valueOf(item.getIncrementExpireTimeForEnergy()));
        freezeBalance.setResource(1);
        freezeList.add(freezeBalance);
      }

      if (item.getIncrementFreezeBandwidthBalance() != 0) {
        FreezeBalanceTrigger.FreezeBalance freezeBalance = new FreezeBalanceTrigger.FreezeBalance();
        freezeBalance.setFromAddress(item.getFromAddress());
        freezeBalance.setToAddress(item.getToAddress());
        freezeBalance.setFreezeBalance(String.valueOf(item.getFreezeBandwidthBalance()));
        freezeBalance.setExpireTime(String.valueOf(item.getExpireTimeForBandwidth()));
        freezeBalance.setIncrementFreezeBalance(String.valueOf(item.getIncrementFreezeBandwidthBalance()));
        freezeBalance.setIncrementExpireTime(String.valueOf(item.getIncrementExpireTimeForBandwidth()));
        freezeBalance.setResource(2);
        freezeList.add(freezeBalance);
      }
    });

    freezeBalanceTrigger.setFreezeList(freezeList);
  }

  private void handlerTrc10Transfer(TransactionCapsule item, List<FreezeBalanceTrigger.AssetTransfer> transferList) {
    try {
      AssetIssueContractOuterClass.TransferAssetContract transferContract = item.getInstance().getRawData().getContract(0).getParameter().unpack(AssetIssueContractOuterClass.TransferAssetContract.class);


    } catch (Exception ex) {
      logger.error("", ex);
    }
  }

  private void handlerTrxTransfer(TransactionCapsule item, List<FreezeBalanceTrigger.AssetTransfer> transferList) {
    try {
      BalanceContract.TransferContract transferContract = item.getInstance().getRawData().getContract(0).getParameter().unpack(BalanceContract.TransferContract.class);
      FreezeBalanceTrigger.AssetTransfer transfer = new FreezeBalanceTrigger.AssetTransfer();
      transfer.setTokenAddress("");
      transfer.setFromAddress(StringUtil.encode58Check(transferContract.getOwnerAddress().toByteArray()));
      transfer.setToAddress(StringUtil.encode58Check(transferContract.getToAddress().toByteArray()));
      transfer.setAssetType(0);
      transfer.setAmount(BigInteger.valueOf(transferContract.getAmount()));
      transfer.setTrId(item.getTransactionId().toString());
      transferList.add(transfer);
    } catch (Exception ex) {
      logger.error("", ex);
    }
  }


  // 只是记录 给别人的冻结， 给自己的不记录
  private void handlerAddress(String fromAddress, String toAddress, Common.ResourceCode code,
                              Map<String, Map<String, Set<Common.ResourceCode>>> result) {
    Map<String, Set<Common.ResourceCode>> map = result.get(fromAddress);

    if (map == null) {
      map = new HashMap<String, Set<Common.ResourceCode>>();
      result.put(fromAddress, map);
    }

    Set<Common.ResourceCode> codeSet = map.get(toAddress);

    if (codeSet == null) {
      codeSet = new HashSet<>();
      map.put(toAddress, codeSet);
    }

    codeSet.add(code);
  }


  @Override
  public void processTrigger() {
    EventPluginLoader.getInstance().postFreezeBalanceTrigger(freezeBalanceTrigger);
  }


}
