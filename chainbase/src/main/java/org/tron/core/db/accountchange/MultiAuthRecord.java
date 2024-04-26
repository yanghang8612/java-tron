package org.tron.core.db.accountchange;

import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.tron.common.entity.AuthInfo;
import org.tron.common.entity.OwnerAuthInfo;
import org.tron.common.utils.Commons;
import org.tron.common.utils.StringUtil;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.store.AccountStore;
import org.tron.protos.Protocol;
import org.tron.protos.contract.AccountContract;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author ：Tron
 * @description ：
 * @date ：2022/1/28
 */
@Slf4j
@Service
public class MultiAuthRecord {

  private static volatile boolean recordMultiAuth = false;

  private static final Map<String, OwnerAuthInfo> ownerAuthMap = new HashMap<>();

  private AccountStore accountStore = null;

  public void startRecordOld(boolean record, BlockCapsule block, AccountStore accountStore) {
    this.recordMultiAuth = record;
    this.accountStore = accountStore;

    if (recordMultiAuth) {
      handlerTransferOldAuth(block);
    }
  }

  public void startRecordNew(BlockCapsule block) {
    if (recordMultiAuth) {
      handlerTransferNewAuth(block);
    }
  }

  private void handlerTransferOldAuth(BlockCapsule block) {
    logger.info("handlerTransferOldAuth start, blockNum={}", block.getNum());
    List<TransactionCapsule> transactionCapsules = block.getTransactions();

    for (TransactionCapsule transactionCapsule : transactionCapsules) {
      final OwnerAuthInfo ownerAuthInfo = handlerTransferAuth(transactionCapsule);
      if (ownerAuthInfo == null) {
        continue;
      }

      ownerAuthMap.put(ownerAuthInfo.getOwnerAddress(), ownerAuthInfo);
    }
  }

  private void handlerTransferNewAuth(BlockCapsule block) {
    if (CollectionUtils.isEmpty(ownerAuthMap)) {
      return;
    }

    logger.info("handlerTransferNewAuth start, blockNum={}", block.getNum());
    ownerAuthMap.forEach((ownerAddress, info) -> {
      final byte[] ownerAddressBytes = Commons.decodeFromBase58Check(ownerAddress);
      final AccountCapsule newAccount = accountStore.get(ownerAddressBytes);
      final List<AuthInfo> multiAuth = getMultiAuth(ownerAddress, newAccount);
      info.setNewAuthList(multiAuth);
    });
  }

  private OwnerAuthInfo handlerTransferAuth(TransactionCapsule transactionCapsule) {
    try {
      boolean isAuth = transactionCapsule.getInstance().getRawData().getContract(0).getType() == Protocol.Transaction.Contract.ContractType.AccountPermissionUpdateContract;
      if (!isAuth) {
        return null;
      }
      AccountContract.AccountPermissionUpdateContract accountPermissionUpdateContract = transactionCapsule.getInstance().getRawData().getContract(0).getParameter().unpack(AccountContract.AccountPermissionUpdateContract.class);
      final byte[] ownerAddressBytes = accountPermissionUpdateContract.getOwnerAddress().toByteArray();
      String ownerAddress = StringUtil.encode58Check(ownerAddressBytes);
//      Protocol.Permission owner = accountPermissionUpdateContract.getOwner();
//      Protocol.Permission witness = accountPermissionUpdateContract.getWitness();
//      List<Protocol.Permission> actives = accountPermissionUpdateContract.getActivesList();
//      final List<AuthInfo> authList = AuthInfo.getAuthList(ownerAddress, owner, witness, actives);
      final OwnerAuthInfo ownerAuthInfo = new OwnerAuthInfo();
      ownerAuthInfo.setOwnerAddress(ownerAddress);
//      ownerAuthInfo.setNewAuthList(authList);
      final AccountCapsule oldAccount = accountStore.get(ownerAddressBytes);

      // If the account does not exist, it must be activated in this block before the permission update transaction.
      // In order to deal with this case, we just create a fake new account and pass it to getMultiAuth method.
      if (oldAccount == null) {
        AccountCapsule newOwnerAccount =
            new AccountCapsule(ByteString.copyFrom(ownerAddressBytes), Protocol.AccountType.Normal);
        ownerAuthInfo.setOldAuthList(getMultiAuth(ownerAddress, newOwnerAccount));
      } else {
        ownerAuthInfo.setOldAuthList(getMultiAuth(ownerAddress, oldAccount));
      }

      return ownerAuthInfo;
    } catch (Exception ex) {
      logger.error("", ex);
    }

    return null;
  }

  public void clear() {
    this.recordMultiAuth = false;
    ownerAuthMap.clear();
  }

  public Map<String, OwnerAuthInfo> getOwnerAuthMap() {
    return ownerAuthMap;
  }

  private List<AuthInfo> getMultiAuth(String address, AccountCapsule accountCapsule) {
    return AuthInfo.getAuthList(address, accountCapsule.getInstance().getOwnerPermission(),
        accountCapsule.getInstance().getWitnessPermission(), accountCapsule.getInstance().getActivePermissionList());
  }
}
