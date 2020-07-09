package org.tron.core.store;

import com.google.common.collect.Maps;
import com.typesafe.config.ConfigObject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.tron.common.utils.Commons;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.db.TronStoreWithRevoking;
import org.tron.core.db.accountstate.AccountStateCallBackUtils;

@Slf4j(topic = "DB")
@Component
public class AccountStore extends TronStoreWithRevoking<AccountCapsule> {

  private static Map<String, byte[]> assertsAddress = new HashMap<>(); // key = name , value = address

  private static volatile boolean recordBalance = false;
  private static Map<byte[], AccountInfo> tempAccountMap = new HashMap<>();


  @Autowired
  private AccountStateCallBackUtils accountStateCallBackUtils;

  @Autowired
  private AccountStore(@Value("account") String dbName) {
    super(dbName);
  }


  public void startRecord(boolean record) {
    tempAccountMap.clear();
    this.recordBalance = record;
  }

  public Map<byte[], AccountInfo> getTempAccountMap() {
    return Maps.newHashMap(tempAccountMap);
  }

  public static void setAccount(com.typesafe.config.Config config) {
    List list = config.getObjectList("genesis.block.assets");
    for (int i = 0; i < list.size(); i++) {
      ConfigObject obj = (ConfigObject) list.get(i);
      String accountName = obj.get("accountName").unwrapped().toString();
      byte[] address = Commons.decodeFromBase58Check(obj.get("address").unwrapped().toString());
      assertsAddress.put(accountName, address);
    }
  }

  @Override
  public AccountCapsule get(byte[] key) {
    byte[] value = revokingDB.getUnchecked(key);
    return ArrayUtils.isEmpty(value) ? null : new AccountCapsule(value);
  }

  @Override
  public void put(byte[] key, AccountCapsule item) {
    final AccountCapsule oldAccount = get(key);
    super.put(key, item);
    accountStateCallBackUtils.accountCallBack(key, item);
    handler(key, oldAccount, item);
  }

  /**
   * Max TRX account.
   */
  public AccountCapsule getSun() {
    return getUnchecked(assertsAddress.get("Sun"));
  }

  /**
   * Min TRX account.
   */
  public AccountCapsule getBlackhole() {
    return getUnchecked(assertsAddress.get("Blackhole"));
  }

  /**
   * Get foundation account info.
   */
  public AccountCapsule getZion() {
    return getUnchecked(assertsAddress.get("Zion"));
  }

  private void handler(byte[] key, AccountCapsule oldAccount, AccountCapsule newAccount) {
    if (!recordBalance || newAccount == null) {
      return;
    }

    AccountInfo accountInfo = null;
    if (oldAccount == null) {
      accountInfo = AccountInfo.of(newAccount);
    }
    else {
      accountInfo = AccountInfo.of(oldAccount, newAccount);
    }

    final AccountInfo inMapInfo = tempAccountMap.get(oldAccount.getAddress().toByteArray());

    if (inMapInfo == null) {
      tempAccountMap.put(key, accountInfo);
      return;
    }

    mergeInfo(inMapInfo, accountInfo);
  }

  private void mergeInfo(AccountInfo inMapInfo, AccountInfo accountInfo) {
    // todo chuanqiang merge 余额 更新， diff余额 相加

  }


  @Data
  public static class AccountInfo {
    private String accountAddress;
    private Boolean add = false;

    private String balance;
    private String frozenBalance;
    private String energyFrozenBalance;
    private String delegatedFrozenBalanceForEnergy;
    private String delegatedFrozenBalanceForBandwidth;

    private String incrementBalance;
    private String incrementFrozenBalance;
    private String incrementEnergyFrozenBalance;
    private String incrementDelegatedFrozenBalanceForEnergy;
    private String incrementDelegatedFrozenBalanceForBandwidth;


    public static AccountInfo of(AccountCapsule account) {
      AccountInfo info = new AccountInfo();

      // todo chuanqiang 确定address
      info.setAccountAddress(account.getAddress().toString());
      info.setAdd(true);

      info.setBalance(String.valueOf(account.getBalance()));
      info.setFrozenBalance(String.valueOf(account.getFrozenBalance()));
      info.setEnergyFrozenBalance(String.valueOf(account.getEnergyFrozenBalance()));
      info.setDelegatedFrozenBalanceForEnergy(String.valueOf(account.getDelegatedFrozenBalanceForEnergy()));
      info.setDelegatedFrozenBalanceForBandwidth(String.valueOf(account.getDelegatedFrozenBalanceForBandwidth()));

      info.setIncrementBalance(String.valueOf(account.getBalance()));
      info.setIncrementFrozenBalance(String.valueOf(account.getFrozenBalance()));
      info.setIncrementEnergyFrozenBalance(String.valueOf(account.getEnergyFrozenBalance()));
      info.setIncrementDelegatedFrozenBalanceForEnergy(String.valueOf(account.getDelegatedFrozenBalanceForEnergy()));
      info.setIncrementDelegatedFrozenBalanceForBandwidth(String.valueOf(account.getDelegatedFrozenBalanceForBandwidth()));

      // todo chuanqiang 处理trc10
      return info;
    }

    public static AccountInfo of(AccountCapsule oldAccount, AccountCapsule account) {
      AccountInfo info = new AccountInfo();

      info.setAccountAddress(account.getAddress().toString());
      info.setAdd(false);

      info.setBalance(String.valueOf(account.getBalance()));
      info.setFrozenBalance(String.valueOf(account.getFrozenBalance()));
      info.setEnergyFrozenBalance(String.valueOf(account.getEnergyFrozenBalance()));
      info.setDelegatedFrozenBalanceForEnergy(String.valueOf(account.getDelegatedFrozenBalanceForEnergy()));
      info.setDelegatedFrozenBalanceForBandwidth(String.valueOf(account.getDelegatedFrozenBalanceForBandwidth()));

      info.setIncrementBalance(String.valueOf(account.getBalance()));
      info.setIncrementFrozenBalance(String.valueOf(account.getFrozenBalance()));
      info.setIncrementEnergyFrozenBalance(String.valueOf(account.getEnergyFrozenBalance()));
      info.setIncrementDelegatedFrozenBalanceForEnergy(String.valueOf(account.getDelegatedFrozenBalanceForEnergy()));
      info.setIncrementDelegatedFrozenBalanceForBandwidth(String.valueOf(account.getDelegatedFrozenBalanceForBandwidth()));

      // todo chuanqiang 处理trc10
      return info;
    }
  }

  @Override
  public void close() {
    super.close();
  }
}
