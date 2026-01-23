package org.tron.common.logsfilter.capsule;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;
import org.tron.common.entity.OwnerAuthInfo;
import org.tron.common.logsfilter.EventPluginLoader;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.db.accountchange.MultiAuthRecord;

import java.util.Map;


@Slf4j
public class MultiAuthTrackerCapsule extends TriggerCapsule {

    @Getter
    @Setter
    private MultiAuthTrackerTrigger multiAuthTrackerTrigger;

    @Autowired
    MultiAuthRecord multiAuthRecord;

    public MultiAuthTrackerCapsule(BlockCapsule block, Map<String, OwnerAuthInfo> ownerAuthMap) {
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
