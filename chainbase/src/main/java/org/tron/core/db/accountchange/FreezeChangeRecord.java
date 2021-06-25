package org.tron.core.db.accountchange;

import com.google.common.collect.Maps;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.spongycastle.util.encoders.Hex;
import org.springframework.stereotype.Service;
import org.tron.common.utils.StringUtil;
import org.tron.core.capsule.DelegatedResourceCapsule;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class FreezeChangeRecord {

  private static volatile boolean recordFreezeBalance = false;

  // not byte[]! because the same byte[] but not hashCode.
  private static Map<String, FreezeInfo> tempAccountMap = new HashMap<>();

  public void startRecord(boolean record) {
    logger.info(" >>>> startRecord:{}", record);
    this.recordFreezeBalance = record;
  }

  public void clear() {
    this.recordFreezeBalance = false;
    tempAccountMap.clear();
  }

  public Map<String, FreezeInfo> getTempFreezeMap() {
    return Maps.newHashMap(tempAccountMap);
  }

  public void recordChangedFreeze(byte[] key, DelegatedResourceCapsule oldResource, DelegatedResourceCapsule newResource) {
    if (!recordFreezeBalance || newResource == null) {
      return;
    }

    FreezeInfo freezeInfo = null;
    try {
      if (oldResource == null) {
        freezeInfo = FreezeInfo.of(newResource);
      }
      else {
        freezeInfo = FreezeInfo.of(oldResource, newResource);

        if (freezeInfo == null) {
          return;
        }
      }
    }
    catch (Exception ex) {
      logger.error("", ex);
      return;
    }

    // if null, means balance not change, return.
    if (freezeInfo == null) {
      return;
    }

    merge(key, freezeInfo);
  }


  private void merge(byte[] key, FreezeInfo freezeInfo) {
    final String keyString = Hex.toHexString(key);
    final FreezeInfo inMapInfo = tempAccountMap.get(keyString);

    if (inMapInfo == null) {
      tempAccountMap.put(keyString, freezeInfo);
      return;
    }

    try {
      mergeInfo(inMapInfo, freezeInfo);
    }
    catch (Exception ex) {
      logger.error("", ex);
    }
  }

  private void mergeInfo(FreezeInfo inMapInfo, FreezeInfo addInfo) {
    //  merge：update balance， add incrementBalance.
    FreezeInfo.setBalance(inMapInfo, addInfo);

    inMapInfo.setIncrementFreezeEnergyBalance(inMapInfo.getIncrementFreezeEnergyBalance() + addInfo.getIncrementFreezeEnergyBalance());
    inMapInfo.setIncrementExpireTimeForEnergy(inMapInfo.getIncrementExpireTimeForEnergy() + addInfo.getIncrementExpireTimeForEnergy());
    inMapInfo.setIncrementFreezeBandwidthBalance(inMapInfo.getIncrementFreezeBandwidthBalance() + addInfo.getIncrementFreezeBandwidthBalance());
    inMapInfo.setIncrementExpireTimeForBandwidth(inMapInfo.getIncrementExpireTimeForBandwidth() + addInfo.getIncrementExpireTimeForBandwidth());
  }


  @Data
  public static class FreezeInfo {
    public String fromAddress;
    public String toAddress;

    public Long freezeEnergyBalance;
    public Long expireTimeForEnergy;
    public Long freezeBandwidthBalance;
    public Long expireTimeForBandwidth;

    public Long incrementFreezeEnergyBalance;
    public Long incrementExpireTimeForEnergy;
    public Long incrementFreezeBandwidthBalance;
    public Long incrementExpireTimeForBandwidth;

    public Integer resource;  // 1=能量， 2=带宽

    public static FreezeInfo of(DelegatedResourceCapsule resourceCapsule) {
      FreezeInfo info = new FreezeInfo();
      info.setFromAddress(StringUtil.encode58Check(resourceCapsule.getFrom().toByteArray()));
      info.setToAddress(StringUtil.encode58Check(resourceCapsule.getTo().toByteArray()));

      setBalance(info, resourceCapsule);

      info.setIncrementFreezeEnergyBalance(resourceCapsule.getFrozenBalanceForEnergy());
      info.setIncrementExpireTimeForEnergy(resourceCapsule.getExpireTimeForEnergy());
      info.setIncrementFreezeBandwidthBalance(resourceCapsule.getFrozenBalanceForBandwidth());
      info.setIncrementExpireTimeForBandwidth(resourceCapsule.getExpireTimeForBandwidth());
      return info;
    }

    // 检查余额是否有变动，没有变动 return null.
    public static FreezeInfo of(DelegatedResourceCapsule oldResource, DelegatedResourceCapsule newResource) {
      FreezeInfo info = new FreezeInfo();
      info.setFromAddress(StringUtil.encode58Check(newResource.getFrom().toByteArray()));
      info.setToAddress(StringUtil.encode58Check(newResource.getTo().toByteArray()));

      setBalance(info, newResource);

      info.setIncrementFreezeEnergyBalance(newResource.getFrozenBalanceForEnergy() - oldResource.getFrozenBalanceForEnergy());
      info.setIncrementExpireTimeForEnergy(newResource.getExpireTimeForEnergy() - oldResource.getExpireTimeForEnergy());
      info.setIncrementFreezeBandwidthBalance(newResource.getFrozenBalanceForBandwidth() - oldResource.getFrozenBalanceForBandwidth());
      info.setIncrementExpireTimeForBandwidth(newResource.getExpireTimeForBandwidth() - oldResource.getExpireTimeForBandwidth());

      // 检查余额是否有变动，没有变动 return null.
      if (info.getIncrementFreezeEnergyBalance() == 0
              && info.getIncrementFreezeBandwidthBalance() == 0
              && info.getIncrementExpireTimeForEnergy() == 0
              && info.getIncrementExpireTimeForBandwidth() == 0) {
        return null;
      }

      return info;
    }

    public static void setBalance(FreezeInfo info, DelegatedResourceCapsule resourceCapsule) {
      info.setFreezeEnergyBalance(resourceCapsule.getFrozenBalanceForEnergy());
      info.setExpireTimeForEnergy(resourceCapsule.getExpireTimeForEnergy());
      info.setFreezeBandwidthBalance(resourceCapsule.getFrozenBalanceForBandwidth());
      info.setExpireTimeForBandwidth(resourceCapsule.getExpireTimeForBandwidth());
    }

    public static void setBalance(FreezeInfo info, FreezeInfo resourceCapsule) {
      info.setFreezeEnergyBalance(resourceCapsule.getFreezeEnergyBalance());
      info.setExpireTimeForEnergy(resourceCapsule.getExpireTimeForEnergy());
      info.setFreezeBandwidthBalance(resourceCapsule.getFreezeBandwidthBalance());
      info.setExpireTimeForBandwidth(resourceCapsule.getExpireTimeForBandwidth());
    }
  }

}
