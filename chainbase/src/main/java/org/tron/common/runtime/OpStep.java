package org.tron.common.runtime;

import java.util.List;
import lombok.Getter;

@Getter
public class OpStep {

  private final int pc;
  private final String op;
  private final int depth;
  private final long energy;
  // Operands of this instruction, top-of-stack first. Bytes are raw 32-byte words.
  private final List<byte[]> stack;

  public OpStep(int pc, String op, int depth, long energy, List<byte[]> stack) {
    this.pc = pc;
    this.op = op;
    this.depth = depth;
    this.energy = energy;
    this.stack = stack;
  }
}
