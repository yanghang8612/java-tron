package org.tron.core.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.tron.common.BaseMethodTest;
import org.tron.common.crypto.ECKey;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.db2.ISession;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.store.AccountStore;
import org.tron.protos.Protocol.Account;
import org.tron.protos.Protocol.Account.AccountResource;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;
import org.tron.protos.contract.BalanceContract.TransferContract;

public class AccountUsageMonitorTest extends BaseMethodTest {

  private final Logger usageLogger = (Logger) LoggerFactory.getLogger("account-usage");
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  @Override
  protected void afterInit() {
    appender.start();
    usageLogger.addAppender(appender);
  }

  @Override
  protected void beforeDestroy() {
    usageLogger.detachAppender(appender);
    appender.stop();
  }

  private TransactionCapsule transfer(ByteString owner, ByteString recipient, long amount) {
    return new TransactionCapsule(TransferContract.newBuilder()
        .setOwnerAddress(owner).setToAddress(recipient).setAmount(amount).build(),
        ContractType.TransferContract);
  }

  @Test
  public void negativeWritesReportAllUsageFieldsAndPreserveValues() {
    ByteString address = ByteString.copyFrom(new ECKey().getAddress());
    AccountStore store = dbManager.getAccountStore();
    Account before = Account.newBuilder().setAddress(address).setNetUsage(11)
        .setFreeNetUsage(12).setAccountResource(AccountResource.newBuilder()
            .setEnergyUsage(13).setStorageUsage(14))
        .putFreeAssetNetUsage("token", 15).putFreeAssetNetUsageV2("1000001", 16).build();
    store.put(address.toByteArray(), new AccountCapsule(before));
    assertTrue(appender.list.isEmpty());
    Account after = before.toBuilder().setNetUsage(-1).setFreeNetUsage(-2)
        .setAccountResource(before.getAccountResource().toBuilder()
            .setEnergyUsage(-3).setStorageUsage(-4))
        .putFreeAssetNetUsage("token", -5).putFreeAssetNetUsageV2("1000001", -6).build();
    TransactionCapsule trx = transfer(address, address, 1);
    BlockCapsule block = new BlockCapsule(123, Sha256Hash.ZERO_HASH, 3000, address);
    try (AccountUsageMonitor ignored = AccountUsageMonitor.trace(trx, block)) {
      store.put(address.toByteArray(), new AccountCapsule(after));
    }
    assertEquals(after, store.get(address.toByteArray()).getInstance());
    assertEquals(1, appender.list.size());
    String message = appender.list.get(0).getFormattedMessage();
    assertTrue(message.contains("account=" + ByteArray.toHexString(address.toByteArray())));
    assertTrue(message.contains("txId=" + trx.getTransactionId()));
    assertTrue(message.contains("contractType=TransferContract"));
    assertTrue(message.contains("blockNum=123"));
    assertTrue(message.contains("blockHash=" + block.getBlockId()));
    assertTrue(message.contains("net_usage=11->-1(introduced=true)"));
    assertTrue(message.contains("free_net_usage=12->-2(introduced=true)"));
    assertTrue(message.contains("energy_usage=13->-3(introduced=true)"));
    assertTrue(message.contains("storage_usage=14->-4(introduced=true)"));
    assertTrue(message.contains("free_asset_net_usage[token]=15->-5(introduced=true)"));
    assertTrue(message.contains("free_asset_net_usageV2[1000001]=16->-6(introduced=true)"));
    store.put(address.toByteArray(), new AccountCapsule(before));
    assertEquals(1, appender.list.size());

    // Each family must trigger by itself, including accounts with only TRC10 usage.
    Account[] isolatedNegatives = {
        before.toBuilder().setNetUsage(-1).build(),
        before.toBuilder().setFreeNetUsage(-1).build(),
        before.toBuilder().setAccountResource(before.getAccountResource().toBuilder()
            .setEnergyUsage(-1)).build(),
        before.toBuilder().setAccountResource(before.getAccountResource().toBuilder()
            .setStorageUsage(-1)).build(),
        before.toBuilder().putFreeAssetNetUsage("token", -1).build(),
        before.toBuilder().putFreeAssetNetUsageV2("1000001", -1).build()
    };
    appender.list.clear();
    for (Account negative : isolatedNegatives) {
      store.put(address.toByteArray(), new AccountCapsule(negative));
    }
    assertEquals(isolatedNegatives.length, appender.list.size());
  }

  @Test
  public void inheritedNegativeUsageIsDistinguishedFromItsFirstWrite() {
    ByteString address = ByteString.copyFrom(new ECKey().getAddress());
    AccountCapsule account = new AccountCapsule(Account.newBuilder()
        .setAddress(address).setNetUsage(-1).build());
    AccountStore store = dbManager.getAccountStore();
    store.put(address.toByteArray(), account);
    String first = appender.list.get(0).getFormattedMessage();
    assertTrue(first.contains("previousExists=false"));
    assertTrue(first.contains("net_usage=0->-1(introduced=true)"));
    store.put(address.toByteArray(), account);
    String second = appender.list.get(1).getFormattedMessage();
    assertTrue(second.contains("previousExists=true"));
    assertTrue(second.contains("net_usage=-1->-1(introduced=false)"));
    assertTrue(second.contains("txId=none"));
    assertTrue(second.contains("phase=outside-transaction"));
  }

  @Test
  public void alertsCombineChangedNegativeWritesAndIgnoreInheritedValues() {
    ByteString address = ByteString.copyFrom(new ECKey().getAddress());
    AccountStore store = dbManager.getAccountStore();
    AccountCapsule account = new AccountCapsule(Account.newBuilder().setAddress(address).build());
    store.put(address.toByteArray(), account);
    TransactionCapsule trx = transfer(address, address, 1);
    List<String> alerts = new ArrayList<>();
    try (AccountUsageMonitor ignored = AccountUsageMonitor.trace(trx, null, alerts::add)) {
      account.setNetUsage(-1);
      store.put(address.toByteArray(), account);
      account.setEnergyUsage(-2);
      store.put(address.toByteArray(), account);
      store.put(address.toByteArray(), account);
      assertTrue(alerts.isEmpty());
    }
    assertEquals(1, alerts.size());
    assertTrue(alerts.get(0).contains(trx.getTransactionId().toString()));
    assertTrue(alerts.get(0).contains("net_usage=0->-1"));
    assertTrue(alerts.get(0).contains("energy_usage=0->-2"));
    try (AccountUsageMonitor ignored = AccountUsageMonitor.trace(trx, null, alerts::add)) {
      store.put(address.toByteArray(), account);
    }
    assertEquals(1, alerts.size());
    try (AccountUsageMonitor ignored = AccountUsageMonitor.trace(trx, null, alerts::add)) {
      account.setEnergyUsage(-3);
      store.put(address.toByteArray(), account);
    }
    assertEquals(2, alerts.size());
    assertTrue(alerts.get(1).contains("energy_usage=-2->-3(introduced=false)"));
  }

  @Test
  public void alertFailureDoesNotFailAccountWriteOrLeakTransactionContext() {
    ByteString address = ByteString.copyFrom(new ECKey().getAddress());
    AccountCapsule account = new AccountCapsule(Account.newBuilder()
        .setAddress(address).setNetUsage(-1).build());
    try (AccountUsageMonitor ignored = AccountUsageMonitor.trace(transfer(address, address, 1),
        null, message -> {
          throw new IllegalStateException("unavailable");
        })) {
      dbManager.getAccountStore().put(address.toByteArray(), account);
    }
    assertEquals(-1, dbManager.getAccountStore().get(address.toByteArray()).getNetUsage());
    dbManager.getAccountStore().put(address.toByteArray(), account);
    assertTrue(appender.list.get(appender.list.size() - 1).getFormattedMessage()
        .contains("txId=none"));
  }

  @Test
  public void revokedWritesRemainDiagnosticAndRestoreNestedContext() {
    ByteString address = ByteString.copyFrom(new ECKey().getAddress());
    AccountStore store = dbManager.getAccountStore();
    Account before = Account.newBuilder().setAddress(address).setFreeNetUsage(1).build();
    store.put(address.toByteArray(), new AccountCapsule(before));
    AccountCapsule negative = new AccountCapsule(before.toBuilder().setFreeNetUsage(-1).build());
    TransactionCapsule outer = transfer(address, address, 1);
    TransactionCapsule inner = transfer(address, address, 2);
    try (AccountUsageMonitor ignored = AccountUsageMonitor.trace(outer, null)) {
      assertThrows(IllegalStateException.class, () -> {
        try (ISession session = dbManager.getRevokingStore().buildSession();
            AccountUsageMonitor nested = AccountUsageMonitor.trace(inner, null)) {
          store.put(address.toByteArray(), negative);
          throw new IllegalStateException("revoke diagnostic write");
        }
      });
      assertEquals(before, store.get(address.toByteArray()).getInstance());
      store.put(address.toByteArray(), negative);
    }
    store.put(address.toByteArray(), negative);
    assertTrue(appender.list.get(0).getFormattedMessage().contains("txId="
        + inner.getTransactionId()));
    assertTrue(appender.list.get(0).getFormattedMessage().contains("phase=pending"));
    assertTrue(appender.list.get(1).getFormattedMessage().contains("txId="
        + outer.getTransactionId()));
    assertTrue(appender.list.get(2).getFormattedMessage().contains("txId=none"));
  }

  @Test
  public void managerAttributesWritesAndClearsContextOnSuccessAndFailure() throws Exception {
    ByteString owner = ByteString.copyFrom(new ECKey().getAddress());
    ByteString recipient = ByteString.copyFrom(new ECKey().getAddress());
    AccountStore store = dbManager.getAccountStore();
    store.put(owner.toByteArray(), new AccountCapsule(Account.newBuilder().setAddress(owner)
        .setBalance(100_000_000L)
        .setAccountResource(AccountResource.newBuilder().setStorageUsage(-1)).build()));
    store.put(recipient.toByteArray(), new AccountCapsule(Account.newBuilder()
        .setAddress(recipient).build()));
    appender.list.clear();
    TransactionCapsule trx = transfer(owner, recipient, 1000);
    BlockCapsule block = new BlockCapsule(1, chainBaseManager.getGenesisBlockId(),
        chainBaseManager.getGenesisBlock().getTimeStamp() + 3000, owner);
    assertNotNull(dbManager.processTransaction(trx, block));
    assertFalse(appender.list.isEmpty());
    for (ILoggingEvent event : appender.list) {
      assertTrue(event.getFormattedMessage().contains("txId=" + trx.getTransactionId()));
    }
    store.put(owner.toByteArray(), store.get(owner.toByteArray()));
    assertTrue(appender.list.get(appender.list.size() - 1).getFormattedMessage()
        .contains("txId=none"));
    appender.list.clear();
    TransactionCapsule failing = transfer(owner, recipient, 1_000_000_000L);
    assertThrows(ContractValidateException.class,
        () -> dbManager.processTransaction(failing, block));
    assertFalse(appender.list.isEmpty());
    for (ILoggingEvent event : appender.list) {
      assertTrue(event.getFormattedMessage().contains("txId=" + failing.getTransactionId()));
    }
    store.put(owner.toByteArray(), store.get(owner.toByteArray()));
    assertTrue(appender.list.get(appender.list.size() - 1).getFormattedMessage()
        .contains("txId=none"));
  }
}
