package org.tron.common.logsfilter.capsule;

import com.alibaba.fastjson.JSON;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.tron.common.entity.AssetTransferInfo;
import org.tron.common.entity.AssetTransferLogInfo;
import org.tron.common.logsfilter.EventPluginLoader;
import org.tron.common.logsfilter.TRC20Utils;
import org.tron.common.runtime.InternalTransaction;
import org.tron.common.runtime.vm.LogInfo;
import org.tron.common.utils.StringUtil;
import org.tron.common.utils.WalletUtil;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.protos.Protocol;
import org.tron.protos.contract.AssetIssueContractOuterClass;
import org.tron.protos.contract.BalanceContract;
import org.tron.protos.contract.SmartContractOuterClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
public class TransferTrackerCapsule extends TriggerCapsule {

  @Getter
  @Setter
  private TransferTrackerTrigger transferTrackerTrigger;

  public TransferTrackerCapsule(BlockCapsule block) {
    logger.info("TransferTrackerCapsule start, blocKNum={}", block.getNum());
    transferTrackerTrigger = new TransferTrackerTrigger();
    transferTrackerTrigger.setBlockHash(block.getBlockId().toString());
    transferTrackerTrigger.setParentHash(block.getParentHash().toString());
    transferTrackerTrigger.setBlockNumber(block.getNum());
    transferTrackerTrigger.setTimeStamp(block.getTimeStamp());
    List<TransactionCapsule> transactionCapsules = block.getTransactions();

    List<AssetTransferLogInfo> assetTransferLogInfos = new ArrayList<>();
    List<AssetTransferInfo> trxAssetTransferInfoList = new ArrayList<>();
    List<AssetTransferInfo> trc10AssetTransferInfoList = new ArrayList<>();

    for (TransactionCapsule transactionCapsule : transactionCapsules) {
      AssetTransferLogInfo assetTransferLogInfo = new AssetTransferLogInfo();
      List<LogInfo> innerList = transactionCapsule.getTrxTrace().getTransactionContext()
          .getProgramResult().getLogInfoList();
      if (innerList != null && innerList.size() > 0) {
        assetTransferLogInfo.setLogInfoList(innerList);
        assetTransferLogInfo.setTxId(transactionCapsule.getTransactionId().toString());
        assetTransferLogInfo.setNote(convertNote(transactionCapsule));

        // 这里只能会得到true
        if (Protocol.Transaction.Result.contractResult.SUCCESS == transactionCapsule.getInstance().getRet(0).getContractRet()) {
          assetTransferLogInfo.setIsSuccess(true);
        } else {
          assetTransferLogInfo.setIsSuccess(false);
        }

        assetTransferLogInfos.add(assetTransferLogInfo);
      }

      //transfer record trx/trc10
      handlerTrxTransfer(transactionCapsule, trxAssetTransferInfoList);
      handlerTrc10Transfer(transactionCapsule, trxAssetTransferInfoList, trc10AssetTransferInfoList);

      //transfer record
      transferTrackerTrigger.setTrxAssetTransferInfoList(trxAssetTransferInfoList);
      transferTrackerTrigger.setTrc10AssetTransferInfoList(trc10AssetTransferInfoList);
    }

    if (assetTransferLogInfos.size() > 0) {
      Map<String, Object> result = TRC20Utils.parseTrc20Transfer(assetTransferLogInfos);

      //transfer record trc20/trc721
      transferTrackerTrigger.setTrc20AssetTransferInfoList((List<AssetTransferInfo>) result.get(TRC20Utils.TRC20_TRANSFER));
      transferTrackerTrigger.setTrc721AssetTransferInfoList((List<AssetTransferInfo>) result.get(TRC20Utils.TRC721_TRANSFER));
    }
  }

  public String convertNote(TransactionCapsule transactionCapsule) {
    if (transactionCapsule == null || transactionCapsule.getInstance() == null) {
      return null;
    }

    final Protocol.Transaction.raw rawData = transactionCapsule.getInstance().getRawData();

    if (rawData == null || rawData.getData() == null || rawData.getData().size() == 0) {
      return null;
    }

    final String stringUtf8 = rawData.getData().toStringUtf8();
    return stringUtf8;
  }

  @Override
  public void processTrigger() {
    EventPluginLoader.getInstance().postTransferTrigger(transferTrackerTrigger);
  }

  private void handlerTrc10Transfer(TransactionCapsule transactionCapsule,
                                    List<AssetTransferInfo> trxAssetTransferInfoList,
                                    List<AssetTransferInfo> assetTransferInfoList) {
    try {
      final Protocol.Transaction.Contract contract = transactionCapsule.getInstance().getRawData().getContract(0);
      boolean isTrigger = contract.getType() == Protocol.Transaction.Contract.ContractType.TriggerSmartContract;
      boolean isCreate = contract.getType() == Protocol.Transaction.Contract.ContractType.CreateSmartContract;
      boolean isTrc10 = contract.getType() == Protocol.Transaction.Contract.ContractType.TransferAssetContract;


      if (isTrigger || isCreate ) {
        if (Protocol.Transaction.Result.contractResult.SUCCESS != transactionCapsule.getInstance().getRet(0).getContractRet()) {
          return;
        }

        if (isTrigger) {
          handlerTrigger(transactionCapsule, trxAssetTransferInfoList, assetTransferInfoList);
        } else if (isCreate) {
          handlerCreate(transactionCapsule, trxAssetTransferInfoList, assetTransferInfoList);
        }

        transactionCapsule.getTrxTrace().getRuntimeResult().getInternalTransactions().forEach(internalTransaction -> {
          if (internalTransaction.getValue() > 0) {
            convertInfo("", internalTransaction.getValue(), internalTransaction, transactionCapsule, trxAssetTransferInfoList, assetTransferInfoList);
          }

          if (!CollectionUtils.isEmpty(internalTransaction.getTokenInfo())) {
            internalTransaction.getTokenInfo().forEach((key, value) -> {
              convertInfo(key, value, internalTransaction, transactionCapsule, trxAssetTransferInfoList, assetTransferInfoList);
            });
          }
        });
      } else if (isTrc10) {
        AssetIssueContractOuterClass.TransferAssetContract transferAssetContract = contract.getParameter().unpack(AssetIssueContractOuterClass.TransferAssetContract.class);
        AssetTransferInfo assetTransferInfo = new AssetTransferInfo();
        assetTransferInfo.setAmount(String.valueOf(transferAssetContract.getAmount()));
        assetTransferInfo.setFromAddress(StringUtil.encode58Check(transferAssetContract.getOwnerAddress().toByteArray()));
        assetTransferInfo.setToAddress(StringUtil.encode58Check(transferAssetContract.getToAddress().toByteArray()));
        assetTransferInfo.setAssetType(1);
        assetTransferInfo.setTxId(transactionCapsule.getTransactionId().toString());
        assetTransferInfo.setNote(convertNote(transactionCapsule));
        assetTransferInfo.setTokenAddress(transferAssetContract.getAssetName().toStringUtf8());
        assetTransferInfo.setIsSuccess(true);

        logger.info("handlerTrc10Transfer isTvm={}, isTrc10={}, assetTransfer={}", false, isTrc10, JSON.toJSONString(assetTransferInfo));
        assetTransferInfoList.add(assetTransferInfo);
      }
    }  catch (Exception ex) {
      logger.error("", ex);
    }
  }

  private void handlerTrigger(TransactionCapsule transactionCapsule,
                              List<AssetTransferInfo> trxAssetTransferInfoList,
                              List<AssetTransferInfo> assetTransferInfoList) {
    try {
      SmartContractOuterClass.TriggerSmartContract triggerSmartContract = transactionCapsule.getInstance().getRawData().getContract(0).getParameter().unpack(SmartContractOuterClass.TriggerSmartContract.class);
      //trx
      long callValue = triggerSmartContract.getCallValue();

      // trc10
      long tokenId = triggerSmartContract.getTokenId();
      long callTokenValue = triggerSmartContract.getCallTokenValue();

      if (callValue <= 0 && callTokenValue <= 0) {
        return;
      }

      String from = StringUtil.encode58Check(triggerSmartContract.getOwnerAddress().toByteArray());
      String to = StringUtil.encode58Check(triggerSmartContract.getContractAddress().toByteArray());
      String txid = transactionCapsule.getTransactionId().toString();
      String note = convertNote(transactionCapsule);

      if (callValue > 0) {
        convertInfo("", callValue, from, to, txid, note, trxAssetTransferInfoList, assetTransferInfoList);
      }

      if (callTokenValue > 0) {
        convertInfo(String.valueOf(tokenId), callTokenValue, from, to, txid, note, trxAssetTransferInfoList, assetTransferInfoList);
      }
    } catch (Exception ex) {
      logger.error("", ex);
    }
  }

  private void handlerCreate(TransactionCapsule transactionCapsule,
                              List<AssetTransferInfo> trxAssetTransferInfoList,
                              List<AssetTransferInfo> assetTransferInfoList) {
    try {
      SmartContractOuterClass.CreateSmartContract createSmartContract = transactionCapsule.getInstance().getRawData().getContract(0).getParameter().unpack(SmartContractOuterClass.CreateSmartContract.class);
      //trx
      long callValue = createSmartContract.getNewContract().getCallValue();

      // trc10
      long tokenId = createSmartContract.getTokenId();
      long callTokenValue = createSmartContract.getCallTokenValue();

      if (callValue <= 0 && callTokenValue <= 0) {
        return;
      }

      String from = StringUtil.encode58Check(createSmartContract.getOwnerAddress().toByteArray());
      String to = StringUtil.encode58Check(WalletUtil.generateContractAddress(transactionCapsule.getInstance()));
      String txid = transactionCapsule.getTransactionId().toString();
      String note = convertNote(transactionCapsule);

      if (callValue > 0) {
        convertInfo("", callValue, from, to, txid, note, trxAssetTransferInfoList, assetTransferInfoList);
      }

      if (callTokenValue > 0) {
        convertInfo(String.valueOf(tokenId), callTokenValue, from, to, txid, note, trxAssetTransferInfoList, assetTransferInfoList);
      }
    } catch (Exception ex) {
      logger.error("", ex);
    }
  }

  private void convertInfo(String key, Long value, InternalTransaction internalTransaction,
                           TransactionCapsule transactionCapsule,
                           List<AssetTransferInfo> trxAssetTransferInfoList,
                           List<AssetTransferInfo> assetTransferInfoList) {
    String from = StringUtil.encode58Check(internalTransaction.getSender());
    String to = StringUtil.encode58Check(internalTransaction.getReceiveAddress());
    String txid = transactionCapsule.getTransactionId().toString();
    String note = convertNote(transactionCapsule);
    convertInfo(key, value, from, to, txid, note, trxAssetTransferInfoList, assetTransferInfoList);
  }

  private void convertInfo(String key, Long value, String from, String to,
                           String txid, String note,
                           List<AssetTransferInfo> trxAssetTransferInfoList,
                           List<AssetTransferInfo> assetTransferInfoList) {
    AssetTransferInfo assetTransferInfo = new AssetTransferInfo();
    assetTransferInfo.setFromAddress(from);
    assetTransferInfo.setToAddress(to);

    assetTransferInfo.setAmount(String.valueOf(value));
    assetTransferInfo.setTxId(txid);
    assetTransferInfo.setNote(note);
    assetTransferInfo.setTokenAddress(key);
    if (StringUtils.isEmpty(key)) {
      assetTransferInfo.setAssetType(0);
    } else {
      assetTransferInfo.setAssetType(1);
    }

    assetTransferInfo.setIsSuccess(true);

    if (assetTransferInfo.getAssetType() == 0) {
      trxAssetTransferInfoList.add(assetTransferInfo);
    } else {
      assetTransferInfoList.add(assetTransferInfo);
    }
  }

  private void handlerTrxTransfer(TransactionCapsule transactionCapsule, List<AssetTransferInfo> assetTransferInfoList) {
    try {
      boolean isTrx = transactionCapsule.getInstance().getRawData().getContract(0).getType() == Protocol.Transaction.Contract.ContractType.TransferContract;
      if (!isTrx) {
        return;
      }

      BalanceContract.TransferContract transferContract = transactionCapsule.getInstance().getRawData().getContract(0).getParameter().unpack(BalanceContract.TransferContract.class);
      AssetTransferInfo assetTransferInfo = new AssetTransferInfo();
      assetTransferInfo.setTokenAddress("");
      assetTransferInfo.setFromAddress(StringUtil.encode58Check(transferContract.getOwnerAddress().toByteArray()));
      assetTransferInfo.setToAddress(StringUtil.encode58Check(transferContract.getToAddress().toByteArray()));
      assetTransferInfo.setAssetType(0);
      assetTransferInfo.setAmount(String.valueOf(transferContract.getAmount()));
      assetTransferInfo.setTxId(transactionCapsule.getTransactionId().toString());
      assetTransferInfo.setNote(convertNote(transactionCapsule));

      //trx失败不上链
      assetTransferInfo.setIsSuccess(true);

      logger.info("handlerTrxTransfer assetTransfer={}", JSON.toJSONString(assetTransferInfo));
      assetTransferInfoList.add(assetTransferInfo);
    } catch (Exception ex) {
      logger.error("", ex);
    }
  }


}
