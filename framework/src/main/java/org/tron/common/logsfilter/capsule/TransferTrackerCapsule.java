package org.tron.common.logsfilter.capsule;

import com.alibaba.fastjson.JSON;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.spongycastle.util.encoders.Hex;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.tron.common.entity.AssetTransfer;
import org.tron.common.entity.AssetTransferLogInfo;
import org.tron.common.logsfilter.EventPluginLoader;
import org.tron.common.logsfilter.TRC20Utils;
import org.tron.common.logsfilter.trigger.BalanceTrackerTrigger;
import org.tron.common.runtime.vm.LogInfo;
import org.tron.common.utils.StringUtil;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.db.TransactionTrace;
import org.tron.core.db.accountchange.AccountChangeRecord;
import org.tron.protos.Protocol;
import org.tron.protos.contract.AssetIssueContractOuterClass;
import org.tron.protos.contract.BalanceContract;
import org.tron.protos.contract.SmartContractOuterClass;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedList;
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
    List<AssetTransfer> trxAssetTransferList = new ArrayList<>();
    List<AssetTransfer> trc10AssetTransferList = new ArrayList<>();

    for (TransactionCapsule transactionCapsule : transactionCapsules) {
      AssetTransferLogInfo assetTransferLogInfo = new AssetTransferLogInfo();
      List<LogInfo> innerList = transactionCapsule.getTrxTrace().getTransactionContext()
          .getProgramResult().getLogInfoList();
      if (innerList != null && innerList.size() > 0) {
        assetTransferLogInfo.setLogInfoList(innerList);
        assetTransferLogInfo.setTxId(transactionCapsule.getTransactionId().toString());
        assetTransferLogInfos.add(assetTransferLogInfo);
      }

      //transfer record trx/trc10
      handlerTrxTransfer(transactionCapsule, trxAssetTransferList);
      handlerTrc10Transfer(transactionCapsule, trc10AssetTransferList);

      //transfer record
      transferTrackerTrigger.setTrxAssetTransferList(trxAssetTransferList);
      transferTrackerTrigger.setTrc10AssetTransferList(trc10AssetTransferList);
    }

    if (assetTransferLogInfos.size() > 0) {
      Map<String, Object> result = TRC20Utils.parseTrc20Transfer(assetTransferLogInfos);

      //transfer record trc20/trc721
      transferTrackerTrigger.setTrc20AssetTransferList((List<AssetTransfer>) result.get(TRC20Utils.TRC20_TRANSFER));
      transferTrackerTrigger.setTrc721AssetTransferList((List<AssetTransfer>) result.get(TRC20Utils.TRC721_TRANSFER));
    }
  }

  @Override
  public void processTrigger() {
    EventPluginLoader.getInstance().postTransferTrigger(transferTrackerTrigger);
  }

  private void handlerTrc10Transfer(TransactionCapsule transactionCapsule, List<AssetTransfer> assetTransferList) {
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
            AssetTransfer assetTransfer = new AssetTransfer();
            assetTransfer.setFromAddress(StringUtil.encode58Check(internalTransaction.getSender()));
            assetTransfer.setToAddress(StringUtil.encode58Check(internalTransaction.getReceiveAddress()));

            assetTransfer.setAmount(BigInteger.valueOf(value));
            assetTransfer.setTxId(Hex.toHexString(internalTransaction.getHash()));
            assetTransfer.setTokenAddress(key);
            if (StringUtils.isEmpty(key) || "0".equals(key)) {
              assetTransfer.setAssetType(0);
            } else {
              assetTransfer.setAssetType(1);
            }

            logger.info("handlerTrc10Transfer isTvm={}, isTrc10={}, assetTransfer={}", isTvm, isTrc10, JSON.toJSONString(assetTransfer));
            assetTransferList.add(assetTransfer);
          });
        });
      } else if (isTrc10) {
        AssetIssueContractOuterClass.TransferAssetContract transferAssetContract = transactionCapsule.getInstance().getRawData().getContract(0).getParameter().unpack(AssetIssueContractOuterClass.TransferAssetContract.class);
        AssetTransfer assetTransfer = new AssetTransfer();
        assetTransfer.setAmount(BigInteger.valueOf(transferAssetContract.getAmount()));
        assetTransfer.setFromAddress(StringUtil.encode58Check(transferAssetContract.getOwnerAddress().toByteArray()));
        assetTransfer.setToAddress(StringUtil.encode58Check(transferAssetContract.getToAddress().toByteArray()));
        assetTransfer.setAssetType(1);
        assetTransfer.setTxId(transactionCapsule.getTransactionId().toString());
        assetTransfer.setTokenAddress(transferAssetContract.getAssetName().toStringUtf8());
        logger.info("handlerTrc10Transfer isTvm={}, isTrc10={}, assetTransfer={}", isTvm, isTrc10, JSON.toJSONString(assetTransfer));
        assetTransferList.add(assetTransfer);

      }
    }  catch (Exception ex) {
      logger.error("", ex);
    }
  }

  private void handlerTrxTransfer(TransactionCapsule transactionCapsule, List<AssetTransfer> assetTransferList) {
    try {
      boolean isTrx = transactionCapsule.getInstance().getRawData().getContract(0).getType() == Protocol.Transaction.Contract.ContractType.TransferContract;
      if (!isTrx) {
        return;
      }

      BalanceContract.TransferContract transferContract = transactionCapsule.getInstance().getRawData().getContract(0).getParameter().unpack(BalanceContract.TransferContract.class);
      AssetTransfer assetTransfer = new AssetTransfer();
      assetTransfer.setTokenAddress("");
      assetTransfer.setFromAddress(StringUtil.encode58Check(transferContract.getOwnerAddress().toByteArray()));
      assetTransfer.setToAddress(StringUtil.encode58Check(transferContract.getToAddress().toByteArray()));
      assetTransfer.setAssetType(0);
      assetTransfer.setAmount(BigInteger.valueOf(transferContract.getAmount()));
      assetTransfer.setTxId(transactionCapsule.getTransactionId().toString());

      logger.info("handlerTrxTransfer assetTransfer={}", JSON.toJSONString(assetTransfer));
      assetTransferList.add(assetTransfer);
    } catch (Exception ex) {
      logger.error("", ex);
    }
  }


}
