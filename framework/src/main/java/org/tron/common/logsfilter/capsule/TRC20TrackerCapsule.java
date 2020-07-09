package org.tron.common.logsfilter.capsule;

import com.alibaba.fastjson.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.tron.common.logsfilter.EventPluginLoader;
import org.tron.common.logsfilter.TRC20Utils;
import org.tron.common.logsfilter.trigger.TRC20TrackerTrigger;
import org.tron.common.logsfilter.trigger.TRC20TrackerTrigger.AssetStatusPojo;
import org.tron.common.runtime.vm.LogInfo;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.store.AccountStore;

@Slf4j
public class TRC20TrackerCapsule extends TriggerCapsule {


  @Getter
  @Setter
  TRC20TrackerTrigger trc20TrackerTrigger;

  public TRC20TrackerCapsule(BlockCapsule block, Map<byte[], AccountStore.AccountInfo> accountInfoMap) {
    trc20TrackerTrigger = new TRC20TrackerTrigger();
    trc20TrackerTrigger.setBlockHash(block.getBlockId().toString());
    trc20TrackerTrigger.setParentHash(block.getParentHash().toString());
    trc20TrackerTrigger.setBlockNumber(block.getNum());
    trc20TrackerTrigger.setTimeStamp(block.getTimeStamp());
    List<TransactionCapsule> transactionCapsules = block.getTransactions();
    List<LogInfo> logInfos = new ArrayList<>();
    for (TransactionCapsule transactionCapsule : transactionCapsules) {
      List<LogInfo> innerList = transactionCapsule.getTrxTrace().getTransactionContext()
          .getProgramResult().getLogInfoList();
      if (innerList != null && innerList.size() > 0) {
        logInfos.addAll(innerList);
      }
    }
    if (logInfos.size() > 0) {
      List<AssetStatusPojo> assetStatusPojos = TRC20Utils
          .parseTrc20AssetStatusPojo(block, logInfos);
      trc20TrackerTrigger.setAssetStatusList(assetStatusPojos);
    }

    // todo chuanqiang 通过accountInfoMap 处理得到list
//    final AccountStore.AccountInfo accountInfo = accountInfoMap.get(null);
//    accountInfo.getAccountAddress();

    logger.info("---------------------trc20TrackerTrigger------------------------{}",
        JSONObject.toJSONString(trc20TrackerTrigger));
  }

  @Override
  public void processTrigger() {
    EventPluginLoader.getInstance().postTRC20TrackerTrigger(trc20TrackerTrigger);
  }


}
