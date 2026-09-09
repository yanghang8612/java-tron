package org.tron.common.crypto.zksnark;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/** Standalone, forkable benchmark. Use scripts/bn128_compare.py to compare identical JVMs. */
public final class Bn128Benchmark {

  private static volatile int blackhole;

  private Bn128Benchmark() {
  }

  public static void main(String[] args) throws Exception {
    String engine = args[0];
    int[] counts = Arrays.stream(args[1].split(",")).mapToInt(Integer::parseInt).toArray();
    int samples = Integer.parseInt(args[2]);
    int warmupSeconds = Integer.parseInt(args[3]);
    int minimumWarmup = Integer.parseInt(args[4]);
    String scenario = args[5];
    long seed = Long.parseLong(args[6]);
    long timeoutMs = Long.parseLong(args[7]);
    System.out.printf(Locale.ROOT,
        "ENV engine=%s java=%s vendor=%s arch=%s os=%s processors=%d heapMiB=%d%n",
        engine, System.getProperty("java.version"), System.getProperty("java.vendor"),
        System.getProperty("os.arch"), System.getProperty("os.name"),
        Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory() / 1048576);
    byte[][] fixtures = Bn128TestSupport.fixtures(counts[counts.length - 1] + 8);
    byte[][][] inputs = new byte[counts.length][8][];
    int[][] expected = new int[counts.length][8];
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    for (int i = 0; i < counts.length; i++) {
      for (int j = 0; j < 8; j++) {
        inputs[i][j] = Bn128TestSupport.input(fixtures, counts[i], j, scenario);
        digest.update(inputs[i][j]);
        expected[i][j] = Bn128TestSupport.execute(inputs[i][j]);
        if (expected[i][j] < 0) {
          throw new AssertionError("Invalid benchmark fixture");
        }
      }
    }
    StringBuilder sha = new StringBuilder();
    for (byte b : digest.digest()) {
      sha.append(String.format(Locale.ROOT, "%02x", b & 255));
    }
    System.out.println("INPUT_SHA256 " + sha);
    Operation operation = "core".equals(engine) ? new CoreOperation()
        : new TvmOperation(engine);
    int warmCount = 0;
    while (warmCount + 1 < counts.length && counts[warmCount + 1] <= 4) {
      warmCount++;
    }
    long start = System.nanoTime();
    long iterations = 0;
    long lastProgress = start;
    do {
      int i = (int) (iterations % (warmCount + 1));
      int j = (int) (iterations % 8);
      long[] result = operation.sample(inputs[i][j], expected[i][j], 60000);
      if (result[2] != 0) {
        throw new AssertionError("Warmup timed out");
      }
      iterations++;
      long now = System.nanoTime();
      if (now - lastProgress >= 10_000_000_000L) {
        System.out.println("PROGRESS warmupCalls=" + iterations);
        lastProgress = now;
      }
    } while (iterations < minimumWarmup
        || System.nanoTime() - start < warmupSeconds * 1_000_000_000L);
    System.out.printf(Locale.ROOT, "WARMUP calls=%d seconds=%.3f%n", iterations,
        (System.nanoTime() - start) / 1e9);
    // Exercise each size after the timed warmup, without including these calls in results.
    for (int i = 0; i < counts.length; i++) {
      for (int j = 0; j < 3; j++) {
        operation.sample(inputs[i][j], expected[i][j], 60000);
      }
    }
    com.sun.management.ThreadMXBean memory = allocationBean();
    long[][] elapsed = new long[counts.length][samples];
    int[][] timedOut = new int[counts.length][samples];
    long[] allocated = new long[counts.length];
    long gcBefore = gcMillis();
    int[] order = new int[counts.length];
    for (int i = 0; i < counts.length; i++) {
      order[i] = i;
    }
    Random random = new Random(seed);
    lastProgress = System.nanoTime();
    for (int sample = 0; sample < samples; sample++) {
      for (int i = order.length - 1; i > 0; i--) {
        int j = random.nextInt(i + 1);
        int tmp = order[i];
        order[i] = order[j];
        order[j] = tmp;
      }
      for (int i : order) {
        int j = sample % 8;
        long before = allocatedBytes(memory);
        long[] result = operation.sample(inputs[i][j], expected[i][j], timeoutMs);
        allocated[i] += allocatedBytes(memory) - before;
        elapsed[i][sample] = result[0];
        timedOut[i][sample] = (int) result[2];
      }
      if (System.nanoTime() - lastProgress >= 10_000_000_000L) {
        System.out.printf(Locale.ROOT, "PROGRESS measuredRounds=%d/%d%n", sample + 1, samples);
        lastProgress = System.nanoTime();
      }
    }
    for (int i = 0; i < counts.length; i++) {
      long[] sorted = elapsed[i].clone();
      Arrays.sort(sorted);
      int over = 0;
      int timeouts = 0;
      double total = 0;
      for (int j = 0; j < samples; j++) {
        total += elapsed[i][j];
        if (elapsed[i][j] > timeoutMs * 1_000_000L || timedOut[i][j] != 0) {
          over++;
        }
        timeouts += timedOut[i][j];
      }
      System.out.printf(Locale.ROOT,
          "RESULT pairs=%d samples=%d meanMs=%.6f p50Ms=%.6f p95Ms=%.6f "
              + "p99Ms=%.6f maxMs=%.6f overDeadline=%d timeouts=%d allocBytes=%.0f%n",
          counts[i], samples, total / samples / 1e6, percentile(sorted, 0.50) / 1e6,
          percentile(sorted, 0.95) / 1e6, percentile(sorted, 0.99) / 1e6,
          sorted[samples - 1] / 1e6, over, timeouts,
          memory == null ? -1 : allocated[i] / (double) samples);
      System.out.println("RAW " + counts[i] + " " + Arrays.toString(elapsed[i]));
      System.out.println("TIMEOUTS " + counts[i] + " " + Arrays.toString(timedOut[i]));
    }
    System.out.println("GC measuredMillis=" + (gcMillis() - gcBefore));
  }

  private static long percentile(long[] values, double fraction) {
    return values[(int) Math.ceil(values.length * fraction) - 1];
  }

  private static com.sun.management.ThreadMXBean allocationBean() {
    java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
    if (!(bean instanceof com.sun.management.ThreadMXBean)) {
      return null;
    }
    com.sun.management.ThreadMXBean result = (com.sun.management.ThreadMXBean) bean;
    if (!result.isThreadAllocatedMemorySupported()) {
      return null;
    }
    if (!result.isThreadAllocatedMemoryEnabled()) {
      result.setThreadAllocatedMemoryEnabled(true);
    }
    return result;
  }

  private static long allocatedBytes(com.sun.management.ThreadMXBean bean) {
    return bean == null ? 0 : bean.getThreadAllocatedBytes(Thread.currentThread().getId());
  }

  private static long gcMillis() {
    long total = 0;
    for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
      total += Math.max(0, gc.getCollectionTime());
    }
    return total;
  }

  private interface Operation {
    long[] sample(byte[] input, int expected, long timeoutMs) throws Exception;
  }

  private static final class CoreOperation implements Operation {
    @Override
    public long[] sample(byte[] input, int expected, long timeoutMs) {
      long start = System.nanoTime();
      int result = Bn128TestSupport.execute(input);
      long nanos = System.nanoTime() - start;
      if (result != expected) {
        throw new AssertionError("Pairing result mismatch");
      }
      blackhole = result;
      return new long[]{nanos, nanos <= timeoutMs * 1_000_000L ? 1 : 0, 0};
    }
  }

  private static final class TvmOperation implements Operation {
    private final Object target;
    private final Method sample;

    private TvmOperation(String engine) throws Exception {
      Class<?> type = Class.forName("org.tron.core.vm.Bn128TvmBenchmark");
      target = type.getConstructor(String.class).newInstance(engine);
      sample = type.getMethod("sample", byte[].class, int.class, long.class);
    }

    @Override
    public long[] sample(byte[] input, int expected, long timeoutMs) throws Exception {
      return (long[]) sample.invoke(target, input, expected, timeoutMs);
    }
  }
}
