package org.tron.common.logsfilter.trigger;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = false)
public class StakeBalanceTrigger extends Trigger {



  private Long blockNumber;

  private String parentHash;

  private String blockHash;

  private List<StakeInfo> stakeList = new ArrayList<>();

  public StakeBalanceTrigger() {
    super();
    setTriggerName(Trigger.STAKE_BALANCE_TRIGGER_NAME);
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

}


