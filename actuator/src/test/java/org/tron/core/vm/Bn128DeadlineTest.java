package org.tron.core.vm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.tron.common.crypto.zksnark.optimized.ExecutionDeadline;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.runtime.InternalTransaction;
import org.tron.common.runtime.vm.DataWord;
import org.tron.common.utils.ByteArray;
import org.tron.core.vm.config.VMConfig;
import org.tron.core.vm.program.Program;
import org.tron.core.vm.program.Program.OutOfTimeException;
import org.tron.core.vm.program.invoke.ProgramInvokeMockImpl;
import org.tron.protos.Protocol;

public class Bn128DeadlineTest {

  private static final long DEADLINE = 1_234_567L;

  private boolean debug;
  private boolean solidity;

  @Before
  public void setUp() {
    CommonParameter params = CommonParameter.getInstance();
    debug = params.isDebug();
    solidity = params.isSolidityNode();
    params.setDebug(false);
    params.setSolidityNode(false);
    select(true);
  }

  @After
  public void tearDown() {
    VMConfig.clearLocalSnapshot();
    CommonParameter.getInstance().setDebug(debug);
    CommonParameter.getInstance().setSolidityNode(solidity);
  }

  private static void select(boolean enabled) {
    VMConfig.Snapshot snapshot = new VMConfig.Snapshot();
    snapshot.allowOptimizeTvm = enabled;
    VMConfig.setLocalSnapshot(snapshot);
  }

  @Test
  public void onlyOptimizedLoopsReturnFailureOnExpiredDeadline() {
    PrecompiledContracts.PrecompiledContract[] contracts = {
        new PrecompiledContracts.BN128Multiplication(), new PrecompiledContracts.BN128Pairing()
    };
    byte[][] inputs = {scalarInput(), new byte[192]};
    for (int i = 0; i < contracts.length; i++) {
      PrecompiledContracts.PrecompiledContract contract = contracts[i];
      contract.setVmShouldEndInUs(Long.MIN_VALUE);
      select(true);
      assertFailure(contract.execute(inputs[i]));
      contract.setConstantCall(true);
      assertFailure(contract.execute(inputs[i]));
      contract.setVmShouldEndInUs(Long.MAX_VALUE);
      assertTrue(contract.execute(inputs[i]).getLeft());
      contract.setVmShouldEndInUs(Long.MIN_VALUE);
      select(false);
      assertTrue(contract.execute(inputs[i]).getLeft());
    }
  }

  @Test
  public void deadlineDoesNotDependOnNodeMode() {
    PrecompiledContracts.BN128Pairing contract = new PrecompiledContracts.BN128Pairing();
    contract.setVmShouldEndInUs(Long.MIN_VALUE);
    CommonParameter.getInstance().setDebug(true);
    assertFailure(contract.execute(new byte[192]));
    CommonParameter.getInstance().setDebug(false);
    CommonParameter.getInstance().setSolidityNode(true);
    assertFailure(contract.execute(new byte[192]));
  }

  @Test
  public void propagatesInterruptionFromDecodeAndScalarLoopsWithoutThrowing() {
    // Infinity pairs still have to be decoded and validated.
    try (MockedStatic<ExecutionDeadline> deadline = expireAt(20)) {
      assertFailure(OptimizedBN128.pairing(new byte[192 * 64], DEADLINE));
      deadline.verify(() -> ExecutionDeadline.isExceeded(DEADLINE), times(20));
    }
    try (MockedStatic<ExecutionDeadline> deadline = expireAt(20)) {
      assertFailure(OptimizedBN128.multiply(scalarInput(), DEADLINE));
      deadline.verify(() -> ExecutionDeadline.isExceeded(DEADLINE), times(20));
    }
    assertTrue(OptimizedBN128.multiply(scalarInput(), Long.MAX_VALUE).getLeft());
    assertTrue(OptimizedBN128.pairing(new byte[192], Long.MAX_VALUE).getLeft());
  }

  @Test
  public void convertsSubgroupMillerAndFinalExponentInterruptionToPrecompileFailure()
      throws Exception {
    byte[] input;
    try (InputStream vectors = getClass().getResourceAsStream("/bn128/bn256Pairing.json")) {
      JsonNode first = new ObjectMapper().readTree(vectors).get(0);
      input = Arrays.copyOf(ByteArray.fromHexString(first.get("Input").asText()), 192);
    }
    AtomicInteger total = new AtomicInteger();
    Pair<Boolean, byte[]> expected;
    try (MockedStatic<ExecutionDeadline> deadline = mockStatic(ExecutionDeadline.class)) {
      deadline.when(() -> ExecutionDeadline.isExceeded(DEADLINE)).thenAnswer(invocation -> {
        total.incrementAndGet();
        return false;
      });
      expected = OptimizedBN128.pairing(input, DEADLINE);
    }
    assertTrue(expected.getLeft());
    // 1 decode + 47 subgroup checks; then Miller, followed by final exponentiation.
    for (int checkpoint : new int[]{20, 49, 60, total.get() - 5, total.get()}) {
      try (MockedStatic<ExecutionDeadline> deadline = expireAt(checkpoint)) {
        assertFailure(OptimizedBN128.pairing(input, DEADLINE));
        deadline.verify(() -> ExecutionDeadline.isExceeded(DEADLINE), times(checkpoint));
      }
    }
    assertArrayEquals(expected.getRight(),
        OptimizedBN128.pairing(input, Long.MAX_VALUE).getRight());
  }

  @Test
  public void loopFreeOperationsDoNotCheckEntryOrExit() {
    PrecompiledContracts.BN128Addition add = new PrecompiledContracts.BN128Addition();
    add.setVmShouldEndInUs(Long.MIN_VALUE);
    try (MockedStatic<ExecutionDeadline> deadline = expireAt(1)) {
      assertTrue(add.execute(Arrays.copyOf(scalarInput(), 64)).getLeft());
      assertArrayEquals(new byte[64], OptimizedBN128.multiply(null, DEADLINE).getRight());
      assertArrayEquals(DataWord.ONE().getData(),
          OptimizedBN128.pairing(null, DEADLINE).getRight());
      assertFailure(OptimizedBN128.pairing(new byte[1], DEADLINE));
      deadline.verifyNoInteractions();
    }
    try (MockedStatic<ExecutionDeadline> deadline = expireAt(3)) {
      // One decoder iteration and one infinity scan, with no entry/exit checks.
      Pair<Boolean, byte[]> result = OptimizedBN128.pairing(new byte[192], DEADLINE);
      assertTrue(result.getLeft());
      assertArrayEquals(DataWord.ONE().getData(), result.getRight());
      deadline.verify(() -> ExecutionDeadline.isExceeded(DEADLINE), times(2));
    }
  }

  private static byte[] scalarInput() {
    byte[] scalar = new byte[96];
    scalar[31] = 1;
    scalar[63] = 2;
    scalar[64] = (byte) 0x80;
    return scalar;
  }

  private static void assertFailure(Pair<Boolean, byte[]> result) {
    assertFalse(result.getLeft());
    assertArrayEquals(new byte[0], result.getRight());
  }

  private static MockedStatic<ExecutionDeadline> expireAt(int limit) {
    AtomicInteger checks = new AtomicInteger();
    MockedStatic<ExecutionDeadline> deadline = mockStatic(ExecutionDeadline.class);
    deadline.when(() -> ExecutionDeadline.isExceeded(anyLong())).thenAnswer(invocation -> {
      assertEquals(DEADLINE, (long) invocation.getArgument(0));
      return checks.incrementAndGet() >= limit;
    });
    return deadline;
  }

  @Test
  public void interruptedPrecompileMakesActualTvmCallFailWithoutAnInnerException() throws Exception {
    Program program = pairingProgram(new AtomicLong(DEADLINE));
    JumpTable table = OperationRegistry.prepareAndGetTable(false);
    // Disable only the VM's outer check so the precompile's failure path can be observed.
    CommonParameter.getInstance().setDebug(true);
    try (MockedStatic<ExecutionDeadline> deadline = expireAt(1)) {
      VM.play(program, table);
      assertTrue(program.getStack().peek().isZero());
      assertNull(program.getResult().getException());
      deadline.verify(() -> ExecutionDeadline.isExceeded(DEADLINE));
    }
  }

  @Test
  public void outerVmStillRejectsAnExpiredTransactionAfterPrecompileFailure() throws Exception {
    AtomicLong endInUs = new AtomicLong(Long.MAX_VALUE);
    Program program = pairingProgram(endInUs);
    JumpTable table = OperationRegistry.prepareAndGetTable(false);
    try (MockedStatic<ExecutionDeadline> deadline = mockStatic(ExecutionDeadline.class)) {
      deadline.when(() -> ExecutionDeadline.isExceeded(Long.MAX_VALUE)).thenAnswer(invocation -> {
        endInUs.set(Long.MIN_VALUE);
        return true;
      });
      assertThrows(OutOfTimeException.class, () -> VM.play(program, table));
      assertTrue(program.getStack().peek().isZero());
      deadline.verify(() -> ExecutionDeadline.isExceeded(Long.MAX_VALUE));
    }
  }

  private static Program pairingProgram(AtomicLong endInUs) throws Exception {
    VMConfig.Snapshot snapshot = new VMConfig.Snapshot();
    snapshot.allowOptimizeTvm = true;
    snapshot.allowTvmIstanbul = true;
    VMConfig.setLocalSnapshot(snapshot);
    // Copy 192 input bytes to memory, CALL precompile 8 with a 32-byte output, then STOP.
    byte[] code = ByteArray.fromHexString("60c060006000376020600060c06000600060086305f5e100f100");
    ProgramInvokeMockImpl invoke = new ProgramInvokeMockImpl(new byte[192]) {
      @Override
      public long getVmShouldEndInUs() {
        return endInUs.get();
      }
    };
    invoke.setEnergyLimit(100_000_000L);
    Program program = new Program(code, code, invoke,
        new InternalTransaction(Protocol.Transaction.getDefaultInstance(),
            InternalTransaction.TrxType.TRX_UNKNOWN_TYPE));
    program.setRootTransactionId(new byte[32]);
    return program;
  }
}
