package org.tron.core.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.io.IOUtils;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.tron.common.es.ExecutorServiceManager;
import org.tron.common.utils.JsonUtil;

public class SlackUsageAlertTest {

  private HttpServer server;
  private ExecutorService executor;
  private final AtomicInteger requests = new AtomicInteger();
  private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
  private final BlockingQueue<Long> requestTimes = new LinkedBlockingQueue<>();
  private String baseUrl;

  @Before
  public void startServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = ExecutorServiceManager.newSingleThreadExecutor("slack-usage-test");
    server.setExecutor(executor);
    server.createContext("/", exchange -> {
      int request = requests.incrementAndGet();
      requestTimes.add(System.nanoTime());
      assertEquals("POST", exchange.getRequestMethod());
      assertTrue(exchange.getRequestHeaders().getFirst("Content-Type")
          .startsWith("application/json"));
      String payload = IOUtils.toString(exchange.getRequestBody(), StandardCharsets.UTF_8);
      messages.add((String) JsonUtil.json2Obj(payload, Map.class).get("text"));
      String path = exchange.getRequestURI().getPath();
      int status = 200;
      if ("/rate-limit".equals(path) && request == 1) {
        exchange.getResponseHeaders().set("Retry-After", "1");
        status = 429;
      } else if ("/final-rate-limit".equals(path) && request <= 3) {
        exchange.getResponseHeaders().set("Retry-After", request == 3 ? "1" : "0");
        status = 429;
      } else if ("/server-error".equals(path) && request == 1) {
        status = 503;
      } else if ("/rejected".equals(path)) {
        status = 400;
      } else if ("/redirect".equals(path)) {
        exchange.getResponseHeaders().set("Location", baseUrl + "/leaked");
        status = 302;
      }
      byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @After
  public void stopServer() {
    server.stop(0);
    ExecutorServiceManager.shutdownAndAwaitTermination(executor, "slack-usage-test");
  }

  @Test
  public void sendsEscapedJsonAndDrainsQueueOnShutdown() throws Exception {
    String message = "negative usage: \"account\"\nenergy_usage=-1";
    try (SlackUsageAlert slack = new SlackUsageAlert(baseUrl + "/ok", 1000, 1)) {
      assertTrue(slack.alert(message));
      assertEquals(message, messages.poll(3, TimeUnit.SECONDS));
      assertTrue(slack.alert("next transaction"));
    }
    assertEquals(2, requests.get());
    assertEquals("next transaction", messages.poll(3, TimeUnit.SECONDS));
  }

  @Test
  public void retriesRateLimitAndHonorsRetryAfter() {
    long started = System.nanoTime();
    try (SlackUsageAlert slack = new SlackUsageAlert(baseUrl + "/rate-limit", 1000, 1)) {
      assertTrue(slack.alert("negative usage"));
    }
    assertEquals(2, requests.get());
    assertTrue(System.nanoTime() - started >= TimeUnit.SECONDS.toNanos(1));
  }

  @Test
  public void retriesTransientServerFailure() {
    try (SlackUsageAlert slack = new SlackUsageAlert(baseUrl + "/server-error", 1000, 1)) {
      assertTrue(slack.alert("negative usage"));
    }
    assertEquals(2, requests.get());
  }

  @Test
  public void finalRateLimitDelaysNextQueuedAlert() throws Exception {
    try (SlackUsageAlert slack = new SlackUsageAlert(baseUrl + "/final-rate-limit", 1000, 1)) {
      assertTrue(slack.alert("first transaction"));
      assertTrue(slack.alert("second transaction"));
    }
    assertEquals(4, requests.get());
    requestTimes.remove();
    requestTimes.remove();
    long finalRateLimit = requestTimes.remove();
    long nextAlert = requestTimes.remove();
    assertTrue("Next alert ignored the final 429 cooldown",
        nextAlert - finalRateLimit >= TimeUnit.SECONDS.toNanos(1));
  }

  @Test
  public void permanentFailureAndRedirectAreNotRetried() {
    try (SlackUsageAlert slack = new SlackUsageAlert(baseUrl + "/rejected", 1000, 1)) {
      assertTrue(slack.alert("negative usage"));
    }
    assertEquals(1, requests.get());
    try (SlackUsageAlert slack = new SlackUsageAlert(baseUrl + "/redirect", 1000, 1)) {
      assertTrue(slack.alert("negative usage"));
    }
    assertEquals(2, requests.get());
  }

  @Test
  public void invalidUrlIsRedactedAndStoppedWorkerRejectsWithoutThrowing() {
    String credential = "private-webhook-credential";
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> new SlackUsageAlert("invalid:" + credential));
    assertFalse(error.toString().contains(credential));
    SlackUsageAlert slack = new SlackUsageAlert(baseUrl + "/ok");
    slack.close();
    assertFalse(slack.alert("negative usage"));
    assertEquals(0, requests.get());
  }

  @Test
  public void postmanSimulationReceivesRealClientAndRateLimitRetry() throws Exception {
    String mockBase = System.getenv("ACCOUNT_USAGE_TEST_WEBHOOK_BASE_URL");
    Assume.assumeTrue(mockBase != null && mockBase.startsWith("http://127.0.0.1:"));
    try (SlackUsageAlert slack = new SlackUsageAlert(mockBase + "/services/ok", 1000, 1)) {
      assertTrue(slack.alert("txId=simulation-success energy_usage=0->-1"));
    }
    try (SlackUsageAlert slack = new SlackUsageAlert(mockBase + "/services/rate", 1000, 1)) {
      assertTrue(slack.alert("txId=simulation-retry net_usage=0->-2"));
    }
    HttpURLConnection connection =
        (HttpURLConnection) new URL(mockBase + "/_state").openConnection();
    connection.setConnectTimeout(1000);
    connection.setReadTimeout(1000);
    try (InputStream input = connection.getInputStream()) {
      Map state = JsonUtil.json2Obj(IOUtils.toString(input, StandardCharsets.UTF_8), Map.class);
      assertEquals(1, ((Number) state.get("ok")).intValue());
      assertEquals(2, ((Number) state.get("rate")).intValue());
      assertEquals(0, ((Number) state.get("invalid")).intValue());
    } finally {
      connection.disconnect();
    }
  }
}
