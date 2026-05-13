package org.tron.core.vm;

import static org.tron.core.Constant.DYNAMIC_ENERGY_FACTOR_DECIMAL;

import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.encoders.Hex;
import org.springframework.util.StringUtils;
import org.tron.core.vm.config.VMConfig;
import org.tron.core.vm.program.Program;
import org.tron.core.vm.program.Program.JVMStackOverFlowException;
import org.tron.core.vm.program.Program.OutOfTimeException;
import org.tron.core.vm.program.Program.TransferException;
import org.tron.core.vm.program.Stack;

@Slf4j(topic = "VM")
public class VM {

  private static final Set<Integer> CALL_OPS = ImmutableSet.of(Op.CALL, Op.STATICCALL,
      Op.DELEGATECALL, Op.CALLCODE, Op.CALLTOKEN);

  // Per-thread flag: when set, VM.play records an OpStep for each executed
  // instruction onto the program's ProgramResult. Toggled by VMActuator
  // around the root play() call; child play() invocations inherit it because
  // they run on the same thread.
  private static final ThreadLocal<Boolean> RECORD_OPS_TL =
      ThreadLocal.withInitial(() -> Boolean.FALSE);

  public static void setRecordOps(boolean enable) {
    if (enable) {
      RECORD_OPS_TL.set(Boolean.TRUE);
    } else {
      RECORD_OPS_TL.remove();
    }
  }

  public static void play(Program program, JumpTable jumpTable) {
    final boolean recordOps = RECORD_OPS_TL.get();
    try {
      long factor = DYNAMIC_ENERGY_FACTOR_DECIMAL;
      long energyUsage = 0L;

      if (VMConfig.allowDynamicEnergy()) {
        factor = program.updateContextContractFactor();
      }

      while (!program.isStopped()) {
        if (VMConfig.vmTrace()) {
          program.saveOpTrace();
        }

        try {
          Operation op = jumpTable.get(program.getCurrentOpIntValue());
          if (!op.isEnabled()) {
            throw Program.Exception.invalidOpCode(program.getCurrentOp());
          }
          program.setLastOp((byte) op.getOpcode());

          /* stack underflow/overflow check */
          program.verifyStackSize(op.getRequire());
          program.verifyStackOverflow(op.getRequire(), op.getRet());

          String opName = Op.getNameOf(op.getOpcode());

          /* capture pre-execution state for ordered op trace */
          int pcBefore = 0;
          int depth = 0;
          List<byte[]> stackTop = null;
          if (recordOps) {
            pcBefore = program.getPC();
            depth = program.getCallDeep();
            stackTop = captureStackTop(program.getStack(), op.getRequire());
          }

          /* spend energy before execution */
          long energy = op.getEnergyCost(program);
          if (VMConfig.allowDynamicEnergy()) {
            long actualEnergy = energy;
            // CALL Ops have special calculation on energy.
            if (CALL_OPS.contains(op.getOpcode())) {
              actualEnergy = energy
                  - program.getAdjustedCallEnergy().longValueSafe()
                  - program.getCallPenaltyEnergy();
            }
            energyUsage += actualEnergy;

            if (factor > DYNAMIC_ENERGY_FACTOR_DECIMAL) {
              long penalty;

              // CALL Ops have special calculation on energy.
              if (CALL_OPS.contains(op.getOpcode())) {
                penalty = program.getCallPenaltyEnergy();
              } else {
                penalty = energy * factor / DYNAMIC_ENERGY_FACTOR_DECIMAL - energy;
                if (penalty < 0) {
                  penalty = 0;
                }
                energy += penalty;
              }

              program.spendEnergyWithPenalty(energy, penalty, opName);
            } else {
              program.spendEnergy(energy, opName);
            }

          } else {
            program.spendEnergy(energy, opName);
          }


          /* check if cpu time out */
          program.checkCPUTimeLimit(opName);

          /* exec op action */
          op.execute(program);

          if (recordOps) {
            program.getResult().addOpStep(pcBefore, opName, depth, energy, stackTop);
          }
          program.setPreviouslyExecutedOp((byte) op.getOpcode());
        } catch (RuntimeException e) {
          logger.info("VM halted: [{}]", e.getMessage());
          if (!(e instanceof TransferException)) {
            program.spendAllEnergy();
          }
          //program.resetFutureRefund();
          program.stop();
          throw e;
        } finally {
          program.fullTrace();
        }
      }

      if (VMConfig.allowDynamicEnergy()) {
        program.addContextContractUsage(energyUsage);
      }

    } catch (JVMStackOverFlowException | OutOfTimeException e) {
      throw e;
    } catch (RuntimeException e) {
      // https://openjdk.org/jeps/358
      // https://bugs.openjdk.org/browse/JDK-8220715
      // since jdk 14, the NullPointerExceptions message is not empty
      if (e instanceof NullPointerException || StringUtils.isEmpty(e.getMessage())) {
        logger.warn("Unknown Exception occurred, tx id: {}",
            Hex.toHexString(program.getRootTransactionId()), e);
        program.setRuntimeFailure(new RuntimeException("Unknown Exception"));
      } else {
        program.setRuntimeFailure(e);
      }
    } catch (StackOverflowError soe) {
      logger.info("\n !!! StackOverflowError: update your java run command with -Xss !!!\n", soe);
      throw new JVMStackOverFlowException();
    }
  }

  private static List<byte[]> captureStackTop(Stack stack, int n) {
    if (n <= 0 || stack.isEmpty()) {
      return Collections.emptyList();
    }
    int size = stack.size();
    int count = Math.min(n, size);
    List<byte[]> top = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      top.add(stack.get(size - 1 - i).getData().clone());
    }
    return top;
  }
}
