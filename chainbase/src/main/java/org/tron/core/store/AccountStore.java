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
import org.springframework.util.CollectionUtils;
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
    try {
      if (oldAccount == null) {
        accountInfo = AccountInfo.of(newAccount);
      }
      else {
        accountInfo = AccountInfo.of(oldAccount, newAccount);
      }
    }
    catch (Exception ex) {
      logger.error("", ex);
      return;
    }

    // 如果是null, 表示balance没有变动，则直接返回
    if (accountInfo == null) {
      return;
    }

    final AccountInfo inMapInfo = tempAccountMap.get(key);

    if (inMapInfo == null) {
      tempAccountMap.put(key, accountInfo);
      return;
    }

    try {
      mergeInfo(inMapInfo, accountInfo);
    }
    catch (Exception ex) {
      logger.error("", ex);
    }
  }

  private void mergeInfo(AccountInfo inMapInfo, AccountInfo addInfo) {
    //  merge操作：余额 更新， diff余额 相加
    AccountInfo.setBalance(inMapInfo, addInfo);

    inMapInfo.setIncrementBalance(inMapInfo.getIncrementBalance() + addInfo.getIncrementBalance());
    inMapInfo.setIncrementFrozenBalance(inMapInfo.getIncrementFrozenBalance() + addInfo.getIncrementFrozenBalance());
    inMapInfo.setIncrementEnergyFrozenBalance(inMapInfo.getIncrementEnergyFrozenBalance() + addInfo.getIncrementEnergyFrozenBalance());
    inMapInfo.setIncrementDelegatedFrozenBalanceForEnergy(inMapInfo.getIncrementDelegatedFrozenBalanceForEnergy() + addInfo.getIncrementDelegatedFrozenBalanceForEnergy());
    inMapInfo.setIncrementDelegatedFrozenBalanceForBandwidth(inMapInfo.getIncrementDelegatedFrozenBalanceForBandwidth() + addInfo.getIncrementDelegatedFrozenBalanceForBandwidth());

    final Map<String, Trc10Info> trc10Map = inMapInfo.getTrc10Map();
    final Map<String, Trc10Info> addMap = addInfo.getTrc10Map();

    trc10Map.forEach((tokenId, info) -> {
      final Trc10Info addTrc10Info = addMap.get(tokenId);
      if (addTrc10Info == null) {
        info.setIncrementBalance(-info.getBalance());
        info.setBalance(0);
        return;
      }

      info.setBalance(addInfo.getBalance());
      info.setIncrementBalance(info.getIncrementBalance() + addTrc10Info.getIncrementBalance());
      addMap.remove(tokenId);
    });

    trc10Map.putAll(addMap);
  }


  @Data
  public static class AccountInfo {
    private String accountAddress;
    private Boolean add = false;

    private long balance;
    private long frozenBalance;
    private long energyFrozenBalance;
    private long delegatedFrozenBalanceForEnergy;
    private long delegatedFrozenBalanceForBandwidth;

    private long incrementBalance;
    private long incrementFrozenBalance;
    private long incrementEnergyFrozenBalance;
    private long incrementDelegatedFrozenBalanceForEnergy;
    private long incrementDelegatedFrozenBalanceForBandwidth;

    private Map<String, Trc10Info> trc10Map;


    public static AccountInfo of(AccountCapsule account) {
      AccountInfo info = new AccountInfo();

      // todo chuanqiang 确定address
      info.setAccountAddress(account.getAddress().toString());
      info.setAdd(true);

      setBalance(info, account);

      info.setIncrementBalance(account.getBalance());
      info.setIncrementFrozenBalance(account.getFrozenBalance());
      info.setIncrementEnergyFrozenBalance(account.getEnergyFrozenBalance());
      info.setIncrementDelegatedFrozenBalanceForEnergy(account.getDelegatedFrozenBalanceForEnergy());
      info.setIncrementDelegatedFrozenBalanceForBandwidth(account.getDelegatedFrozenBalanceForBandwidth());

      info.setTrc10Map(Trc10Info.of(account.getAssetMapV2(), true));
      return info;
    }

    // 检查余额是否有变动，没有变动 return null.
    public static AccountInfo of(AccountCapsule oldAccount, AccountCapsule newAccount) {
      AccountInfo info = new AccountInfo();

      info.setAccountAddress(newAccount.getAddress().toString());
      info.setAdd(false);

      setBalance(info, newAccount);

      info.setIncrementBalance(newAccount.getBalance() - oldAccount.getBalance());
      info.setIncrementFrozenBalance(newAccount.getFrozenBalance() - oldAccount.getFrozenBalance());
      info.setIncrementEnergyFrozenBalance(newAccount.getEnergyFrozenBalance() - oldAccount.getEnergyFrozenBalance());
      info.setIncrementDelegatedFrozenBalanceForEnergy(newAccount.getDelegatedFrozenBalanceForEnergy() - oldAccount.getDelegatedFrozenBalanceForEnergy());
      info.setIncrementDelegatedFrozenBalanceForBandwidth(newAccount.getDelegatedFrozenBalanceForBandwidth() - oldAccount.getDelegatedFrozenBalanceForBandwidth());

      info.setTrc10Map(Trc10Info.of(oldAccount.getAssetMapV2(), newAccount.getAssetMapV2()));

      // 检查余额是否有变动，没有变动 return null.
      if (info.getIncrementBalance() == 0
              && info.getIncrementFrozenBalance() == 0
              && info.getIncrementEnergyFrozenBalance() == 0
              && info.getIncrementDelegatedFrozenBalanceForEnergy() == 0
              && info.getIncrementDelegatedFrozenBalanceForBandwidth() == 0
              && info.getTrc10Map() == null) {
        return null;
      }

      return info;
    }

    public static void setBalance(AccountInfo info, AccountCapsule account) {
      info.setBalance(account.getBalance());
      info.setFrozenBalance(account.getFrozenBalance());
      info.setEnergyFrozenBalance(account.getEnergyFrozenBalance());
      info.setDelegatedFrozenBalanceForEnergy(account.getDelegatedFrozenBalanceForEnergy());
      info.setDelegatedFrozenBalanceForBandwidth(account.getDelegatedFrozenBalanceForBandwidth());
    }

    public static void setBalance(AccountInfo info, AccountInfo account) {
      info.setBalance(account.getBalance());
      info.setFrozenBalance(account.getFrozenBalance());
      info.setEnergyFrozenBalance(account.getEnergyFrozenBalance());
      info.setDelegatedFrozenBalanceForEnergy(account.getDelegatedFrozenBalanceForEnergy());
      info.setDelegatedFrozenBalanceForBandwidth(account.getDelegatedFrozenBalanceForBandwidth());
    }
  }


  @Data
  public static class Trc10Info {
    private String tokenId;
    private long balance;
    private long incrementBalance;

    public static Map<String, Trc10Info> of(Map<String, Long> assetMapV2, boolean type) {
      Map<String, Trc10Info> trc10Map = new HashMap<>();
      if (CollectionUtils.isEmpty(assetMapV2)) {
        return trc10Map;
      }

      assetMapV2.forEach((key, val) -> {
        Trc10Info trc10Info = null;
        if (type) {
          trc10Info = of(key, val);
        }
        else {
          trc10Info = of(key, val, 0);
        }

        if (trc10Info != null) {
          trc10Map.put(key, trc10Info);
        }
      });

      return trc10Map;
    }

    // if not change return null;
    public static Map<String, Trc10Info> of(Map<String, Long> oldAssetMapV2, Map<String, Long> newAssetMapV2) {
      final boolean oldEmpty = CollectionUtils.isEmpty(oldAssetMapV2);
      final boolean newEmpty = CollectionUtils.isEmpty(newAssetMapV2);

      // trc10 没有修改的， return null
      if (oldEmpty && newEmpty) {
        return null;
      }

      if (oldEmpty) {
        return of(newAssetMapV2, true);
      }

      if (newEmpty) {
        return of(oldAssetMapV2, false);
      }

      Map<String, Trc10Info> trc10Map = new HashMap<>();
      newAssetMapV2.forEach((key, val) -> {
        Long oldVal = oldAssetMapV2.get(key);
        oldVal = oldVal == null ? 0L : oldVal;
        final Trc10Info trc10Info = of(key, oldVal, val);

        if (trc10Info != null) {
          trc10Map.put(key, trc10Info);
        }

        oldAssetMapV2.remove(key);
      });

      oldAssetMapV2.forEach((key, oldVal) -> {
        final Trc10Info trc10Info = of(key, oldVal, 0);

        if (trc10Info != null) {
          trc10Map.put(key, trc10Info);
        }
      });

      // trc10 没有修改的， return null
      if (CollectionUtils.isEmpty(trc10Map)) {
        return null;
      }

      return trc10Map;
    }

    private static Trc10Info of(String tokenId, long val) {
      Trc10Info info = new Trc10Info();
      info.setTokenId(tokenId);
      info.setBalance(val);
      info.setIncrementBalance(val);
      return info;
    }

    private static Trc10Info of(String tokenId, long oldVal, long val) {
      Trc10Info info = new Trc10Info();
      info.setTokenId(tokenId);
      info.setBalance(val);
      info.setIncrementBalance(val - oldVal);

      // not change
      if (info.getIncrementBalance() == 0) {
        return null;
      }
      return info;
    }
  }

  @Override
  public void close() {
    super.close();
  }
}
