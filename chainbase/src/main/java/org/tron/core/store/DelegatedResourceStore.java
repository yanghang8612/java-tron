package org.tron.core.store;

import com.google.protobuf.ByteString;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.tron.core.capsule.DelegatedResourceCapsule;
import org.tron.core.db.TronStoreWithRevoking;
import org.tron.core.db.accountchange.FreezeChangeRecord;
import org.tron.core.db.accountchange.StakeChangeRecord;
import org.tron.protos.contract.Common;

@Component
public class DelegatedResourceStore extends TronStoreWithRevoking<DelegatedResourceCapsule> {


  @Autowired
  private FreezeChangeRecord freezeChangeRecord;

  @Autowired
  public DelegatedResourceStore(@Value("DelegatedResource") String dbName) {
    super(dbName);
  }

  @Override
  public DelegatedResourceCapsule get(byte[] key) {

    byte[] value = revokingDB.getUnchecked(key);
    return ArrayUtils.isEmpty(value) ? null : new DelegatedResourceCapsule(value);
  }


  @Override
  public void put(byte[] key, DelegatedResourceCapsule item) {
    if (Objects.isNull(key) || Objects.isNull(item)) {
      return;
    }

    final DelegatedResourceCapsule oldResource = get(key);
    revokingDB.put(key, item.getData());

    freezeChangeRecord.recordChangedFreeze(key, oldResource, item);
  }

  @Override
  public void delete(byte[] key) {
    final DelegatedResourceCapsule oldResource = get(key);
    revokingDB.delete(key);
    freezeChangeRecord.recordChangedFreeze(key, oldResource, null);
  }

  @Deprecated
  public List<DelegatedResourceCapsule> getByFrom(byte[] key) {
    return revokingDB.getValuesNext(key, Long.MAX_VALUE).stream()
        .map(DelegatedResourceCapsule::new)
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
  }

  public void unLockExpireResource(byte[] from, byte[] to, long now) {
    byte[] lockKey = DelegatedResourceCapsule
        .createDbKeyV2(from, to, true);
    DelegatedResourceCapsule lockResource = get(lockKey);
    if (lockResource == null) {
      return;
    }
    if (lockResource.getExpireTimeForEnergy() >= now
        && lockResource.getExpireTimeForBandwidth() >= now) {
      return;
    }

    byte[] unlockKey = DelegatedResourceCapsule
        .createDbKeyV2(from, to, false);
    DelegatedResourceCapsule unlockResource = get(unlockKey);
    if (unlockResource == null) {
      unlockResource = new DelegatedResourceCapsule(ByteString.copyFrom(from),
          ByteString.copyFrom(to));
    }
    if (lockResource.getExpireTimeForEnergy() < now) {
      final long unLockFrozenBalanceForEnergy = unlockResource.getFrozenBalanceForEnergy();
      final long unlockExpireTimeForEnergy = unlockResource.getExpireTimeForEnergy();
      final long lockFrozenBalanceForEnergy = lockResource.getFrozenBalanceForEnergy();
      final long lockExpireTimeForEnergy = lockResource.getExpireTimeForEnergy();

      unlockResource.addFrozenBalanceForEnergy(
          lockResource.getFrozenBalanceForEnergy(), 0);
      lockResource.setFrozenBalanceForEnergy(0, 0);

      StakeChangeRecord.recordResource(from, to, Common.ResourceCode.ENERGY,
              unLockFrozenBalanceForEnergy + lockFrozenBalanceForEnergy, 0L,
              unLockFrozenBalanceForEnergy, unlockExpireTimeForEnergy, false);

      StakeChangeRecord.recordResource(from, to, Common.ResourceCode.ENERGY,
              0L, 0L,
              lockFrozenBalanceForEnergy, lockExpireTimeForEnergy, true);
    }
    if (lockResource.getExpireTimeForBandwidth() < now) {
      final long unLockFrozenBalanceForBandwidth = unlockResource.getFrozenBalanceForBandwidth();
      final long unlockExpireTimeForBandwidth = unlockResource.getExpireTimeForBandwidth();
      final long lockFrozenBalanceForBandwidth = lockResource.getFrozenBalanceForBandwidth();
      final long lockExpireTimeForBandwidth = lockResource.getExpireTimeForBandwidth();
      unlockResource.addFrozenBalanceForBandwidth(
          lockResource.getFrozenBalanceForBandwidth(), 0);
      lockResource.setFrozenBalanceForBandwidth(0, 0);

      StakeChangeRecord.recordResource(from, to, Common.ResourceCode.BANDWIDTH,
              unLockFrozenBalanceForBandwidth + lockFrozenBalanceForBandwidth, 0L,
              unLockFrozenBalanceForBandwidth, unlockExpireTimeForBandwidth, false);

      StakeChangeRecord.recordResource(from, to, Common.ResourceCode.BANDWIDTH,
              0L, 0L,
              lockFrozenBalanceForBandwidth, lockExpireTimeForBandwidth, true);
    }
    if (lockResource.getFrozenBalanceForBandwidth() == 0
        && lockResource.getFrozenBalanceForEnergy() == 0) {
      delete(lockKey);
    } else {
      put(lockKey, lockResource);
    }
    put(unlockKey, unlockResource);
  }

}