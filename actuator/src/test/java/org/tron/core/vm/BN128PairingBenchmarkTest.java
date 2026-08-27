package org.tron.core.vm;

import static org.junit.Assert.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.Assume;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.runtime.InternalTransaction;
import org.tron.core.vm.PrecompiledContracts.BN128Pairing;
import org.tron.core.vm.config.VMConfig;
import org.tron.core.vm.program.Program;
import org.tron.core.vm.program.Program.OutOfTimeException;
import org.tron.core.vm.program.invoke.ProgramInvokeMockImpl;
import org.tron.protos.Protocol;

/**
 * Opt-in benchmark for the alt_bn128 pairing precompile.
 *
 * <p>Run with:
 * <pre>
 * BN128_BENCHMARK=true ./gradlew :actuator:cleanTest :actuator:test \
 *   --tests org.tron.core.vm.BN128PairingBenchmarkTest
 * </pre>
 *
 * <p>The benchmark uses valid, non-zero G1/G2 generator points. It first warms the direct
 * precompile path and the complete TVM CALL path, then reports latency percentiles and the number
 * of calls that complete before the configured TVM deadline.
 */
public class BN128PairingBenchmarkTest {

  private static final int WORD_SIZE = 32;
  private static final int PAIR_SIZE = 6 * WORD_SIZE;

  private static final String[] GENERATOR_PAIR = {
      "1",
      "2",
      "11559732032986387107991004021392285783925812861821192530917403151452391805634",
      "10857046999023057135944570762232829481370756359578518086990519993285655852781",
      "4082367875863433681332203403145435568316851327593401208105741076214120093531",
      "8495653923123431417604973247489272438418190587263600148770280649306958101930"
  };

  @Test
  public void benchmarkPairingAtMainnetDeadline() throws Exception {
    Assume.assumeTrue(
        "Set BN128_BENCHMARK=true to run this benchmark",
        envBoolean("BN128_BENCHMARK", false));

    int maxPairs = envInt("BN128_MAX_PAIRS", 6, 1, 128);
    int samples = envInt("BN128_SAMPLES", 101, 11, 10_000);
    int tvmSamples = envInt("BN128_TVM_SAMPLES", 51, 11, 10_000);
    int directWarmupSeconds = envInt("BN128_WARMUP_SECONDS", 30, 1, 3_600);
    int tvmWarmupSeconds = envInt("BN128_TVM_WARMUP_SECONDS", 15, 1, 3_600);
    long timeoutMs = envInt("BN128_TIMEOUT_MS", 80, 1, 60_000);

    boolean previousIstanbul = VMConfig.allowTvmIstanbul();
    boolean previousDebug = CommonParameter.getInstance().isDebug();
    Logger vmLogger = (Logger) LoggerFactory.getLogger("VM");
    Level previousVmLogLevel = vmLogger.getLevel();

    VMConfig.initAllowTvmIstanbul(1);
    CommonParameter.getInstance().setDebug(false);
    vmLogger.setLevel(Level.WARN);

    try {
      runBenchmark(maxPairs, samples, tvmSamples, directWarmupSeconds,
          tvmWarmupSeconds, timeoutMs);
    } finally {
      VMConfig.initAllowTvmIstanbul(previousIstanbul ? 1 : 0);
      CommonParameter.getInstance().setDebug(previousDebug);
      vmLogger.setLevel(previousVmLogLevel);
    }
  }

  private static void runBenchmark(int maxPairs, int samples, int tvmSamples,
      int directWarmupSeconds, int tvmWarmupSeconds, long timeoutMs) throws Exception {
    BN128Pairing pairing = new BN128Pairing();
    byte[][] inputs = new byte[maxPairs + 1][];
    for (int pairCount = 1; pairCount <= maxPairs; pairCount++) {
      inputs[pairCount] = inputForPairs(pairCount);
    }

    System.out.println("BN128_BENCHMARK_BEGIN");
    printEnvironment();
    System.out.printf(Locale.ROOT,
        "config maxPairs=%d samples=%d tvmSamples=%d timeoutMs=%d "
            + "directWarmupSeconds=%d tvmWarmupSeconds=%d%n",
        maxPairs, samples, tvmSamples, timeoutMs, directWarmupSeconds, tvmWarmupSeconds);

    long directWarmupIterations = warmupDirect(
        pairing, inputs, maxPairs, directWarmupSeconds, 200);
    System.out.printf(Locale.ROOT,
        "warmup path=direct iterations=%d requestedSeconds=%d%n",
        directWarmupIterations, directWarmupSeconds);

    JumpTable jumpTable = OperationRegistry.beginExecution(false);
    int tvmWarmupMaxPairs = Math.min(maxPairs, 5);
    long tvmWarmupIterations = warmupTvm(
        jumpTable, tvmWarmupMaxPairs, tvmWarmupSeconds, 100);
    System.out.printf(Locale.ROOT,
        "warmup path=tvm iterations=%d requestedSeconds=%d maxPairs=%d%n",
        tvmWarmupIterations, tvmWarmupSeconds, tvmWarmupMaxPairs);

    int directP50Max = 0;
    int directP99Max = 0;
    System.out.println("BN128_DIRECT_RESULTS_BEGIN");
    for (int pairCount = 1; pairCount <= maxPairs; pairCount++) {
      long[] elapsed = new long[samples];
      int overDeadline = 0;
      for (int i = 0; i < samples; i++) {
        long start = System.nanoTime();
        Pair<Boolean, byte[]> result = pairing.execute(inputs[pairCount]);
        elapsed[i] = System.nanoTime() - start;
        assertTrue("BN128 precompile rejected valid input", result.getLeft());
        if (elapsed[i] > timeoutMs * 1_000_000L) {
          overDeadline++;
        }
      }
      Arrays.sort(elapsed);
      printLatency("direct", pairCount, samples, overDeadline,
          45_000L + 34_000L * pairCount, elapsed);
      if (percentile(elapsed, 0.50) <= timeoutMs * 1_000_000L) {
        directP50Max = pairCount;
      }
      if (percentile(elapsed, 0.99) <= timeoutMs * 1_000_000L) {
        directP99Max = pairCount;
      }
    }
    System.out.println("BN128_DIRECT_RESULTS_END");

    int tvmAnySuccessMax = 0;
    int tvmAllSuccessMax = 0;
    System.out.println("BN128_TVM_RESULTS_BEGIN");
    for (int pairCount = 1; pairCount <= maxPairs; pairCount++) {
      long[] elapsed = new long[tvmSamples];
      int success = 0;
      for (int i = 0; i < tvmSamples; i++) {
        TvmSample sample = runInTvm(pairCount, timeoutMs, jumpTable);
        elapsed[i] = sample.elapsedNanos;
        if (sample.success) {
          success++;
        }
      }
      Arrays.sort(elapsed);
      int timedOut = tvmSamples - success;
      System.out.printf(Locale.ROOT,
          "path=tvm pairs=%d samples=%d success=%d timedOut=%d successRate=%.2f%% "
              + "avgMs=%.3f p50Ms=%.3f p90Ms=%.3f p99Ms=%.3f maxMs=%.3f%n",
          pairCount, tvmSamples, success, timedOut, success * 100.0 / tvmSamples,
          average(elapsed) / 1_000_000.0,
          percentile(elapsed, 0.50) / 1_000_000.0,
          percentile(elapsed, 0.90) / 1_000_000.0,
          percentile(elapsed, 0.99) / 1_000_000.0,
          elapsed[elapsed.length - 1] / 1_000_000.0);
      if (success > 0) {
        tvmAnySuccessMax = pairCount;
      }
      if (timedOut == 0) {
        tvmAllSuccessMax = pairCount;
      }
    }
    System.out.println("BN128_TVM_RESULTS_END");
    System.out.printf(Locale.ROOT,
        "summary timeoutMs=%d directP50MaxPairs=%d directP99MaxPairs=%d "
            + "tvmAnySuccessMaxPairs=%d tvmAllSuccessMaxPairs=%d%n",
        timeoutMs, directP50Max, directP99Max, tvmAnySuccessMax, tvmAllSuccessMax);
    System.out.println("BN128_BENCHMARK_END");
  }

  private static long warmupDirect(BN128Pairing pairing, byte[][] inputs, int maxPairs,
      int seconds, int minimumIterations) {
    long deadline = System.nanoTime() + seconds * 1_000_000_000L;
    long iterations = 0;
    while (iterations < minimumIterations || System.nanoTime() < deadline) {
      int pairCount = 1 + (int) (iterations % maxPairs);
      Pair<Boolean, byte[]> result = pairing.execute(inputs[pairCount]);
      assertTrue("BN128 warmup rejected valid input", result.getLeft());
      iterations++;
    }
    return iterations;
  }

  private static long warmupTvm(JumpTable jumpTable, int maxPairs, int seconds,
      int minimumIterations) throws Exception {
    long deadline = System.nanoTime() + seconds * 1_000_000_000L;
    long iterations = 0;
    while (iterations < minimumIterations || System.nanoTime() < deadline) {
      int pairCount = 1 + (int) (iterations % maxPairs);
      TvmSample sample = runInTvm(pairCount, 10_000L, jumpTable);
      assertTrue("TVM warmup failed with a generous deadline", sample.success);
      iterations++;
    }
    return iterations;
  }

  private static TvmSample runInTvm(int pairCount, long timeoutMs, JumpTable jumpTable)
      throws Exception {
    byte[] input = inputForPairs(pairCount);
    byte[] code = callPairingPrecompileCode(input.length);
    final long[] vmTimesInUs = new long[2];
    ProgramInvokeMockImpl invoke = new ProgramInvokeMockImpl(input) {
      @Override
      public long getVmStartInUs() {
        return vmTimesInUs[0];
      }

      @Override
      public long getVmShouldEndInUs() {
        return vmTimesInUs[1];
      }
    };
    invoke.setEnergyLimit(15_000_000L);
    Program program = new Program(
        code,
        code,
        invoke,
        new InternalTransaction(
            Protocol.Transaction.getDefaultInstance(),
            InternalTransaction.TrxType.TRX_UNKNOWN_TYPE));
    program.setRootTransactionId(new byte[32]);

    long startNanos = System.nanoTime();
    vmTimesInUs[0] = startNanos / 1_000L;
    vmTimesInUs[1] = vmTimesInUs[0] + timeoutMs * 1_000L;
    boolean success;
    try {
      VM.play(program, jumpTable);
      RuntimeException exception = program.getResult().getException();
      if (exception != null && !(exception instanceof OutOfTimeException)) {
        throw new AssertionError("Unexpected TVM exception", exception);
      }
      success = !(exception instanceof OutOfTimeException);
    } catch (OutOfTimeException e) {
      success = false;
    }
    return new TvmSample(success, System.nanoTime() - startNanos);
  }

  private static void printLatency(String path, int pairCount, int samples, int overDeadline,
      long energy, long[] elapsed) {
    System.out.printf(Locale.ROOT,
        "path=%s pairs=%d inputBytes=%d energyIstanbul=%d samples=%d overDeadline=%d "
            + "avgMs=%.3f minMs=%.3f p50Ms=%.3f p90Ms=%.3f p95Ms=%.3f "
            + "p99Ms=%.3f maxMs=%.3f%n",
        path, pairCount, pairCount * PAIR_SIZE, energy, samples, overDeadline,
        average(elapsed) / 1_000_000.0,
        elapsed[0] / 1_000_000.0,
        percentile(elapsed, 0.50) / 1_000_000.0,
        percentile(elapsed, 0.90) / 1_000_000.0,
        percentile(elapsed, 0.95) / 1_000_000.0,
        percentile(elapsed, 0.99) / 1_000_000.0,
        elapsed[elapsed.length - 1] / 1_000_000.0);
  }

  private static long percentile(long[] sortedValues, double percentile) {
    int index = (int) Math.ceil(sortedValues.length * percentile) - 1;
    return sortedValues[Math.max(0, Math.min(index, sortedValues.length - 1))];
  }

  private static double average(long[] values) {
    double total = 0;
    for (long value : values) {
      total += value;
    }
    return total / values.length;
  }

  private static byte[] inputForPairs(int count) {
    byte[] onePair = new byte[PAIR_SIZE];
    for (int i = 0; i < GENERATOR_PAIR.length; i++) {
      byte[] value = new BigInteger(GENERATOR_PAIR[i]).toByteArray();
      int sourceOffset = value.length > WORD_SIZE ? value.length - WORD_SIZE : 0;
      int length = Math.min(value.length, WORD_SIZE);
      System.arraycopy(value, sourceOffset, onePair, (i + 1) * WORD_SIZE - length, length);
    }

    byte[] input = new byte[count * PAIR_SIZE];
    for (int i = 0; i < count; i++) {
      System.arraycopy(onePair, 0, input, i * PAIR_SIZE, PAIR_SIZE);
    }
    return input;
  }

  private static byte[] callPairingPrecompileCode(int inputLength) {
    ByteArrayOutputStream code = new ByteArrayOutputStream();
    push2(code, inputLength);
    push1(code, 0);
    push1(code, 0);
    code.write(Op.CALLDATACOPY);

    push1(code, WORD_SIZE);
    push1(code, 0);
    push2(code, inputLength);
    push1(code, 0);
    push1(code, 0);
    push1(code, 8);
    code.write(Op.PUSH3);
    code.write(0xe4);
    code.write(0xe1);
    code.write(0xc0);
    code.write(Op.CALL);
    code.write(Op.POP);
    code.write(Op.STOP);
    return code.toByteArray();
  }

  private static void push1(ByteArrayOutputStream code, int value) {
    code.write(Op.PUSH1);
    code.write(value);
  }

  private static void push2(ByteArrayOutputStream code, int value) {
    code.write(Op.PUSH2);
    code.write(value >>> 8);
    code.write(value);
  }

  private static boolean envBoolean(String name, boolean defaultValue) {
    String value = System.getenv(name);
    return value == null ? defaultValue : Boolean.parseBoolean(value);
  }

  private static int envInt(String name, int defaultValue, int min, int max) {
    String value = System.getenv(name);
    if (value == null || value.trim().isEmpty()) {
      return defaultValue;
    }
    int parsed = Integer.parseInt(value.trim());
    if (parsed < min || parsed > max) {
      throw new IllegalArgumentException(
          name + " must be in [" + min + ", " + max + "], got " + parsed);
    }
    return parsed;
  }

  private static void printEnvironment() {
    Runtime runtime = Runtime.getRuntime();
    System.out.printf(Locale.ROOT,
        "environment os=%s osVersion=%s arch=%s javaVendor=%s javaVersion=%s "
            + "vm=%s processors=%d maxHeapMiB=%d cpu=%s%n",
        System.getProperty("os.name"),
        System.getProperty("os.version"),
        System.getProperty("os.arch"),
        System.getProperty("java.vendor"),
        System.getProperty("java.version"),
        System.getProperty("java.vm.name"),
        runtime.availableProcessors(),
        runtime.maxMemory() / 1024 / 1024,
        readLinuxCpuModel());
  }

  private static String readLinuxCpuModel() {
    Path cpuInfo = Paths.get("/proc/cpuinfo");
    if (!Files.isReadable(cpuInfo)) {
      return "unknown";
    }
    try {
      List<String> lines = Files.readAllLines(cpuInfo);
      for (String line : lines) {
        if (line.startsWith("model name") || line.startsWith("Hardware")) {
          int separator = line.indexOf(':');
          if (separator >= 0) {
            return line.substring(separator + 1).trim().replace(' ', '_');
          }
        }
      }
    } catch (IOException ignored) {
      // Environment metadata is best-effort and must not fail the benchmark.
    }
    return "unknown";
  }

  private static final class TvmSample {

    private final boolean success;
    private final long elapsedNanos;

    private TvmSample(boolean success, long elapsedNanos) {
      this.success = success;
      this.elapsedNanos = elapsedNanos;
    }
  }
}
