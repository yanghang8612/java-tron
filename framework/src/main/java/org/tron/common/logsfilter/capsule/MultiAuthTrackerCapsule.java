package org.tron.common.logsfilter.capsule;

import com.alibaba.fastjson.JSON;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.tron.core.db.accountchange.MultiAuthRecord;
import org.tron.protos.Protocol;
import org.tron.protos.contract.AccountContract;

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

    @Autowired
    MultiAuthRecord multiAuthRecord;

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
        List<AuthInfo> oldAuthInfoList = multiAuthRecord.getAccountAuthsMap().get(ownerAddress);
        OwnerAuthInfo ownerAuthInfo = new OwnerAuthInfo(ownerAddress, oldAuthInfoList, newAuthInfoList);
        ownerAuthInfoList.add(ownerAuthInfo);
      });

      if (CollectionUtils.isEmpty(ownerAuthInfoList)) {
        return;
      }
      multiAuthTrackerTrigger.setAuthInfoList(ownerAuthInfoList);
    }

    @Override
    public void processTrigger() {
      EventPluginLoader.getInstance().postMultiAuthTrigger(multiAuthTrackerTrigger);
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
        authInfoList = AuthInfo.getAuthList(ownerAddress, owner, witness, actives);
        logger.info("handlerTrxTransferAuth authInfoList={}", JSON.toJSONString(authInfoList));
      } catch (Exception ex) {
        logger.error("", ex);
      }
    }

  }
