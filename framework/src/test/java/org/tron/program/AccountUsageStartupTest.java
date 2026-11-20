package org.tron.program;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.tron.common.TestConstants;
import org.tron.common.application.TronApplicationContext;
import org.tron.common.exit.ExitManager;
import org.tron.core.config.DefaultConfig;
import org.tron.core.config.args.Args;
import org.tron.core.exception.TronError;
import org.tron.core.store.AccountStore;
import org.tron.core.store.TrackerStore;

public class AccountUsageStartupTest {

  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void scanFailureExitsAndRestartCanRescanSameDatabase() throws Exception {
    Path directory = temporaryFolder.newFolder().toPath();
    String failure = runProbe(directory, "scan-failure", null, 1);
    assertTrue(failure, failure.contains("ACCOUNT_USAGE_INIT(1)"));
    assertTrue(failure, failure.contains("Cannot decode account during usage scan"));
    String restart = runProbe(directory, "rescan", null, 0);
    assertTrue(restart, restart.contains("USAGE_SCAN_RESTART_COMPLETED"));
    assertFalse(restart, restart.contains("Account usage scan already completed"));
  }

  @Test
  public void invalidWebhookExitsAndDoesNotLeakCredential() throws Exception {
    String credential = "invalid-webhook-private-credential";
    String output = runProbe(temporaryFolder.newFolder().toPath(), "webhook-failure",
        credential, TronError.ErrCode.ACCOUNT_USAGE_INIT.getCode());
    assertTrue(output, output.contains("ACCOUNT_USAGE_INIT(1)"));
    assertTrue(output, output.contains("Invalid account usage Slack webhook URL"));
    assertFalse(output, output.contains(credential));
  }

  private String runProbe(Path directory, String mode, String webhook, int expectedExit)
      throws Exception {
    Path output = directory.resolve(mode + ".log");
    ProcessBuilder builder = new ProcessBuilder(
        Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
        "-Xmx512m", "-cp", testClasspath(), StartupProbe.class.getName(),
        directory.toString(), mode)
        .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
    builder.environment().remove("ACCOUNT_USAGE_SLACK_WEBHOOK_URL");
    if (webhook != null) {
      builder.environment().put("ACCOUNT_USAGE_SLACK_WEBHOOK_URL", webhook);
    }
    Process process = builder.start();
    try {
      boolean exited = process.waitFor(30, TimeUnit.SECONDS);
      String logs = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
      assertTrue("Startup subprocess did not exit:\n" + logs, exited);
      assertEquals(logs, expectedExit, process.exitValue());
      return logs;
    } finally {
      process.destroyForcibly();
      assertTrue(process.waitFor(5, TimeUnit.SECONDS));
    }
  }

  private String testClasspath() throws Exception {
    Set<String> entries = new LinkedHashSet<>();
    for (ClassLoader loader = getClass().getClassLoader(); loader != null;
        loader = loader.getParent()) {
      if (loader instanceof URLClassLoader) {
        for (URL url : ((URLClassLoader) loader).getURLs()) {
          if ("file".equals(url.getProtocol())) {
            entries.add(Paths.get(url.toURI()).toString());
          }
        }
      }
    }
    entries.addAll(Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator)));
    return String.join(File.pathSeparator, entries);
  }

  /** Runs the real post-context startup step with checkpoint-v2's non-daemon pruner. */
  public static class StartupProbe {

    public static void main(String[] args) {
      ExitManager.initExceptionHandler();
      Args.setParam(new String[] {"--output-directory", args[0], "--p2p-disable", "true"},
          TestConstants.TEST_CONF);
      Args.getInstance().getStorage().setCheckpointVersion(2);
      TronApplicationContext context = new TronApplicationContext(DefaultConfig.class);
      AccountStore accounts = context.getBean(AccountStore.class);
      TrackerStore tracker = context.getBean(TrackerStore.class);
      byte[] key = new byte[21];
      key[0] = 0x41;
      key[20] = 0x7f;
      if ("scan-failure".equals(args[1])) {
        accounts.getDb().put(key, new byte[] {(byte) 0x80});
      } else if ("rescan".equals(args[1])) {
        assertNull(tracker.getNegativeUsageScanResult());
        accounts.getDb().remove(key);
      }
      // The real context is initialized and owns DB locks, but no API/P2P service is started.
      assertTrue(Thread.getAllStackTraces().keySet().stream().anyMatch(thread ->
          "checkpoint-prune".equals(thread.getName()) && !thread.isDaemon()));
      FullNode.startAccountUsage(context);
      if ("rescan".equals(args[1])) {
        assertTrue(tracker.getNegativeUsageScanResult() != null);
        System.out.println("USAGE_SCAN_RESTART_COMPLETED");
      }
      context.close();
      Args.clearParam();
    }
  }
}
