package org.tron.core.vm;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.tron.core.vm.config.VMConfig;

public class OperationRegistryTest {

  private boolean previousHigherCpuLimit;

  @Before
  public void setUp() {
    previousHigherCpuLimit = VMConfig.allowHigherLimitForMaxCpuTimeOfOneTx();
  }

  @After
  public void tearDown() {
    OperationRegistry.endExecution(true);
    VMConfig.initAllowHigherLimitForMaxCpuTimeOfOneTx(previousHigherCpuLimit ? 1 : 0);
  }

  @Test
  public void constantExecutionUsesPrivateReusableTable() {
    JumpTable shared = OperationRegistry.getTable(false);
    JumpTable constant = OperationRegistry.beginExecution(true);

    assertNotSame(shared, constant);
    assertSame(constant, OperationRegistry.getTable(true));
    assertSame(shared, OperationRegistry.getTable(false));
  }

  @Test
  public void nonConstantExecutionAlwaysUsesSharedTable() {
    JumpTable shared = OperationRegistry.getTable(false);

    assertSame(shared, OperationRegistry.beginExecution(false));
    assertSame(shared, OperationRegistry.getTable(false));
  }

  @Test(expected = IllegalStateException.class)
  public void constantTableIsUnavailableAfterExecution() {
    OperationRegistry.beginExecution(true);
    OperationRegistry.endExecution(true);

    OperationRegistry.getTable(true);
  }

  @Test
  public void consecutiveConstantExecutionsUseDifferentTables() {
    JumpTable first = OperationRegistry.beginExecution(true);
    OperationRegistry.endExecution(true);
    JumpTable second = OperationRegistry.beginExecution(true);

    assertNotSame(first, second);
  }

  @Test
  public void nonConstantExecutionAfterConstantExecutionUsesSharedTable() {
    JumpTable shared = OperationRegistry.getTable(false);
    JumpTable constant = OperationRegistry.beginExecution(true);

    try {
      assertNotSame(constant, shared);
      // A stale constant-call table must not affect a non-constant execution on the same thread.
      assertSame(shared, OperationRegistry.beginExecution(false));
      assertSame(shared, OperationRegistry.getTable(false));
      assertSame(constant, OperationRegistry.getTable(true));
    } finally {
      OperationRegistry.endExecution(true);
    }
  }

  @Test
  public void constantAdjustmentsDoNotMutateSharedTable() {
    JumpTable shared = OperationRegistry.getTable(false);
    Operation sharedMload = shared.get(Op.MLOAD);
    VMConfig.initAllowHigherLimitForMaxCpuTimeOfOneTx(1);

    JumpTable constant = OperationRegistry.beginExecution(true);

    assertSame(sharedMload, shared.get(Op.MLOAD));
    assertNotSame(sharedMload, constant.get(Op.MLOAD));
    assertSame(constant.get(Op.MLOAD), OperationRegistry.getTable(true).get(Op.MLOAD));
  }
}
