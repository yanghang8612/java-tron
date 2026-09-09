package org.tron.common.crypto.zksnark.optimized;

/** Monotonic execution deadline, in microseconds, shared with the VM. */
public final class ExecutionDeadline {

  private ExecutionDeadline() {
  }

  public static boolean isExceeded(long deadlineInUs) {
    return System.nanoTime() / 1000 > deadlineInUs;
  }
}
