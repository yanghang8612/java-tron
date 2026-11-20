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

  public static boolean hasAbnormalUsage(AccountCapsule capsule, long netLimit, long energyLimit) {
    if (capsule == null || capsule.getInstance() == null) {
      return false;
    }
    Account account = capsule.getInstance();
    return isAbnormal(account.getNetUsage(), netLimit)
        || isAbnormal(account.getFreeNetUsage(), netLimit)
        || isAbnormal(account.getAccountResource().getEnergyUsage(), energyLimit)
        || account.getAccountResource().getStorageUsage() < 0
        || hasAbnormalValue(account.getFreeAssetNetUsageMap(), netLimit)
        || hasAbnormalValue(account.getFreeAssetNetUsageV2Map(), netLimit);
  }

  private static boolean isAbnormal(long value, long limit) {
    return value < 0 || (limit >= 0 && value > limit);
  }

  private static boolean hasAbnormalValue(Map<String, Long> values, long limit) {
    for (long value : values.values()) {
      if (isAbnormal(value, limit)) {
        return true;
      }
    }
    return false;
  }

  public static void logAbnormalUsage(byte[] key, AccountCapsule before, AccountCapsule after,
      long netLimit, long energyLimit) {
    String usage = describeUsage(before, after, false, netLimit, energyLimit);
    AccountUsageMonitor context = CURRENT.get();
    TransactionCapsule trx = context == null ? null : context.transaction;
    BlockCapsule block = context == null ? null : context.block;
    String contractType = trx == null || trx.getContractCount() == 0 ? "none"
        : trx.getInstance().getRawData().getContract(0).getType().name();
    // A put can still be revoked by its enclosing transaction/block session.
    logger.warn("Abnormal account usage write: account={}, txId={}, contractType={}, "
            + "blockNum={}, blockHash={}, phase={}, previousExists={}, previousReadable={}, "
            + "usage=[{}]",
        ByteArray.toHexString(key), trx == null ? "none" : trx.getTransactionId(), contractType,
        block == null ? "none" : block.getNum(), block == null ? "none" : block.getBlockId(),
        trx == null ? "outside-transaction" : block == null ? "pending"
            : block.hasWitnessSignature() ? "apply" : "pack", before != null,
        before == null || before.getInstance() != null, usage);
    if (context != null && trx != null && context.alert != null) {
      String changed = describeUsage(before, after, true, netLimit, energyLimit);
      if (!changed.isEmpty()) {
        context.recordChangedUsage(ByteArray.toHexString(key), changed);
      }
    }
  }

  private static String describeUsage(AccountCapsule before, AccountCapsule after,
      boolean changedOnly, long netLimit, long energyLimit) {
    Account old = before == null ? Account.getDefaultInstance() : before.getInstance();
    Account account = after.getInstance();
    StringBuilder usage = new StringBuilder();
    appendUsage(usage, "net_usage", old == null ? null : old.getNetUsage(), account.getNetUsage(),
        changedOnly, netLimit);
    appendUsage(usage, "free_net_usage", old == null ? null : old.getFreeNetUsage(),
        account.getFreeNetUsage(), changedOnly, netLimit);
    appendUsage(usage, "energy_usage",
        old == null ? null : old.getAccountResource().getEnergyUsage(),
        account.getAccountResource().getEnergyUsage(), changedOnly, energyLimit);
    appendUsage(usage, "storage_usage",
        old == null ? null : old.getAccountResource().getStorageUsage(),
        account.getAccountResource().getStorageUsage(), changedOnly, -1);
    appendAssetUsage(usage, "free_asset_net_usage",
        old == null ? null : old.getFreeAssetNetUsageMap(),
        account.getFreeAssetNetUsageMap(), changedOnly, netLimit);
    appendAssetUsage(usage, "free_asset_net_usageV2",
        old == null ? null : old.getFreeAssetNetUsageV2Map(),
        account.getFreeAssetNetUsageV2Map(), changedOnly, netLimit);
    return usage.toString();
  }

  private static void appendAssetUsage(StringBuilder usage, String field,
      Map<String, Long> before, Map<String, Long> after, boolean changedOnly, long limit) {
    for (Map.Entry<String, Long> entry : after.entrySet()) {
      if (isAbnormal(entry.getValue(), limit)) {
        appendUsage(usage, field + "[" + entry.getKey() + "]",
            before == null ? null : before.getOrDefault(entry.getKey(), 0L), entry.getValue(),
            changedOnly, limit);
      }
    }
  }

  private static void appendUsage(StringBuilder usage, String field, Long before, long after,
      boolean changedOnly, long limit) {
    if (isAbnormal(after, limit) && (!changedOnly || before == null || before != after)) {
      if (usage.length() > 0) {
        usage.append(", ");
      }
      usage.append(field).append('=').append(before == null ? "unknown" : before).append("->")
          .append(after).append("(introduced=")
          .append(before == null ? "unknown" : after < 0 ? before >= 0 : before <= limit)
          .append(')');
      if (after < 0) {
        usage.append("[reason=negative]");
      } else {
        usage.append("[reason=exceeds_total,totalLimit=").append(limit).append(']');
      }
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
      StringBuilder message = new StringBuilder("Abnormal account usage write attempts\n")
          .append("txId=").append(transaction.getTransactionId())
          .append(" contractType=")
          .append(transaction.getContractCount() == 0 ? "none"
              : transaction.getInstance().getRawData().getContract(0).getType().name())
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
