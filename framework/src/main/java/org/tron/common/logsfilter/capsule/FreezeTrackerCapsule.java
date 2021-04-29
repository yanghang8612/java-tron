package org.tron.common.logsfilter.capsule;

import com.google.protobuf.InvalidProtocolBufferException;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;
import org.tron.common.logsfilter.EventPluginLoader;
import org.tron.common.logsfilter.trigger.FreezeBalanceTrigger;
import org.tron.common.runtime.vm.LogInfo;
import org.tron.common.utils.Commons;
import org.tron.common.utils.Sha256Hash;
import org.tron.common.utils.StringUtil;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.DelegatedResourceCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.store.DelegatedResourceStore;
import org.tron.protos.Protocol;
import org.tron.protos.contract.BalanceContract;
import org.tron.protos.contract.Common;

import java.util.*;

@Slf4j
public class FreezeTrackerCapsule extends TriggerCapsule {


  @Getter
  @Setter
  private FreezeBalanceTrigger freezeBalanceTrigger;

  public FreezeTrackerCapsule(BlockCapsule block, DelegatedResourceStore delegatedResourceStore) {
    freezeBalanceTrigger = new FreezeBalanceTrigger();
    freezeBalanceTrigger.setBlockHash(block.getBlockId().toString());
    freezeBalanceTrigger.setParentHash(block.getParentHash().toString());
    freezeBalanceTrigger.setBlockNumber(block.getNum());
    freezeBalanceTrigger.setTimeStamp(block.getTimeStamp());


    List<TransactionCapsule> transactionCapsules = block.getTransactions();
    List<LogInfo> logInfos = new ArrayList<>();
    for (TransactionCapsule transactionCapsule : transactionCapsules) {
      List<LogInfo> innerList = transactionCapsule.getTrxTrace().getTransactionContext()
          .getProgramResult().getLogInfoList();
      if (innerList != null && innerList.size() > 0) {
        logInfos.addAll(innerList);
      }
    }

    Map<String, Map<String, Set<Common.ResourceCode>>> result = new HashMap<>();

    block.getTransactions().forEach(item -> {
      final Sha256Hash transactionId = item.getTransactionId();
//      item.getTrxTrace().getRuntimeResult().getInternalTransactions().forEach(internalTransaction -> {
//        final Map<String, Long> tokenInfo = internalTransaction.getTokenInfo();
//        tokenInfo.get("_");
//        tokenInfo.get("1002000");
//        internalTransaction.getReceiveAddress();
//        internalTransaction.getSender();
//      });
      final Protocol.Transaction.Contract.ContractType type = item.getInstance().getRawData().getContract(0).getType();

      if (Protocol.Transaction.Contract.ContractType.FreezeBalanceContract.equals(type)) {
        try {
          final BalanceContract.FreezeBalanceContract freezeBalanceContract = item.getInstance().getRawData().getContract(0).getParameter().unpack(BalanceContract.FreezeBalanceContract.class);
          String fromAddress = StringUtil.encode58Check(freezeBalanceContract.getOwnerAddress().toByteArray());

          if (freezeBalanceContract.getReceiverAddress() != null && !freezeBalanceContract.getReceiverAddress().isEmpty()) {
            String toAddress = StringUtil.encode58Check(freezeBalanceContract.getReceiverAddress().toByteArray());
            handlerAddress(fromAddress, toAddress, freezeBalanceContract.getResource(), result);
          }
        } catch (InvalidProtocolBufferException e) {
          logger.error("", e);
          return;
        }
      }
      else if (Protocol.Transaction.Contract.ContractType.UnfreezeBalanceContract.equals(type)) {
        try {
          final BalanceContract.UnfreezeBalanceContract unfreezeBalanceContract = item.getInstance().getRawData().getContract(0).getParameter().unpack(BalanceContract.UnfreezeBalanceContract.class);
          String fromAddress = StringUtil.encode58Check(unfreezeBalanceContract.getOwnerAddress().toByteArray());

          if (unfreezeBalanceContract.getReceiverAddress() != null && !unfreezeBalanceContract.getReceiverAddress().isEmpty()) {
            String toAddress = StringUtil.encode58Check(unfreezeBalanceContract.getReceiverAddress().toByteArray());
            handlerAddress(fromAddress, toAddress, unfreezeBalanceContract.getResource(), result);
          }
        } catch (InvalidProtocolBufferException e) {
          logger.error("", e);
        }
      }

      final List<FreezeBalanceTrigger.FreezeBalance> freezeBalanceList = handlerFreezeAddress(result, delegatedResourceStore);
      freezeBalanceTrigger.setFreezeList(freezeBalanceList);

//      type.equals(Protocol.Transaction.Contract.ContractType.TransferContract);
      try {
//        final BalanceContract.TransferContract transferContract = item.getInstance().getRawData().getContract(0).getParameter().unpack(BalanceContract.TransferContract.class);
//        transferContract.getOwnerAddress();
//        transferContract.getToAddress();
//        transferContract.getAmount();

        final BalanceContract.TransferContract transferContract = item.getInstance().getRawData().getContract(0).getParameter().unpack(BalanceContract.TransferContract.class);
        transferContract.getOwnerAddress();
        transferContract.getToAddress();
        transferContract.getAmount();

        final BalanceContract.FreezeBalanceContract freezeBalanceContract = item.getInstance().getRawData().getContract(0).getParameter().unpack(BalanceContract.FreezeBalanceContract.class);
        freezeBalanceContract.getFrozenBalance();
        freezeBalanceContract.getOwnerAddress();
        freezeBalanceContract.getReceiverAddress();
        freezeBalanceContract.getFrozenDuration(); //过期时间
        freezeBalanceContract.getResource();
//        freezeBalanceContract.getResourceValue(); // 数量

        final BalanceContract.UnfreezeBalanceContract unfreezeBalanceContract = item.getInstance().getRawData().getContract(0).getParameter().unpack(BalanceContract.UnfreezeBalanceContract.class);
        unfreezeBalanceContract.getOwnerAddress();
        unfreezeBalanceContract.getReceiverAddress();
        unfreezeBalanceContract.getResource();
      } catch (Exception e) {
        logger.error("", e);
      }
    });
  }

  private List<FreezeBalanceTrigger.FreezeBalance> handlerFreezeAddress(Map<String, Map<String, Set<Common.ResourceCode>>> result, DelegatedResourceStore delegatedResourceStore) {
    List<FreezeBalanceTrigger.FreezeBalance> freezeList = new LinkedList();
    if (CollectionUtils.isEmpty(result)) {
      return freezeList;
    }

    result.forEach((fromAddress, map) -> {
      if (CollectionUtils.isEmpty(map)) {
        return;
      }

      map.forEach((toAddress, set) -> {
        if (CollectionUtils.isEmpty(set)) {
          return;
        }
        byte[] keyVal = DelegatedResourceCapsule.createDbKey(Commons.decodeFromBase58Check(fromAddress), Commons.decodeFromBase58Check(toAddress));
        final DelegatedResourceCapsule resourceCapsule = delegatedResourceStore.get(keyVal);

        set.forEach(resourceCode -> {
          FreezeBalanceTrigger.FreezeBalance freezeBalance = new FreezeBalanceTrigger.FreezeBalance();
          freezeBalance.setFromAddress(fromAddress);
          freezeBalance.setToAddress(toAddress);

          switch (resourceCode) {
            case BANDWIDTH:
              freezeBalance.setResource(2);
              freezeBalance.setFreezeBalance(resourceCapsule.getFrozenBalanceForBandwidth());
              break;
            case ENERGY:
              freezeBalance.setResource(1);
              freezeBalance.setFreezeBalance(resourceCapsule.getFrozenBalanceForEnergy());
              break;
            default:
              break;
          }

          freezeList.add(freezeBalance);
        });
      });
    });

    return freezeList;
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
