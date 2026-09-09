package org.tron.core.vm;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.LoggerFactory;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.runtime.InternalTransaction;
import org.tron.common.runtime.vm.DataWord;
import org.tron.core.vm.config.VMConfig;
import org.tron.core.vm.program.Program;
import org.tron.core.vm.program.Program.OutOfTimeException;
import org.tron.core.vm.program.invoke.ProgramInvokeMockImpl;
import org.tron.protos.Protocol;

/** Adapter used only by the standalone, forked BN128 benchmark (no coverage agent). */
public final class Bn128TvmBenchmark {

  private final boolean tvm;
  private final PrecompiledContracts.PrecompiledContract addMul;
  private final PrecompiledContracts.BN128Pairing precompile =
      new PrecompiledContracts.BN128Pairing();
  private final JumpTable table;

  public Bn128TvmBenchmark(String engine) {
    tvm = "tvm".equals(engine);
    addMul = "add".equals(engine) ? new PrecompiledContracts.BN128Addition()
        : "mul".equals(engine) ? new PrecompiledContracts.BN128Multiplication() : null;
    VMConfig.initAllowTvmIstanbul(1);
    CommonParameter.getInstance().setDebug(false);
    ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).setLevel(Level.ERROR);
    ((Logger) LoggerFactory.getLogger("VM")).setLevel(Level.ERROR);
    table = OperationRegistry.prepareAndGetTable(false);
  }

  public long[] sample(byte[] input, int expected, long timeoutMs) throws Exception {
    if (addMul != null) {
      byte[] callInput = Arrays.copyOf(input, input.length - 64);
      byte[] output = Arrays.copyOfRange(input, input.length - 64, input.length);
      long start = System.nanoTime();
      Pair<Boolean, byte[]> result = addMul.execute(callInput);
      long nanos = System.nanoTime() - start;
      if (!result.getLeft() || !Arrays.equals(output, result.getRight())) {
        throw new AssertionError("Add/Mul result mismatch");
      }
      return new long[]{nanos, nanos <= timeoutMs * 1_000_000L ? 1 : 0, 0};
    }
    if (!tvm) {
      long start = System.nanoTime();
      Pair<Boolean, byte[]> result = precompile.execute(input);
      long nanos = System.nanoTime() - start;
      if (!result.getLeft() || !Arrays.equals(new DataWord(expected).getData(), result.getRight())) {
        throw new AssertionError("Precompile returned incorrect success flag or bytes");
      }
      return new long[]{nanos, nanos <= timeoutMs * 1_000_000L ? 1 : 0, 0};
    }
    byte[] code = callCode(input.length);
    final long[] times = new long[2];
    ProgramInvokeMockImpl invoke = new ProgramInvokeMockImpl(input) {
      @Override
      public long getVmStartInUs() {
        return times[0];
      }

      @Override
      public long getVmShouldEndInUs() {
        return times[1];
      }
    };
    invoke.setEnergyLimit(100_000_000L);
    Program program = new Program(code, code, invoke,
        new InternalTransaction(Protocol.Transaction.getDefaultInstance(),
            InternalTransaction.TrxType.TRX_UNKNOWN_TYPE));
    program.setRootTransactionId(new byte[32]);
    long start = System.nanoTime();
    times[0] = start / 1000;
    times[1] = times[0] + timeoutMs * 1000;
    try {
      VM.play(program, table);
    } catch (OutOfTimeException e) {
      return new long[]{System.nanoTime() - start, 0, 1};
    }
    long nanos = System.nanoTime() - start;
    if (program.getResult().getException() != null) {
      throw new AssertionError("Unexpected VM failure", program.getResult().getException());
    }
    // The bytecode leaves CALL's success flag on the stack and returns the output word.
    if (program.getStack().size() != 1 || !program.getStack().peek().equals(new DataWord(1))
        || !Arrays.equals(program.getResult().getHReturn(), new DataWord(expected).getData())) {
      throw new AssertionError("CALL failed or pairing return data is incorrect");
    }
    return new long[]{nanos, nanos <= timeoutMs * 1_000_000L ? 1 : 0, 0};
  }

  private static byte[] callCode(int length) {
    ByteArrayOutputStream code = new ByteArrayOutputStream();
    push3(code, length);
    push3(code, 0);
    push3(code, 0);
    code.write(Op.CALLDATACOPY);
    push3(code, 32);
    push3(code, 0);
    push3(code, length);
    push3(code, 0);
    push3(code, 0);
    push3(code, 8);
    // Sufficient forwarded energy for up to 256 pairs, including pre-Istanbul pricing.
    code.write(Op.PUSH4);
    code.write(0x05);
    code.write(0xf5);
    code.write(0xe1);
    code.write(0x00);
    code.write(Op.CALL);
    push3(code, 32);
    push3(code, 0);
    code.write(Op.RETURN);
    return code.toByteArray();
  }

  private static void push3(ByteArrayOutputStream code, int value) {
    code.write(Op.PUSH3);
    code.write(value >>> 16);
    code.write(value >>> 8);
    code.write(value);
  }
}
