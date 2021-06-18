package org.tron.common.logsfilter.capsule;

import com.alibaba.fastjson.JSON;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.spongycastle.util.encoders.Hex;
import org.springframework.util.StringUtils;
import org.tron.common.entity.AssetTransferInfo;
import org.tron.common.entity.AssetTransferLogInfo;
import org.tron.common.logsfilter.EventPluginLoader;
import org.tron.common.logsfilter.TRC20Utils;
import org.tron.common.runtime.vm.LogInfo;
import org.tron.common.utils.StringUtil;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.db.TransactionTrace;
import org.tron.protos.Protocol;
import org.tron.protos.contract.AssetIssueContractOuterClass;
import org.tron.protos.contract.BalanceContract;
import org.tron.protos.contract.SmartContractOuterClass;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
public class TransferTrackerCapsule extends TriggerCapsule {

  @Getter
  @Setter
  private TransferTrackerTrigger transferTrackerTrigger;

  public TransferTrackerCapsule(BlockCapsule block) {
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

        if (Protocol.Transaction.Result.contractResult.SUCCESS == transactionCapsule.getInstance().getRet(0).getContractRet()) {
          assetTransferLogInfo.setIsSuccess(true);
        } else {
          assetTransferLogInfo.setIsSuccess(false);
        }

        assetTransferLogInfos.add(assetTransferLogInfo);
      }

      //transfer record trx/trc10
      handlerTrxTransfer(transactionCapsule, trxAssetTransferInfoList);
      handlerTrc10Transfer(transactionCapsule, trc10AssetTransferInfoList);

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

  @Override
  public void processTrigger() {
    EventPluginLoader.getInstance().postTransferTrigger(transferTrackerTrigger);
  }

  private void handlerTrc10Transfer(TransactionCapsule transactionCapsule, List<AssetTransferInfo> assetTransferInfoList) {
    try {

      boolean isTvm = transactionCapsule.getInstance().getRawData().getContract(0).getType() == Protocol.Transaction.Contract.ContractType.TriggerSmartContract
          || transactionCapsule.getInstance().getRawData().getContract(0).getType() == Protocol.Transaction.Contract.ContractType.CreateSmartContract;
      boolean isTrc10 = transactionCapsule.getInstance().getRawData().getContract(0).getType() == Protocol.Transaction.Contract.ContractType.TransferAssetContract;

      SmartContractOuterClass.TriggerSmartContract triggerSmartContract = transactionCapsule.getInstance().getRawData().getContract(0).getParameter().unpack(SmartContractOuterClass.TriggerSmartContract.class);
      String contractAddress = StringUtil.encode58Check(TransactionTrace.convertToTronAddress(triggerSmartContract.getContractAddress().toByteArray()));
      long callValue = triggerSmartContract.getCallValue();
      long callTokenValue = triggerSmartContract.getCallTokenValue();
      long tokenId = triggerSmartContract.getTokenId();

      if (isTvm) {
        transactionCapsule.getTrxTrace().getRuntimeResult().getInternalTransactions().forEach(internalTransaction -> {

          internalTransaction.getTokenInfo().forEach((key, value) -> {
            AssetTransferInfo assetTransferInfo = new AssetTransferInfo();
            assetTransferInfo.setFromAddress(StringUtil.encode58Check(internalTransaction.getSender()));
            assetTransferInfo.setToAddress(StringUtil.encode58Check(internalTransaction.getReceiveAddress()));

            assetTransferInfo.setAmount(BigInteger.valueOf(value));
            assetTransferInfo.setTxId(Hex.toHexString(internalTransaction.getHash()));
            assetTransferInfo.setTokenAddress(key);
            if (StringUtils.isEmpty(key) || "0".equals(key)) {
              assetTransferInfo.setAssetType(0);
            } else {
              assetTransferInfo.setAssetType(1);
            }

            if (Protocol.Transaction.Result.contractResult.SUCCESS == transactionCapsule.getInstance().getRet(0).getContractRet()) {
              assetTransferInfo.setIsSuccess(true);
            } else {
              assetTransferInfo.setIsSuccess(false);
            }


            logger.info("handlerTrc10Transfer isTvm={}, isTrc10={}, assetTransfer={}", isTvm, isTrc10, JSON.toJSONString(assetTransferInfo));
            assetTransferInfoList.add(assetTransferInfo);
          });
        });
      } else if (isTrc10) {
        AssetIssueContractOuterClass.TransferAssetContract transferAssetContract = transactionCapsule.getInstance().getRawData().getContract(0).getParameter().unpack(AssetIssueContractOuterClass.TransferAssetContract.class);
        AssetTransferInfo assetTransferInfo = new AssetTransferInfo();
        assetTransferInfo.setAmount(BigInteger.valueOf(transferAssetContract.getAmount()));
        assetTransferInfo.setFromAddress(StringUtil.encode58Check(transferAssetContract.getOwnerAddress().toByteArray()));
        assetTransferInfo.setToAddress(StringUtil.encode58Check(transferAssetContract.getToAddress().toByteArray()));
        assetTransferInfo.setAssetType(1);
        assetTransferInfo.setTxId(transactionCapsule.getTransactionId().toString());
        assetTransferInfo.setTokenAddress(transferAssetContract.getAssetName().toStringUtf8());
        assetTransferInfo.setIsSuccess(true);

        logger.info("handlerTrc10Transfer isTvm={}, isTrc10={}, assetTransfer={}", isTvm, isTrc10, JSON.toJSONString(assetTransferInfo));
        assetTransferInfoList.add(assetTransferInfo);

      }
    }  catch (Exception ex) {
      logger.error("", ex);
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
      assetTransferInfo.setAmount(BigInteger.valueOf(transferContract.getAmount()));
      assetTransferInfo.setTxId(transactionCapsule.getTransactionId().toString());

      //trx失败不上链
      assetTransferInfo.setIsSuccess(true);

      logger.info("handlerTrxTransfer assetTransfer={}", JSON.toJSONString(assetTransferInfo));
      assetTransferInfoList.add(assetTransferInfo);
    } catch (Exception ex) {
      logger.error("", ex);
    }
  }


}
