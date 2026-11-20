package org.tron.core.service;

import java.io.IOException;
import java.util.Map;
import javax.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.utils.ByteArray;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.db.AccountUsageMonitor;
import org.tron.core.db.common.iterator.DBIterator;
import org.tron.core.db.common.iterator.RockStoreIterator;
import org.tron.core.store.AccountStore;
import org.tron.core.store.DynamicPropertiesStore;
import org.tron.core.store.TrackerStore;

/** Startup baseline and asynchronous notifications for the statistics node. */
@Component
@Slf4j(topic = "account-usage")
public class AccountUsageService {

  private final AccountStore accountStore;
  private final TrackerStore trackerStore;
  private final DynamicPropertiesStore dynamicPropertiesStore;
  private volatile SlackUsageAlert slack;

  @Autowired
  public AccountUsageService(AccountStore accountStore, TrackerStore trackerStore,
      DynamicPropertiesStore dynamicPropertiesStore) {
    this.accountStore = accountStore;
    this.trackerStore = trackerStore;
    this.dynamicPropertiesStore = dynamicPropertiesStore;
  }

  // Called by FullNode before app.startup(), after checkpoint recovery and genesis init.
  public void start() {
    scanOnce();
    String webhook = System.getenv("ACCOUNT_USAGE_SLACK_WEBHOOK_URL");
    if (webhook == null || webhook.trim().isEmpty()) {
      logger.info("Slack account usage alerts disabled; set ACCOUNT_USAGE_SLACK_WEBHOOK_URL");
    } else {
      slack = new SlackUsageAlert(webhook.trim());
      logger.info("Slack account usage alerts enabled");
    }
  }

  public void scanOnce() {
    long[] previous = trackerStore.getNegativeUsageScanResult();
    if (previous != null) {
      logger.info("Account usage scan already completed: accounts={}, negativeAccounts={}, "
              + "blockNum={}, completedAt={}", previous[0], previous[1], previous[2], previous[3]);
      return;
    }
    long started = System.nanoTime();
    long blockNum = dynamicPropertiesStore.getLatestBlockHeaderNumber();
    long total = 0;
    long negatives = 0;
    logger.info("Account usage scan starting: blockNum={}", blockNum);
    // Startup has no active revoking session; checkpoints have been recovered into the root.
    // Use the raw iterator so it is closed even on decoding errors or interruption.
    try (DBIterator iterator = (DBIterator) accountStore.getDb().iterator()) {
      // account uses bytewise key order. Unlike LevelDB JNI seekToFirst(), seek()
      // checks native read status, so an initial I/O error cannot look like an empty DB.
      iterator.seek(new byte[0]);
      while (iterator.valid()) {
        if (Thread.currentThread().isInterrupted()) {
          throw new IllegalStateException("Account usage scan interrupted");
        }
        Map.Entry<byte[], byte[]> entry = iterator.next();
        AccountCapsule account = new AccountCapsule(entry.getValue());
        if (account.getInstance() == null) {
          throw new IllegalStateException("Cannot decode account during usage scan");
        }
        total++;
        if (AccountUsageMonitor.hasNegativeUsage(account)) {
          negatives++;
          logger.warn("Negative account usage baseline: account={}, usage=[{}]",
              ByteArray.toHexString(entry.getKey()),
              AccountUsageMonitor.describeNegativeUsage(account));
        }
        if (total % 1_000_000 == 0) {
          logger.info("Account usage scan progress: accounts={}, negativeAccounts={}",
              total, negatives);
        }
      }
      if (iterator instanceof RockStoreIterator) {
        ((RockStoreIterator) iterator).checkStatus();
      }
    } catch (IOException | RuntimeException e) {
      throw new IllegalStateException("Account usage scan incomplete; completion not marked", e);
    }
    if (Thread.currentThread().isInterrupted()) {
      throw new IllegalStateException("Account usage scan interrupted; completion not marked");
    }
    trackerStore.saveNegativeUsageScanResult(total, negatives, blockNum,
        System.currentTimeMillis());
    logger.info("Account usage scan completed: accounts={}, negativeAccounts={}, blockNum={}, "
        + "elapsedMs={}", total, negatives, blockNum, (System.nanoTime() - started) / 1_000_000);
  }

  public void alert(String message) {
    if (slack != null) {
      slack.alert(message);
    }
  }

  @PreDestroy
  public void close() {
    if (slack != null) {
      slack.close();
    }
  }
}
