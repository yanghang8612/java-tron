package org.tron.common.logsfilter.trigger;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.LinkedList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = false)
public class FreezeBalanceTrigger extends Trigger {

  @Data
  public static class FreezeBalance {

    public String fromAddress;

    public String toAddress;

    public Long freezeBalance;

    public Integer resource;  // 1=能量， 2=带宽
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


