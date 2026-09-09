package org.tron.core.vm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mockStatic;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.tron.common.runtime.vm.DataWord;
import org.tron.core.vm.config.VMConfig;

public class Bn128ProposalGateTest {

  @Test
  public void allThreePrecompilesSwitchOnEveryInvocation() {
    PrecompiledContracts.PrecompiledContract[] contracts = {
        PrecompiledContracts.getContractForAddress(new DataWord(6)),
        PrecompiledContracts.getContractForAddress(new DataWord(7)),
        PrecompiledContracts.getContractForAddress(new DataWord(8))
    };
    byte[] input = new byte[0];
    Pair<Boolean, byte[]> sentinel = Pair.of(false, new byte[]{42});
    long deadlineInUs = 123_456_789L;
    for (PrecompiledContracts.PrecompiledContract contract : contracts) {
      contract.setVmShouldEndInUs(deadlineInUs);
    }
    try {
      // Start with the default snapshot, activate, then simulate a rollback/historical view.
      for (boolean enabled : new boolean[]{false, true, false}) {
        VMConfig.Snapshot snapshot = new VMConfig.Snapshot();
        snapshot.allowOptimizeTvm = enabled;
        VMConfig.setLocalSnapshot(snapshot);
        try (MockedStatic<OptimizedBN128> optimized = mockStatic(OptimizedBN128.class)) {
          optimized.when(() -> OptimizedBN128.add(same(input))).thenReturn(sentinel);
          optimized.when(() -> OptimizedBN128.multiply(same(input), eq(deadlineInUs)))
              .thenReturn(sentinel);
          optimized.when(() -> OptimizedBN128.pairing(same(input), eq(deadlineInUs)))
              .thenReturn(sentinel);
          for (int i = 0; i < contracts.length; i++) {
            Pair<Boolean, byte[]> result = contracts[i].execute(input);
            if (enabled) {
              assertSame("enabled must execute the optimized backend",
                  sentinel, result);
            } else {
              assertTrue(result.getLeft());
              assertArrayEquals(i == 2 ? new DataWord(1).getData() : new byte[64],
                  result.getRight());
            }
          }
          if (enabled) {
            optimized.verify(() -> OptimizedBN128.add(same(input)));
            optimized.verify(() -> OptimizedBN128.multiply(same(input), eq(deadlineInUs)));
            optimized.verify(() -> OptimizedBN128.pairing(same(input), eq(deadlineInUs)));
            optimized.verifyNoMoreInteractions();
          } else {
            optimized.verifyNoInteractions();
          }
        }
      }
    } finally {
      VMConfig.clearLocalSnapshot();
    }
  }
}
