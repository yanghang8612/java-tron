package org.tron.core.store;

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

}