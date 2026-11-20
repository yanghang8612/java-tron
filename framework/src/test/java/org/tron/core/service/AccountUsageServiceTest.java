package org.tron.core.service;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.tron.common.BaseMethodTest;
import org.tron.common.crypto.ECKey;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.db.common.iterator.RockStoreIterator;
import org.tron.core.db.common.iterator.StoreIterator;
import org.tron.core.db2.common.DB;
import org.tron.core.store.AccountStore;
import org.tron.protos.Protocol.Account;

public class AccountUsageServiceTest extends BaseMethodTest {

  @Test
  public void scanPersistsSummaryAtRootAndRestartSkipsAccounts() {
    AccountStore store = dbManager.getAccountStore();
    ByteString address = ByteString.copyFrom(new ECKey().getAddress());
    Account account = Account.newBuilder().setAddress(address).setFreeNetUsage(-7).build();
    store.put(address.toByteArray(), new AccountCapsule(account));
    long total = store.size();
    context.getBean(AccountUsageService.class).scanOnce();
    long[] result = chainBaseManager.getTrackerStore().getNegativeUsageScanResult();
    assertNotNull(result);
    assertEquals(total, result[0]);
    assertEquals(1, result[1]);
    assertEquals(dbManager.getHeadBlockNum(), result[2]);
    assertNotNull(chainBaseManager.getTrackerStore().getDb()
        .get("NEGATIVE_USAGE_SCAN_V1".getBytes(StandardCharsets.UTF_8)));
    assertEquals(account, store.get(address.toByteArray()).getInstance());
    AccountStore unused = mock(AccountStore.class);
    new AccountUsageService(unused, chainBaseManager.getTrackerStore(),
        dbManager.getDynamicPropertiesStore()).scanOnce();
    verifyNoInteractions(unused);
    assertArrayEquals(result, chainBaseManager.getTrackerStore().getNegativeUsageScanResult());
  }

  @Test
  public void nativeReadFailureClosesIteratorAndNeverMarksCompleted() throws Exception {
    AccountStore store = mock(AccountStore.class);
    DB<byte[], byte[]> db = mock(DB.class);
    RockStoreIterator iterator = mock(RockStoreIterator.class);
    when(store.getDb()).thenReturn(db);
    when(db.iterator()).thenReturn(iterator);
    doThrow(new IllegalStateException("read failed")).when(iterator).checkStatus();
    AccountUsageService service = new AccountUsageService(store, chainBaseManager.getTrackerStore(),
        dbManager.getDynamicPropertiesStore());
    assertThrows(IllegalStateException.class, service::scanOnce);
    verify(iterator).close();
    assertNull(chainBaseManager.getTrackerStore().getNegativeUsageScanResult());
    context.getBean(AccountUsageService.class).scanOnce();
    assertNotNull(chainBaseManager.getTrackerStore().getNegativeUsageScanResult());
  }

  @Test
  public void interruptionDoesNotPersistCompletion() {
    Thread.currentThread().interrupt();
    try {
      assertThrows(IllegalStateException.class,
          () -> context.getBean(AccountUsageService.class).scanOnce());
      assertNull(chainBaseManager.getTrackerStore().getNegativeUsageScanResult());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  public void levelDbInitialSeekFailureNeverMarksCompleted() throws Exception {
    AccountStore store = mock(AccountStore.class);
    DB<byte[], byte[]> db = mock(DB.class);
    StoreIterator iterator = mock(StoreIterator.class);
    when(store.getDb()).thenReturn(db);
    when(db.iterator()).thenReturn(iterator);
    doThrow(new IllegalStateException("initial LevelDB seek failed"))
        .when(iterator).seek(new byte[0]);
    AccountUsageService service = new AccountUsageService(store, chainBaseManager.getTrackerStore(),
        dbManager.getDynamicPropertiesStore());
    assertThrows(IllegalStateException.class, service::scanOnce);
    verify(iterator).close();
    assertNull(chainBaseManager.getTrackerStore().getNegativeUsageScanResult());
  }
}
