package org.tron.common.logsfilter.capsule;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.tron.common.entity.OwnerAuthInfo;
import org.tron.common.logsfilter.trigger.Trigger;

/**
 * === TronLink Feature ===
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class MultiAuthTrackerTrigger extends Trigger {

  private long blockNumber;

  private String parentHash;

  private String blockHash;

  List<OwnerAuthInfo> authInfoList = new ArrayList<>();

  MultiAuthTrackerTrigger() {
    super();
    setTriggerName(Trigger.MULTIAUTH_TRIGGER_NAME);
  }
}
