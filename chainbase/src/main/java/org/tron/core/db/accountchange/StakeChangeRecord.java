package org.tron.core.db.accountchange;


import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.tron.common.utils.StringUtil;
import org.tron.protos.Protocol;
import org.tron.protos.contract.Common;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class StakeChangeRecord {

  static List<StakeInfo> result = new ArrayList<>();

  static volatile boolean record = false;

  public static void startRecord() {
    record = true;
    result.clear();
  }

  @Data
  public static class StakeInfo {
    String ownerAddress; //账户地址
    String receiverAddress; //接收账户地址， 只有stakeType=1有值

    Integer stakeType; //1=DelegateResource,UnDelegateResource, 3=UnfreezeBalanceV2, 4=WithdrawExpireUnfreeze（这个时候需要记录所有的解质押记录）
    Integer resource; //资源类型。0=带宽，1=能量，2=tron_power(stakeType=1没有这类型)

    long balance; //代理余额或者解质押余额
    long oldBalance; //只有stakeType=1有值, stakeType3,4的值 跟balance相同

    Long expireTime; //DelegateResource, UnDelegateResource 是分别有 过期和未过期 两种情况
    Long oldExpireTime; //只有stakeType=1，有值

    boolean lock = false;
  }

  public static void recordUnfreeze(byte[] ownerAddress, Common.ResourceCode resourceCode,
                                    Long unBalance, Long expireTime) {
    if (!record) {
      return;
    }

    StakeInfo stakeInfo = new StakeInfo();
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

    totalWithdrawList.stream().forEach(item -> {
      StakeInfo stakeInfo = new StakeInfo();
      stakeInfo.setOwnerAddress(StringUtil.encode58Check(ownerAddress));
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

    StakeInfo stakeInfo = new StakeInfo();
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
