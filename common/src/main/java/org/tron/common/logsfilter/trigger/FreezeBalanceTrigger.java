package org.tron.common.logsfilter.trigger;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigInteger;
import java.util.LinkedList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = false)
public class FreezeBalanceTrigger extends Trigger {

  @Data
  public static class FreezeBalance {

    public String fromAddress;

    public String toAddress;

    public String freezeBalance;

    public String expireTime;

    public String incrementFreezeBalance;

    public String incrementExpireTime;

    public Integer resource;  // 1=能量， 2=带宽
  }

  @Data
  public static class AssetTransfer {

    public String fromAddress;

    public String toAddress;

    public String trId;

    public String tokenAddress;

    public BigInteger amount;

    public Integer assetType; // 0=trx, 1=trc10, 2=trc20, 3=trc721
  }

  private Long blockNumber;

  private String parentHash;

  private String blockHash;

  private List<FreezeBalance> freezeList = new LinkedList<>();

  public FreezeBalanceTrigger() {
    super();
    setTriggerName(Trigger.FREEZE_BALANCE_TRIGGER_NAME);
  }

}


