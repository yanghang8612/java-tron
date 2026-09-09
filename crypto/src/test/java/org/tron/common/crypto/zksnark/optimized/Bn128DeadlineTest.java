package org.tron.common.crypto.zksnark.optimized;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

import java.math.BigInteger;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.mockito.MockedStatic;

/** Deterministic cancellation inside the arithmetic, independent of machine speed. */
public class Bn128DeadlineTest {

  private static final long DEADLINE = 1_234_567L;

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
  public void comparesMonotonicDeadlineWithoutConvertingItToNanoseconds() {
    assertTrue(ExecutionDeadline.isExceeded(Long.MIN_VALUE));
    assertFalse(ExecutionDeadline.isExceeded(Long.MAX_VALUE));
    assertTrue(ExecutionDeadline.isExceeded(System.nanoTime() / 1000 - 1_000_000));
  }

  @Test
  public void interruptsScalarAndEverySubgroupDoublingLoop() {
    BN128G1 p = Bn128TestSupport.g1();
    BN128G2 q = Bn128TestSupport.g2();
    for (int checkpoint : new int[]{1, 20, 256}) {
      try (MockedStatic<ExecutionDeadline> deadline = expireAt(checkpoint)) {
        assertNull(p.mul(BigInteger.ONE.shiftLeft(255), DEADLINE));
        deadline.verify(() -> ExecutionDeadline.isExceeded(DEADLINE), times(checkpoint));
      }
    }
    // The fixed-seed chain has consecutive loops of 17, 14 and 16 doublings.
    for (int checkpoint : new int[]{1, 17, 18, 31, 32, 47}) {
      try (MockedStatic<ExecutionDeadline> deadline = expireAt(checkpoint)) {
        assertNull(BN128G2.create(q.x.a.bytes(), q.x.b.bytes(), q.y.a.bytes(), q.y.b.bytes(),
            DEADLINE));
        deadline.verify(() -> ExecutionDeadline.isExceeded(DEADLINE), times(checkpoint));
      }
    }
  }

  @Test
  public void interruptsEveryMillerStageWithoutExposingAPartialProduct() {
    BN128G1 p = Bn128TestSupport.g1();
    BN128G2 q = Bn128TestSupport.g2();
    int millerBits = FixedExponent.naf(PairingCheck.LOOP_COUNT).length - 1;
    int tail = 16 + millerBits * 9 + 1;
    // Normalization, setup, outer bit, inner pair, Frobenius tail.
    for (int checkpoint : new int[]{1, 9, 17, 22, tail}) {
      PairingCheck check = PairingCheck.create(DEADLINE);
      for (int i = 0; i < 8; i++) {
        check.addPair(p, q);
      }
      try (MockedStatic<ExecutionDeadline> deadline = expireAt(checkpoint)) {
        assertFalse(check.run());
        assertNull(check.product);
        assertEquals(0, check.result());
        assertFalse(check.run());
        deadline.verify(() -> ExecutionDeadline.isExceeded(DEADLINE), times(checkpoint));
      }
    }
  }

  @Test
  public void interruptsEveryFinalExponentiationAndGenericExponentLoop() {
    Fp12 input = Bn128TestSupport.randomFp12(new Random(128));
    int seedBits = FixedExponent.naf(Params.PAIRING_FINAL_EXPONENT_Z).length - 1;
    for (int checkpoint : new int[]{1, 20, seedBits + 1, 2 * seedBits + 1, 3 * seedBits}) {
      try (MockedStatic<ExecutionDeadline> deadline = expireAt(checkpoint)) {
        assertNull(PairingCheck.finalExponentiation(input, DEADLINE));
        deadline.verify(() -> ExecutionDeadline.isExceeded(DEADLINE), times(checkpoint));
      }
    }
    try (MockedStatic<ExecutionDeadline> deadline = expireAt(20)) {
      assertNull(input.negExp(BigInteger.ONE.shiftLeft(255), DEADLINE));
      deadline.verify(() -> ExecutionDeadline.isExceeded(DEADLINE), times(20));
    }
    BN128G1 p = Bn128TestSupport.g1();
    BN128G2 q = Bn128TestSupport.g2();
    int millerChecks = 3 + 2 * (FixedExponent.naf(PairingCheck.LOOP_COUNT).length - 1);
    PairingCheck check = PairingCheck.create(DEADLINE);
    check.addPair(p, q);
    try (MockedStatic<ExecutionDeadline> deadline = expireAt(millerChecks + 20)) {
      assertFalse(check.run());
      assertNull(check.product);
      assertEquals(0, check.result());
      deadline.verify(() -> ExecutionDeadline.isExceeded(DEADLINE), times(millerChecks + 20));
    }
  }

  @Test
  public void checksInfinityScanButNotLoopFreeOperations() {
    BN128G1 p = Bn128TestSupport.g1();
    BN128G1 zero = BN128G1.create(new byte[32], new byte[32]);
    BN128G2 q = Bn128TestSupport.g2();
    PairingCheck empty = PairingCheck.create(DEADLINE);
    try (MockedStatic<ExecutionDeadline> deadline = expireAt(1)) {
      assertTrue(empty.run());
      assertEquals(1, empty.result());
      assertTrue(p.mul(BigInteger.ZERO, DEADLINE).isZero());
      assertTrue(zero.mul(BigInteger.TEN, DEADLINE).isZero());
      deadline.verifyNoInteractions();
    }
    PairingCheck zeros = PairingCheck.create(DEADLINE);
    for (int i = 0; i < 64; i++) {
      zeros.addPair(zero, q);
    }
    try (MockedStatic<ExecutionDeadline> deadline = expireAt(20)) {
      assertFalse(zeros.run());
      assertEquals(0, zeros.result());
      deadline.verify(() -> ExecutionDeadline.isExceeded(DEADLINE), times(20));
    }
  }

  @Test
  public void completedOperationsPreserveResultsAndDoNotCheckAtExit() {
    BN128G1 p = Bn128TestSupport.g1();
    BN128G2 q = Bn128TestSupport.g2();
    PairingCheck checked = PairingCheck.create(DEADLINE);
    PairingCheck unchecked = PairingCheck.create();
    checked.addPair(p, q);
    unchecked.addPair(p, q);
    assertTrue(unchecked.run());
    int millerChecks = 3 + 2 * (FixedExponent.naf(PairingCheck.LOOP_COUNT).length - 1);
    int seedChecks = 3 * (FixedExponent.naf(Params.PAIRING_FINAL_EXPONENT_Z).length - 1);
    int total = millerChecks + seedChecks;
    try (MockedStatic<ExecutionDeadline> deadline = expireAt(total + 1)) {
      assertTrue(checked.run());
      deadline.verify(() -> ExecutionDeadline.isExceeded(DEADLINE), times(total));
    }
    assertEquals(unchecked.product, checked.product);
    BN128<Fp> expected = p.mul(BigInteger.valueOf(7));
    try (MockedStatic<ExecutionDeadline> deadline = expireAt(4)) {
      assertEquals(expected, p.mul(BigInteger.valueOf(7), DEADLINE));
      deadline.verify(() -> ExecutionDeadline.isExceeded(DEADLINE), times(3));
    }
  }
}
