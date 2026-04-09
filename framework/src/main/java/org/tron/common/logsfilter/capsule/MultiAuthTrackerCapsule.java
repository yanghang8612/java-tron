package org.tron.common.logsfilter.capsule;

import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;
import org.tron.common.entity.OwnerAuthInfo;
import org.tron.common.logsfilter.EventPluginLoader;
import org.tron.common.logsfilter.trigger.MultiAuthTrackerTrigger;
import org.tron.core.capsule.BlockCapsule;

/**
 * === TronLink Feature ===
 */
@Slf4j
public class MultiAuthTrackerCapsule extends TriggerCapsule {

    @Getter
    @Setter
    private MultiAuthTrackerTrigger multiAuthTrackerTrigger;

    public MultiAuthTrackerCapsule(BlockCapsule block, Map<String, OwnerAuthInfo> ownerAuthMap) {
//      logger.info("MultiAuthTrackerCapsule start, blockNum={}", block.getNum());
      multiAuthTrackerTrigger = new MultiAuthTrackerTrigger();
      multiAuthTrackerTrigger.setBlockHash(block.getBlockId().toString());
      multiAuthTrackerTrigger.setParentHash(block.getParentHash().toString());
      multiAuthTrackerTrigger.setBlockNumber(block.getNum());
      multiAuthTrackerTrigger.setTimeStamp(block.getTimeStamp());

      if (CollectionUtils.isEmpty(ownerAuthMap)) {
        return;
      }

      multiAuthTrackerTrigger.getAuthInfoList().addAll(ownerAuthMap.values());
    }

    @Override
    public void processTrigger() {
      EventPluginLoader.getInstance().postMultiAuthTrigger(multiAuthTrackerTrigger);
    }

  }
