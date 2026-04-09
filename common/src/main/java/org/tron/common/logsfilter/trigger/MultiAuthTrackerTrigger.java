package org.tron.common.logsfilter.trigger;

import java.util.ArrayList;
import java.util.List;
import javax.swing.Icon;import lombok.Data;
import lombok.EqualsAndHashCode;
import org.tron.common.entity.OwnerAuthInfo;

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

  public MultiAuthTrackerTrigger() {
    super();
    setTriggerName(Trigger.MULTIAUTH_TRIGGER_NAME);
  }
}
