package org.tron.core.vm.program;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Assume;
import org.junit.Test;

/**
 * Retained heap of {@link ProgramPrecompile#compile} for init code that is entirely JUMPDEST.
 *
 * <p>Skipped unless {@code -PjumpDestHeapMeasure} is set. That flag raises this test worker to a
 * 20 GiB serial-GC heap. Default {@code :actuator:test} stays on the 1200m heap and skips this.
 */
public class JumpDestCacheHeapTest {

  private static final int CODE_BYTES = 3 * 1024 * 1024;
  private static final int ENTRIES = 100;
  private static final byte JUMPDEST = 0x5b;

  @Test
  public void hundredFullJumpDestAnalysesStayReachable() throws Exception {
    Assume.assumeTrue(
        "pass -PjumpDestHeapMeasure; default test heap is 1200m and cannot hold 100 entries",
        Boolean.getBoolean("jumpdest.heap.measure"));

    byte[] code = jumpDestCode();
    ProgramPrecompile warmup = ProgramPrecompile.compile(code);
    assertEquals(CODE_BYTES, jumpDestCount(warmup));
    warmup = null;
    fullGc();
    long baseline = usedHeap();

    List<ProgramPrecompile> held = new ArrayList<>(ENTRIES);
    long previous = baseline;
    for (int n = 1; n <= ENTRIES; n++) {
      ProgramPrecompile analyzed = ProgramPrecompile.compile(code);
      assertEquals(CODE_BYTES, jumpDestCount(analyzed));
      held.add(analyzed);
      if (n % 10 == 0) {
        fullGc();
        long live = usedHeap();
        System.out.println(
            "n=" + n
                + " retainedBytes=" + (live - baseline)
                + " stepBytes=" + (live - previous)
                + " held=" + held.size());
        previous = live;
      }
    }
    code = null;
    fullGc();
    long retained = usedHeap() - baseline;
    long perEntry = retained / ENTRIES;
    System.out.println(
        "JUMPDEST_HEAP entries=" + ENTRIES
            + " codeBytes=" + CODE_BYTES
            + " jumpDests=" + CODE_BYTES
            + " retainedBytes=" + retained
            + " perEntryBytes=" + perEntry
            + " held=" + held.size());
    assertEquals(ENTRIES, held.size());
    // One entry is about 160 MiB with compressed oops. Well under that means the sets were freed.
    assertTrue("retainedBytes=" + retained, retained > ENTRIES * 80L * 1024 * 1024);
  }

  private static byte[] jumpDestCode() {
    byte[] code = new byte[CODE_BYTES];
    for (int i = 0; i < code.length; i++) {
      code[i] = JUMPDEST;
    }
    return code;
  }

  private static int jumpDestCount(ProgramPrecompile precompile) throws Exception {
    Field field = ProgramPrecompile.class.getDeclaredField("jumpDest");
    field.setAccessible(true);
    return ((Set<?>) field.get(precompile)).size();
  }

  private static long usedHeap() {
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }

  private static void fullGc() throws InterruptedException {
    for (int i = 0; i < 2; i++) {
      System.gc();
      Thread.sleep(200);
    }
  }
}
