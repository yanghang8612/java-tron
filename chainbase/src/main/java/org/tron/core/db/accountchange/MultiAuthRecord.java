package org.tron.core.db.accountchange;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.tron.common.entity.AuthInfo;
import org.tron.common.utils.StringUtil;
import org.tron.core.capsule.AccountCapsule;

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

  Map<String, List<AuthInfo>> accountAuthsMap = new HashMap<>();

  public void startRecord(boolean record) {
    this.recordMultiAuth = record;
  }

  public void clear() {
    this.recordMultiAuth = false;
    accountAuthsMap.clear();
  }

  public Map<String, List<AuthInfo>> getAccountAuthsMap() {
    return accountAuthsMap;
  }

  public void recordMultiAuth(AccountCapsule accountCapsule) {
    String accountAddress = StringUtil.encode58Check(accountCapsule.getAddress().toByteArray());
    List<AuthInfo> oldAuthInfoList = AuthInfo.getAuthList(accountAddress, accountCapsule.getInstance().getOwnerPermission(),
        accountCapsule.getInstance().getWitnessPermission(), accountCapsule.getInstance().getActivePermissionList());
    if (CollectionUtils.isEmpty(oldAuthInfoList)) {
      accountAuthsMap.remove(accountAddress);
      return;
    }
    accountAuthsMap.put(accountAddress, oldAuthInfoList);
  }
}
