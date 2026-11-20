package org.tron.core.db;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.tron.common.utils.ByteArray;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.protos.Protocol.Account;

/** Diagnostic context for account writes, independent of history-balance tracing. */
@Slf4j(topic = "account-usage")
public final class AccountUsageMonitor implements AutoCloseable {

  private static final ThreadLocal<AccountUsageMonitor> CURRENT = new ThreadLocal<>();

  private final AccountUsageMonitor previous;
  private final TransactionCapsule transaction;
  private final BlockCapsule block;
  private final Consumer<String> alert;
  private Map<String, String> changedAccounts;
  private boolean truncated;

  private AccountUsageMonitor(TransactionCapsule transaction, BlockCapsule block,
      Consumer<String> alert) {
    this.previous = CURRENT.get();
    this.transaction = transaction;
    this.block = block;
    this.alert = alert;
    CURRENT.set(this);
  }

  public static AccountUsageMonitor trace(TransactionCapsule transaction, BlockCapsule block) {
    return trace(transaction, block, null);
  }

  public static AccountUsageMonitor trace(TransactionCapsule transaction, BlockCapsule block,
      Consumer<String> alert) {
    return new AccountUsageMonitor(transaction, block, alert);
  }

  public static boolean hasNegativeUsage(AccountCapsule capsule) {
    if (capsule == null) {
      return false;
    }
    Account account = capsule.getInstance();
    return account.getNetUsage() < 0 || account.getFreeNetUsage() < 0
        || account.getAccountResource().getEnergyUsage() < 0
        || account.getAccountResource().getStorageUsage() < 0
        || hasNegativeValue(account.getFreeAssetNetUsageMap())
        || hasNegativeValue(account.getFreeAssetNetUsageV2Map());
  }

  private static boolean hasNegativeValue(Map<String, Long> values) {
    for (long value : values.values()) {
      if (value < 0) {
        return true;
      }
    }
    return false;
  }

  public static void logNegativeUsage(byte[] key, AccountCapsule before, AccountCapsule after) {
    String usage = describeUsage(before, after, false);
    AccountUsageMonitor context = CURRENT.get();
    TransactionCapsule trx = context == null ? null : context.transaction;
    BlockCapsule block = context == null ? null : context.block;
    String contractType = trx == null || trx.getContractCount() == 0 ? "none"
        : trx.getInstance().getRawData().getContract(0).getType().name();
    // A put can still be revoked by its enclosing transaction/block session.
    logger.warn("Negative account usage write: account={}, txId={}, contractType={}, "
            + "blockNum={}, blockHash={}, phase={}, previousExists={}, usage=[{}]",
        ByteArray.toHexString(key), trx == null ? "none" : trx.getTransactionId(), contractType,
        block == null ? "none" : block.getNum(), block == null ? "none" : block.getBlockId(),
        trx == null ? "outside-transaction" : block == null ? "pending"
            : block.hasWitnessSignature() ? "apply" : "pack", before != null, usage);
    if (context != null && trx != null && context.alert != null) {
      String changed = describeUsage(before, after, true);
      if (!changed.isEmpty()) {
        context.recordChangedUsage(ByteArray.toHexString(key), changed);
      }
    }
  }

  public static String describeNegativeUsage(AccountCapsule account) {
    return describeUsage(account, account, false);
  }

  private static String describeUsage(AccountCapsule before, AccountCapsule after,
      boolean changedOnly) {
    Account old = before == null ? Account.getDefaultInstance() : before.getInstance();
    Account account = after.getInstance();
    StringBuilder usage = new StringBuilder();
    appendUsage(usage, "net_usage", old.getNetUsage(), account.getNetUsage(), changedOnly);
    appendUsage(usage, "free_net_usage", old.getFreeNetUsage(), account.getFreeNetUsage(),
        changedOnly);
    appendUsage(usage, "energy_usage", old.getAccountResource().getEnergyUsage(),
        account.getAccountResource().getEnergyUsage(), changedOnly);
    appendUsage(usage, "storage_usage", old.getAccountResource().getStorageUsage(),
        account.getAccountResource().getStorageUsage(), changedOnly);
    appendAssetUsage(usage, "free_asset_net_usage", old.getFreeAssetNetUsageMap(),
        account.getFreeAssetNetUsageMap(), changedOnly);
    appendAssetUsage(usage, "free_asset_net_usageV2", old.getFreeAssetNetUsageV2Map(),
        account.getFreeAssetNetUsageV2Map(), changedOnly);
    return usage.toString();
  }

  private static void appendAssetUsage(StringBuilder usage, String field,
      Map<String, Long> before, Map<String, Long> after, boolean changedOnly) {
    for (Map.Entry<String, Long> entry : after.entrySet()) {
      if (entry.getValue() < 0) {
        appendUsage(usage, field + "[" + entry.getKey() + "]",
            before.getOrDefault(entry.getKey(), 0L), entry.getValue(), changedOnly);
      }
    }
  }

  private static void appendUsage(StringBuilder usage, String field, long before, long after,
      boolean changedOnly) {
    if (after < 0 && (!changedOnly || before != after)) {
      if (usage.length() > 0) {
        usage.append(", ");
      }
      usage.append(field).append('=').append(before).append("->").append(after)
          .append("(introduced=").append(before >= 0).append(')');
    }
  }

  private void recordChangedUsage(String address, String usage) {
    if (changedAccounts == null) {
      changedAccounts = new LinkedHashMap<>();
    }
    if (changedAccounts.size() < 20 || changedAccounts.containsKey(address)) {
      String previousUsage = changedAccounts.get(address);
      String combined = previousUsage == null ? usage : previousUsage + ", " + usage;
      changedAccounts.put(address,
          combined.length() > 2000 ? combined.substring(0, 2000) : combined);
    } else {
      truncated = true;
    }
  }

  @Override
  public void close() {
    if (previous == null) {
      CURRENT.remove();
    } else {
      CURRENT.set(previous);
    }
    if (changedAccounts != null) {
      StringBuilder message = new StringBuilder("Negative account usage write attempts\n")
          .append("txId=").append(transaction.getTransactionId())
          .append(" contractType=")
          .append(transaction.getInstance().getRawData().getContract(0).getType().name())
          .append(" blockNum=").append(block == null ? "none" : block.getNum())
          .append(" blockHash=").append(block == null ? "none" : block.getBlockId())
          .append(" phase=").append(block == null ? "pending"
              : block.hasWitnessSignature() ? "apply" : "pack");
      changedAccounts.forEach((address, usage) -> message.append('\n').append(address)
          .append(": ").append(usage));
      message.append("\nWrites may be reverted; see node logs for full details.");
      if (truncated) {
        message.append(" Additional accounts omitted.");
      }
      try {
        alert.accept(message.toString());
      } catch (RuntimeException e) {
        logger.warn("Unable to queue account usage alert: {}", e.getClass().getSimpleName());
      }
    }
  }
}
