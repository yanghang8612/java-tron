package org.tron.common.logsfilter.capsule;

import com.alibaba.fastjson.JSON;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.tron.common.entity.AssetTransferInfo;
import org.tron.common.entity.AuthInfo;
import org.tron.common.entity.OwnerAuthInfo;
import org.tron.common.logsfilter.EventPluginLoader;
import org.tron.common.runtime.InternalTransaction;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.Commons;
import org.tron.common.utils.StringUtil;
import org.tron.common.utils.WalletUtil;
import org.tron.core.ChainBaseManager;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.protos.Protocol;
import org.tron.protos.contract.AccountContract;
import org.tron.protos.contract.AssetIssueContractOuterClass;
import org.tron.protos.contract.BalanceContract;
import org.tron.protos.contract.SmartContractOuterClass;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Slf4j
public class MultiAuthTrackerCapsule extends TriggerCapsule {

    @Getter
    @Setter
    private MultiAuthTrackerTrigger multiAuthTrackerTrigger;

    private ChainBaseManager chainBaseManager;

    public MultiAuthTrackerCapsule(BlockCapsule block) {
      logger.info("MultiAuthTrackerCapsule start, blockNum={}", block.getNum());
      multiAuthTrackerTrigger = new MultiAuthTrackerTrigger();
      multiAuthTrackerTrigger.setBlockHash(block.getBlockId().toString());
      multiAuthTrackerTrigger.setParentHash(block.getParentHash().toString());
      multiAuthTrackerTrigger.setBlockNumber(block.getNum());
      multiAuthTrackerTrigger.setTimeStamp(block.getTimeStamp());
      List<TransactionCapsule> transactionCapsules = block.getTransactions();

      Map<String, List<AuthInfo>> ownerAuthsMap = new HashMap<>();
      for (TransactionCapsule transactionCapsule : transactionCapsules) {
        //auth
        List<AuthInfo> authInfoList = new ArrayList<>();
        handlerTransferAuth(transactionCapsule, authInfoList);
        if (CollectionUtils.isEmpty(authInfoList)) {
          continue;
        }

        //save into map
        String ownerAddress = authInfoList.get(0).getOwnerAddress();
        List<AuthInfo> ownerNewAuthList = ownerAuthsMap.get(ownerAddress);
        if (CollectionUtils.isEmpty(ownerNewAuthList)) {
          ownerNewAuthList = new ArrayList<>();
          ownerNewAuthList.addAll(authInfoList);
        }
        ownerAuthsMap.put(ownerAddress, ownerNewAuthList);
      }

      if (CollectionUtils.isEmpty(ownerAuthsMap)) {
        return;
      }

      List<OwnerAuthInfo> ownerAuthInfoList = new ArrayList<>();
      ownerAuthsMap.forEach((ownerAddress, newAuthInfoList)-> {
        byte[] ownerByte = Commons.decode58Check(ownerAddress);
        AccountCapsule accountCapsule = chainBaseManager.getAccountStore().get(ownerByte);
        List<AuthInfo> oldAuthInfoList = new ArrayList<>();
        getAuthList(ownerAddress, accountCapsule.getInstance().getOwnerPermission(),
            accountCapsule.getInstance().getWitnessPermission(),
            accountCapsule.getInstance().getActivePermissionList(), oldAuthInfoList);

        OwnerAuthInfo ownerAuthInfo = new OwnerAuthInfo(ownerAddress, oldAuthInfoList, newAuthInfoList);
        ownerAuthInfoList.add(ownerAuthInfo);
      });

      if (CollectionUtils.isEmpty(ownerAuthInfoList)) {
        return;
      }
      multiAuthTrackerTrigger.setAuthInfoList(ownerAuthInfoList);
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
      EventPluginLoader.getInstance().postMultiAuthTrigger(multiAuthTrackerTrigger);
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

    private AuthInfo getAuthInfo(String ownerAddress, Protocol.Permission permission) {
      return new AuthInfo(ownerAddress, StringUtil.encode58Check(permission.getKeys(0).getAddress().toByteArray()),
          ByteArray.toHexString(permission.getOperations().toByteArray()), permission.getType().getNumber(),
          permission.getId(), permission.getThreshold(), permission.getKeys(0).getWeight());
    }

    private void getAuthList(String ownerAddress,
                             Protocol.Permission owner,
                             Protocol.Permission witness,
                             List<Protocol.Permission> actives,
                             List<AuthInfo> authInfoList) {

      AuthInfo ownerAuthInfo = getAuthInfo(ownerAddress, owner);
      authInfoList.add(ownerAuthInfo);

      AuthInfo witnessAuthInfo = getAuthInfo(ownerAddress, witness);
      authInfoList.add(witnessAuthInfo);

      if (!CollectionUtils.isEmpty(actives)) {
        actives.forEach(item -> {
          AuthInfo activeAuthInfo = getAuthInfo(ownerAddress, item);
          authInfoList.add(activeAuthInfo);
        });
      }
    }

    private void handlerTransferAuth(TransactionCapsule transactionCapsule, List<AuthInfo> authInfoList) {
      try {
        boolean isAuth = transactionCapsule.getInstance().getRawData().getContract(0).getType() == Protocol.Transaction.Contract.ContractType.AccountPermissionUpdateContract;
        if (!isAuth) {
          return;
        }
        AccountContract.AccountPermissionUpdateContract accountPermissionUpdateContract = transactionCapsule.getInstance().getRawData().getContract(0).getParameter().unpack(AccountContract.AccountPermissionUpdateContract.class);
        String ownerAddress = StringUtil.encode58Check(accountPermissionUpdateContract.getOwnerAddress().toByteArray());
        Protocol.Permission owner = accountPermissionUpdateContract.getOwner();
        Protocol.Permission witness = accountPermissionUpdateContract.getWitness();
        List<Protocol.Permission> actives = accountPermissionUpdateContract.getActivesList();
        getAuthList(ownerAddress, owner, witness, actives, authInfoList);
        logger.info("handlerTrxTransferAuth authInfoList={}", JSON.toJSONString(authInfoList));
      } catch (Exception ex) {
        logger.error("", ex);
      }
    }

  }
