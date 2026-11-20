package org.tron.core.service;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.tron.common.es.ExecutorServiceManager;
import org.tron.common.utils.JsonUtil;

/** Bounded, best-effort Slack delivery; network failures never escape to transaction execution. */
@Slf4j(topic = "account-usage")
public final class SlackUsageAlert implements AutoCloseable {

  private final URL webhook;
  private final int timeoutMs;
  private final long intervalMs;
  private final ExecutorService worker;
  private long nextSendNanos;

  public SlackUsageAlert(String webhook) {
    this(webhook, 3000, 1000);
  }

  SlackUsageAlert(String webhook, int timeoutMs, long intervalMs) {
    try {
      URI uri = new URI(webhook);
      if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
          || uri.getHost() == null || uri.getUserInfo() != null) {
        throw new IllegalArgumentException();
      }
      this.webhook = uri.toURL();
    } catch (Exception e) {
      // Never include the URL or exception message: the path contains a credential.
      throw new IllegalArgumentException("Invalid account usage Slack webhook URL");
    }
    this.timeoutMs = timeoutMs;
    this.intervalMs = intervalMs;
    worker = ExecutorServiceManager.newThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(256), "account-usage-slack", true);
  }

  public boolean alert(String message) {
    String text = message.length() > 12000
        ? message.substring(0, 12000) + "\nTruncated; see node logs." : message;
    byte[] payload = JsonUtil.obj2Json(Collections.singletonMap("text", text))
        .getBytes(StandardCharsets.UTF_8);
    try {
      worker.execute(() -> deliver(payload));
      return true;
    } catch (RejectedExecutionException e) {
      logger.warn("Slack account usage alert not queued: worker stopped or queue full; see logs");
      return false;
    }
  }

  private void deliver(byte[] payload) {
    for (int attempt = 1; attempt <= 3; attempt++) {
      HttpURLConnection connection = null;
      long retryMs = 1000L * attempt;
      try {
        long waitNanos = nextSendNanos - System.nanoTime();
        if (waitNanos > 0) {
          TimeUnit.NANOSECONDS.sleep(waitNanos);
        }
        nextSendNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(intervalMs);
        connection = (HttpURLConnection) webhook.openConnection();
        connection.setConnectTimeout(timeoutMs);
        connection.setReadTimeout(timeoutMs);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(payload.length);
        try (OutputStream output = connection.getOutputStream()) {
          output.write(payload);
        }
        int status = connection.getResponseCode();
        if (status == 200) {
          return;
        }
        if (status == 429) {
          String retryAfter = connection.getHeaderField("Retry-After");
          if (retryAfter != null && retryAfter.matches("[0-9]{1,5}")) {
            retryMs = TimeUnit.SECONDS.toMillis(Long.parseLong(retryAfter));
          }
          // The rate limit applies to the webhook, including the next queued message
          // after this message has exhausted its final attempt.
          long retryAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(retryMs);
          if (retryAt - nextSendNanos > 0) {
            nextSendNanos = retryAt;
          }
        } else if (status < 500 || status >= 600) {
          logger.warn("Slack account usage alert rejected: HTTP {}; see node logs", status);
          return;
        }
        logger.warn("Slack account usage alert attempt {} failed: HTTP {}", attempt, status);
      } catch (IOException e) {
        logger.warn("Slack account usage alert attempt {} failed: {}", attempt,
            e.getClass().getSimpleName());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } finally {
        if (connection != null) {
          connection.disconnect();
        }
      }
      if (attempt < 3) {
        try {
          TimeUnit.MILLISECONDS.sleep(retryMs);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
    logger.warn("Slack account usage alert delivery exhausted retries; see node logs");
  }

  @Override
  public void close() {
    worker.shutdown();
    try {
      if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
        int discarded = worker.shutdownNow().size();
        logger.warn("Stopping Slack account usage alerts; {} queued alerts discarded", discarded);
        worker.awaitTermination(5, TimeUnit.SECONDS);
      }
    } catch (InterruptedException e) {
      worker.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
