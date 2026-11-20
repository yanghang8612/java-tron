package org.tron.core.service;

import javax.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Asynchronous account usage notifications for the statistics node. */
@Component
@Slf4j(topic = "account-usage")
public class AccountUsageService {

  private volatile SlackUsageAlert slack;

  // Called by FullNode before app.startup(); no account scan or state changes.
  public void start() {
    String webhook = System.getenv("ACCOUNT_USAGE_SLACK_WEBHOOK_URL");
    if (webhook == null || webhook.trim().isEmpty()) {
      logger.info("Slack account usage alerts disabled; set ACCOUNT_USAGE_SLACK_WEBHOOK_URL");
    } else {
      slack = new SlackUsageAlert(webhook.trim());
      logger.info("Slack account usage alerts enabled");
    }
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
