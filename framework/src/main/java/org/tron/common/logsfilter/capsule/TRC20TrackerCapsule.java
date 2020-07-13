package org.tron.common.logsfilter.capsule;

import com.alibaba.fastjson.JSONObject;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;
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

    List<TRC20TrackerTrigger.Trc10StatusPojo> trc10StatusList = new LinkedList<>();
    List<TRC20TrackerTrigger.TrxStatusPojo> trxStatusList = new LinkedList<>();
    handlerTrxAndTrc10(accountInfoMap, trxStatusList, trc10StatusList);
    trc20TrackerTrigger.setTrxStatusList(trxStatusList);
    trc20TrackerTrigger.setTrc10StatusList(trc10StatusList);

    logger.info("---------------------trc20TrackerTrigger------------------------{}",
        JSONObject.toJSONString(trc20TrackerTrigger));
    //logger.info("---------------------trc20TrackerTrigger------------------------{}", JSONObject.toJSONString(trc20TrackerTrigger));
  }

  private void handlerTrxAndTrc10(Map<byte[], AccountStore.AccountInfo> accountInfoMap,
                                  List<TRC20TrackerTrigger.TrxStatusPojo> trxStatusList,
                                  List<TRC20TrackerTrigger.Trc10StatusPojo> trc10StatusList) {
    if (CollectionUtils.isEmpty(accountInfoMap)) {
      return;
    }

    accountInfoMap.values().stream().forEach(info -> {
      final TRC20TrackerTrigger.TrxStatusPojo trxStatusPojo = converterTrx(info);
      trxStatusList.add(trxStatusPojo);
      final List<TRC20TrackerTrigger.Trc10StatusPojo> trc10List = converterTrc10(info.getAccountAddress(), info.getTrc10Map());
      trc10StatusList.addAll(trc10List);
    });
  }

  private TRC20TrackerTrigger.TrxStatusPojo converterTrx(AccountStore.AccountInfo info) {
    TRC20TrackerTrigger.TrxStatusPojo trx = new TRC20TrackerTrigger.TrxStatusPojo();
    trx.setAccountAddress(info.getAccountAddress());
    trx.setAdd(info.getAdd());

    trx.setBalance(String.valueOf(info.getBalance()));
    trx.setFrozenBalance(String.valueOf(info.getFrozenBalance()));
    trx.setEnergyFrozenBalance(String.valueOf(info.getEnergyFrozenBalance()));
    trx.setDelegatedFrozenBalanceForEnergy(String.valueOf(info.getDelegatedFrozenBalanceForEnergy()));
    trx.setDelegatedFrozenBalanceForBandwidth(String.valueOf(info.getDelegatedFrozenBalanceForBandwidth()));

    trx.setIncrementBalance(String.valueOf(info.getIncrementBalance()));
    trx.setIncrementFrozenBalance(String.valueOf(info.getIncrementFrozenBalance()));
    trx.setIncrementEnergyFrozenBalance(String.valueOf(info.getIncrementEnergyFrozenBalance()));
    trx.setIncrementDelegatedFrozenBalanceForEnergy(String.valueOf(info.getIncrementDelegatedFrozenBalanceForEnergy()));
    trx.setIncrementDelegatedFrozenBalanceForBandwidth(String.valueOf(info.getIncrementDelegatedFrozenBalanceForBandwidth()));
    return trx;
  }

  private List<TRC20TrackerTrigger.Trc10StatusPojo> converterTrc10(String accountAddress,
                                                                   Map<String, AccountStore.Trc10Info> trc10Map) {
    List<TRC20TrackerTrigger.Trc10StatusPojo> list = new LinkedList<>();
    if (CollectionUtils.isEmpty(trc10Map)) {
      return list;
    }

    trc10Map.forEach((key, info) -> {
      TRC20TrackerTrigger.Trc10StatusPojo trc10Info = new TRC20TrackerTrigger.Trc10StatusPojo();
      trc10Info.setAccountAddress(accountAddress);
      trc10Info.setTokenId(info.getTokenId());
      trc10Info.setBalance(String.valueOf(info.getBalance()));
      trc10Info.setIncrementBalance(String.valueOf(info.getIncrementBalance()));
      list.add(trc10Info);
    });
    return list;
  }

  @Override
  public void processTrigger() {
    EventPluginLoader.getInstance().postTRC20TrackerTrigger(trc20TrackerTrigger);
  }


}
