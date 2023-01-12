package org.tron.common.logsfilter.capsule;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;
import org.tron.common.logsfilter.EventPluginLoader;
import org.tron.common.logsfilter.trigger.StakeBalanceTrigger;
import org.tron.core.capsule.BlockCapsule;

import java.util.*;

@Slf4j
public class StakeTrackerCapsule extends TriggerCapsule {

  @Getter
  @Setter
  private StakeBalanceTrigger stakeBalanceTrigger;

  public StakeTrackerCapsule(BlockCapsule block, List<StakeBalanceTrigger.StakeInfo> infos) {
    stakeBalanceTrigger = new StakeBalanceTrigger();
    stakeBalanceTrigger.setBlockHash(block.getBlockId().toString());
    stakeBalanceTrigger.setParentHash(block.getParentHash().toString());
    stakeBalanceTrigger.setBlockNumber(block.getNum());
    stakeBalanceTrigger.setTimeStamp(block.getTimeStamp());

    if (!CollectionUtils.isEmpty(infos)) {
      stakeBalanceTrigger.setStakeList(infos);
    }
  }

  @Override
  public void processTrigger() {
    EventPluginLoader.getInstance().postStakeBalanceTrigger(stakeBalanceTrigger);
  }


}
