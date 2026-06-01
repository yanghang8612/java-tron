package org.tron.core.store;

import com.google.common.primitives.Bytes;
import com.google.protobuf.ByteString;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.FastByteComparisons;
import org.tron.core.capsule.BytesCapsule;
import org.tron.core.db.TronStoreWithRevoking;

@Slf4j(topic = "DB")
@Component
public class StakerIndexStore extends TronStoreWithRevoking<BytesCapsule> {

  private static final byte[] STAKER_PREFIX = "S_".getBytes();
  private static final byte[] RANK_PREFIX = "R_".getBytes();
  private static final byte[] READY_KEY = "META_READY".getBytes();
  private static final byte[] ONE = new byte[] {1};
  private static final int RANK_STAKE_OFFSET = RANK_PREFIX.length;
  private static final int RANK_ADDRESS_OFFSET = RANK_STAKE_OFFSET + Long.BYTES;

  @Autowired
  private StakerIndexStore(@Value("staker-index") String dbName) {
    super(dbName);
  }

  @Override
  public BytesCapsule get(byte[] key) {
    return getUnchecked(key);
  }

  public boolean isReady() {
    byte[] data = getData(READY_KEY);
    return data != null && data.length > 0 && data[0] == 1;
  }

  public void markReady() {
    put(READY_KEY, new BytesCapsule(ONE));
  }

  public void clearIndex() {
    deleteByPrefix(STAKER_PREFIX);
    deleteByPrefix(RANK_PREFIX);
    delete(READY_KEY);
  }

  public List<Map.Entry<ByteString, Long>> loadStakers() {
    List<Map.Entry<ByteString, Long>> result = new ArrayList<>();
    prefixQuery(STAKER_PREFIX).forEach((k, v) -> {
      byte[] key = k.getBytes();
      byte[] address = Arrays.copyOfRange(key, STAKER_PREFIX.length, key.length);
      result.add(new AbstractMap.SimpleImmutableEntry<>(
          ByteString.copyFrom(address), ByteArray.toLong(v.getData())));
    });
    return result;
  }

  public List<Map.Entry<ByteString, Long>> getTopStakers(int limit) {
    if (limit <= 0) {
      return new ArrayList<>();
    }
    List<Map.Entry<byte[], byte[]>> rows = new ArrayList<>(
        revokingDB.getNext(RANK_PREFIX, limit).entrySet());
    rows.sort((e1, e2) -> compareKeys(e1.getKey(), e2.getKey()));

    List<Map.Entry<ByteString, Long>> result = new ArrayList<>(Math.min(limit, rows.size()));
    for (Map.Entry<byte[], byte[]> row : rows) {
      byte[] key = row.getKey();
      if (!startsWith(key, RANK_PREFIX)) {
        break;
      }
      if (key.length <= RANK_ADDRESS_OFFSET) {
        continue;
      }
      byte[] value = row.getValue();
      if (value == null) {
        continue;
      }
      byte[] address = Arrays.copyOfRange(key, RANK_ADDRESS_OFFSET, key.length);
      result.add(new AbstractMap.SimpleImmutableEntry<>(
          ByteString.copyFrom(address), ByteArray.toLong(value)));
      if (result.size() >= limit) {
        break;
      }
    }
    return result;
  }

  public long getStake(ByteString address) {
    return getStake(address.toByteArray());
  }

  public long getStake(byte[] address) {
    return ByteArray.toLong(getData(stakerKey(address)));
  }

  public void updateStaker(ByteString address, long oldStake, long newStake) {
    byte[] addressBytes = address.toByteArray();
    if (oldStake > 0) {
      delete(stakerKey(addressBytes));
      delete(rankKey(oldStake, addressBytes));
    }
    if (newStake > 0) {
      put(stakerKey(addressBytes), new BytesCapsule(ByteArray.fromLong(newStake)));
      put(rankKey(newStake, addressBytes), new BytesCapsule(ByteArray.fromLong(newStake)));
    }
  }

  public void removeStaker(ByteString address, long oldStake) {
    updateStaker(address, oldStake, 0);
  }

  private void deleteByPrefix(byte[] prefix) {
    prefixQuery(prefix).forEach((k, v) -> delete(k.getBytes()));
  }

  private byte[] stakerKey(byte[] address) {
    return Bytes.concat(STAKER_PREFIX, address);
  }

  private byte[] rankKey(long stake, byte[] address) {
    return Bytes.concat(RANK_PREFIX, ByteArray.fromLong(Long.MAX_VALUE - stake), address);
  }

  private byte[] getData(byte[] key) {
    BytesCapsule capsule = getUnchecked(key);
    return capsule == null ? null : capsule.getData();
  }

  private int compareKeys(byte[] left, byte[] right) {
    return FastByteComparisons.compareTo(left, 0, left.length, right, 0, right.length);
  }

  private boolean startsWith(byte[] key, byte[] prefix) {
    if (key.length < prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if (key[i] != prefix[i]) {
        return false;
      }
    }
    return true;
  }
}
