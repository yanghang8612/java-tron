package org.tron.core.db.accountchange;


import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.tron.common.logsfilter.trigger.StakeBalanceTrigger;
import org.tron.common.utils.StringUtil;
import org.tron.protos.Protocol;
import org.tron.protos.contract.Common;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class StakeChangeRecord {

  static List<StakeBalanceTrigger.StakeInfo> result = new ArrayList<>();

  static volatile boolean record = false;

  public void startRecord() {
    record = true;
    result.clear();
  }

  public void clear() {
    result.clear();
  }

  public List<StakeBalanceTrigger.StakeInfo> getStakeInfos() {
    return result;
  }

  public static void recordUnfreeze(byte[] ownerAddress, Common.ResourceCode resourceCode,
                                    Long unBalance, Long expireTime) {
    if (!record) {
      return;
    }

    StakeBalanceTrigger.StakeInfo stakeInfo = new StakeBalanceTrigger.StakeInfo();
    stakeInfo.setOwnerAddress(StringUtil.encode58Check(ownerAddress));
    stakeInfo.setReceiverAddress("");
    stakeInfo.setStakeType(3);
    stakeInfo.setResource(resourceCode.getNumber());
    stakeInfo.setBalance(unBalance);
    stakeInfo.setOldBalance(0);
    stakeInfo.setExpireTime(expireTime);
    stakeInfo.setOldExpireTime(0L);

    result.add(stakeInfo);
  }


  public static void withdrawUnfreeze(byte[] ownerAddress,
                                      List<Protocol.Account.UnFreezeV2> totalWithdrawList) {
    if (!record || CollectionUtils.isEmpty(totalWithdrawList)) {
      return;
    }

    final String address58 = StringUtil.encode58Check(ownerAddress);

    totalWithdrawList.stream().forEach(item -> {
      StakeBalanceTrigger.StakeInfo stakeInfo = new StakeBalanceTrigger.StakeInfo();
      stakeInfo.setOwnerAddress(address58);
      stakeInfo.setReceiverAddress("");
      stakeInfo.setStakeType(4);
      stakeInfo.setResource(item.getType().getNumber());
      stakeInfo.setBalance(item.getUnfreezeAmount());
      stakeInfo.setOldBalance(0L);
      stakeInfo.setExpireTime(item.getUnfreezeExpireTime());
      stakeInfo.setOldExpireTime(0L);

      result.add(stakeInfo);
    });
  }

  public static void recordResource(byte[] ownerAddress, byte[] receiverAddress,
                                    Common.ResourceCode resourceCode,
                                    Long newBalance, Long expireTime,
                                    Long oldBalance, Long oldExpireTime, boolean lock) {
    if (!record) {
      return;
    }

    StakeBalanceTrigger.StakeInfo stakeInfo = new StakeBalanceTrigger.StakeInfo();
    stakeInfo.setOwnerAddress(StringUtil.encode58Check(ownerAddress));
    stakeInfo.setReceiverAddress(StringUtil.encode58Check(receiverAddress));
    stakeInfo.setStakeType(1);
    stakeInfo.setResource(resourceCode.getNumber());
    stakeInfo.setBalance(newBalance);
    stakeInfo.setOldBalance(oldBalance);
    stakeInfo.setExpireTime(expireTime);
    stakeInfo.setOldExpireTime(oldExpireTime);

    stakeInfo.setLock(lock);

    result.add(stakeInfo);
  }
}
