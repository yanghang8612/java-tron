package org.tron.core.vm;

import static org.tron.common.crypto.Hash.sha3;
import static org.tron.common.utils.ByteUtil.EMPTY_BYTE_ARRAY;
import static org.tron.core.db.TransactionTrace.convertToTronAddress;
import static org.tron.core.vm.OpCode.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.spongycastle.util.encoders.Hex;
import org.springframework.util.StringUtils;
import org.tron.common.runtime.vm.DataWord;
import org.tron.common.runtime.vm.LogInfo;
import org.tron.common.utils.ByteArray;
import org.tron.core.vm.config.VMConfig;
import org.tron.core.vm.process.OpCodeV2;
import org.tron.core.vm.program.Program;
import org.tron.core.vm.program.Program.JVMStackOverFlowException;
import org.tron.core.vm.program.Program.OutOfEnergyException;
import org.tron.core.vm.program.Program.OutOfTimeException;
import org.tron.core.vm.program.Program.TransferException;
import org.tron.core.vm.program.Stack;

@Slf4j(topic = "VM")
public class VM {

  public static final String ADDRESS_LOG = "address: ";
  private static final String DATA_LOG = "data: ";
  private static final String SIZE_LOG = "size: ";
  private static final String VALUE_LOG = " value: ";
  private static final BigInteger _32_ = BigInteger.valueOf(32);
  private static final String ENERGY_LOG_FORMATE = "{} Op:[{}]  Energy:[{}] Deep:[{}] Hint:[{}]";
  // 3MB
  private static final BigInteger MEM_LIMIT = BigInteger.valueOf(3L * 1024 * 1024);
  private final VMConfig config;

  public VM() {
    config = VMConfig.getInstance();
  }

  public VM(VMConfig config) {
    this.config = config;
  }

  /**
   * Utility to calculate new total memory size needed for an operation. <br/> Basically just offset
   * + size, unless size is 0, in which case the result is also 0.
   *
   * @param offset starting position of the memory
   * @param size number of bytes needed
   * @return offset + size, unless size is 0. In that case memNeeded is also 0.
   */
  private static BigInteger memNeeded(DataWord offset, DataWord size) {
    return size.isZero() ? BigInteger.ZERO : offset.value().add(size.value());
  }

  private void checkMemorySize(int op, BigInteger newMemSize) {
    if (newMemSize.compareTo(MEM_LIMIT) > 0) {
      throw Program.Exception.memoryOverflow(OpCodeV2.getOpName(op));
    }
  }

  private long calcMemEnergy(EnergyCost energyCosts, long oldMemSize, BigInteger newMemSize,
      long copySize, int op) {
    long energyCost = 0;

    checkMemorySize(op, newMemSize);

    // memory SUN consume calc
    long memoryUsage = (newMemSize.longValueExact() + 31) / 32 * 32;
    if (memoryUsage > oldMemSize) {
      long memWords = (memoryUsage / 32);
      long memWordsOld = (oldMemSize / 32);
      //TODO #POC9 c_quadCoeffDiv = 512, this should be a constant, not magic number
      long memEnergy = (energyCosts.getMEMORY() * memWords + memWords * memWords / 512)
          - (energyCosts.getMEMORY() * memWordsOld + memWordsOld * memWordsOld / 512);
      energyCost += memEnergy;
    }

    if (copySize > 0) {
      long copyEnergy = energyCosts.getCOPY_ENERGY() * ((copySize + 31) / 32);
      energyCost += copyEnergy;
    }
    return energyCost;
  }

  public void step(Program program) {
    if (config.vmTrace()) {
      program.saveOpTrace();
    }

    try {
      int op = program.getCurrentOp() & 0xff;
      int val = OpCodeV2.opsBasic[op];
      if (val == 0) {
        throw Program.Exception.invalidOpCode(program.getCurrentOp());
      }
      String opName = OpCodeV2.getOpName(op);

      // hard fork for 3.2
//      if (!VMConfig.allowTvmTransferTrc10()
//          && (OpCodeV2.hardForkJudge(val, OpCodeV2.VER_TRC10_3_2_0))) {
//        throw Program.Exception.invalidOpCode(program.getCurrentOp());
//      }
//
//      if (!VMConfig.allowTvmConstantinople()
//          && (OpCodeV2.hardForkJudge(val, OpCodeV2.VER_CONSTANTINOPLE_3_6_0))) {
//        throw Program.Exception.invalidOpCode(program.getCurrentOp());
//      }
//
//      if (!VMConfig.allowTvmSolidity059() && OpCodeV2.hardForkJudge(val, OpCodeV2.VER_SOLIDITY059_3_6_5)) {
//        throw Program.Exception.invalidOpCode(program.getCurrentOp());
//      }
//
//      if (!VMConfig.allowTvmIstanbul() && (OpCodeV2.hardForkJudge(val, OpCodeV2.VER_ISTANBUL_4_1_0))) {
//        throw Program.Exception.invalidOpCode(program.getCurrentOp());
//      }

      // todo xiang
//      if (!VMConfig.allowTvmStake()
//              && (op == ISSRCANDIDATE || op == REWARDBALANCE || op == STAKE || op == UNSTAKE
//                || op == WITHDRAWREWARD)) {
//        throw Program.Exception.invalidOpCode(program.getCurrentOp());
//      }
//
//      if (!VMConfig.allowTvmAssetIssue() && (op == TOKENISSUE || op == UPDATEASSET)) {
//        throw Program.Exception.invalidOpCode(program.getCurrentOp());
//      }

      program.setLastOp((byte)op);
      program.verifyStackSize(OpCodeV2.getRequire(val));
      program.verifyStackOverflow(OpCodeV2.getRequire(val), OpCodeV2.getRet(val)); //Check not exceeding stack limits

      long oldMemSize = program.getMemSize();
      Stack stack = program.getStack();

      String hint = "";
      long energyCost = OpCodeV2.getTierLevel(val);
      EnergyCost energyCosts = EnergyCost.getInstance();
      DataWord adjustedCallEnergy = null;

      // Calculate fees and spend energy
      switch (op) {
        case 0x00:
          energyCost = energyCosts.getSTOP();
          break;
        case 0xff:
          energyCost = energyCosts.getSUICIDE();
          DataWord suicideAddressWord = stack.get(stack.size() - 1);
          if (isDeadAccount(program, suicideAddressWord)
              && !program.getBalance(program.getContractAddress()).isZero()) {
            energyCost += energyCosts.getNEW_ACCT_SUICIDE();
          }
          break;
        case 0x55:
          // todo: check the reset to 0, refund or not
          DataWord newValue = stack.get(stack.size() - 2);
          DataWord oldValue = program.storageLoad(stack.peek());
          if (oldValue == null && !newValue.isZero()) {
            // set a new not-zero value
            energyCost = energyCosts.getSET_SSTORE();
          } else if (oldValue != null && newValue.isZero()) {
            // set zero to an old value
            program.futureRefundEnergy(energyCosts.getREFUND_SSTORE());
            energyCost = energyCosts.getCLEAR_SSTORE();
          } else {
            // include:
            // [1] oldValue == null && newValue == 0
            // [2] oldValue != null && newValue != 0
            energyCost = energyCosts.getRESET_SSTORE();
          }
          break;
        case 0x54:
          energyCost = energyCosts.getSLOAD();
          break;
        case 0xd1:
        case 0x31:
        case 0xd8:
        case 0xd4:
        case 0xd9:
          energyCost = energyCosts.getBALANCE();
          break;

        // These all operate on memory and therefore potentially expand it:
        case 0x52:
          energyCost = calcMemEnergy(energyCosts, oldMemSize,
              memNeeded(stack.peek(), new DataWord(32)),
              0, op);
          break;
        case 0x53:
          energyCost = calcMemEnergy(energyCosts, oldMemSize,
              memNeeded(stack.peek(), new DataWord(1)),
              0, op);
          break;
        case 0x51:
          energyCost = calcMemEnergy(energyCosts, oldMemSize,
              memNeeded(stack.peek(), new DataWord(32)),
              0, op);
          break;
        case 0xf3:
        case 0xfd:
          energyCost = energyCosts.getSTOP() + calcMemEnergy(energyCosts, oldMemSize,
              memNeeded(stack.peek(), stack.get(stack.size() - 2)), 0, op);
          break;
        case 0x20:
          energyCost = energyCosts.getSHA3() + calcMemEnergy(energyCosts, oldMemSize,
              memNeeded(stack.peek(), stack.get(stack.size() - 2)), 0, op);
          DataWord size = stack.get(stack.size() - 2);
          long chunkUsed = (size.longValueSafe() + 31) / 32;
          energyCost += chunkUsed * energyCosts.getSHA3_WORD();
          break;
        case 0x37:
        case 0x3e:
          energyCost = calcMemEnergy(energyCosts, oldMemSize,
              memNeeded(stack.peek(), stack.get(stack.size() - 3)),
              stack.get(stack.size() - 3).longValueSafe(), op);
          break;
        case 0x39:
          energyCost = calcMemEnergy(energyCosts, oldMemSize,
              memNeeded(stack.peek(), stack.get(stack.size() - 3)),
              stack.get(stack.size() - 3).longValueSafe(), op);
          break;
        case 0x3b:
          energyCost = energyCosts.getEXT_CODE_SIZE();
          break;
        case 0x3c:
          energyCost = energyCosts.getEXT_CODE_COPY() + calcMemEnergy(energyCosts, oldMemSize,
              memNeeded(stack.get(stack.size() - 2), stack.get(stack.size() - 4)),
              stack.get(stack.size() - 4).longValueSafe(), op);
          break;
        case 0x3f:
          energyCost = energyCosts.getEXT_CODE_HASH();
          break;
        case 0xf1:
        case 0xf2:
        case 0xf4:
        case 0xfa:
        case 0xd0:
          // here, contract call an other contract, or a library, and so on
          energyCost = energyCosts.getCALL();
          DataWord callEnergyWord = stack.get(stack.size() - 1);
          DataWord callAddressWord = stack.get(stack.size() - 2);
          DataWord value = OpCodeV2.callHasValue(op) ? stack.get(stack.size() - 3) : DataWord.ZERO;

          //check to see if account does not exist and is not a precompiled contract
          if ((op == 0xf1 || op == 0xd0)
              && isDeadAccount(program, callAddressWord)
              && !value.isZero()) {
            energyCost += energyCosts.getNEW_ACCT_CALL();
          }

          // TODO #POC9 Make sure this is converted to BigInteger (256num support)
          if (!value.isZero()) {
            energyCost += energyCosts.getVT_CALL();
          }

          int opOff = OpCodeV2.callHasValue(op) ? 4 : 3;
          if (op == 0xd0) {
            opOff++;
          }
          BigInteger in = memNeeded(stack.get(stack.size() - opOff),
              stack.get(stack.size() - opOff - 1)); // in offset+size
          BigInteger out = memNeeded(stack.get(stack.size() - opOff - 2),
              stack.get(stack.size() - opOff - 3)); // out offset+size
          energyCost += calcMemEnergy(energyCosts, oldMemSize, in.max(out), 0, op);
          checkMemorySize(op, in.max(out));

          if (energyCost > program.getEnergyLimitLeft().longValueSafe()) {
            throw new OutOfEnergyException(
                "Not enough energy for '%s' operation executing: opEnergy[%d], programEnergy[%d]",
                opName,
                energyCost, program.getEnergyLimitLeft().longValueSafe());
          }
          DataWord getEnergyLimitLeft = program.getEnergyLimitLeft().clone();
          getEnergyLimitLeft.sub(new DataWord(energyCost));

          adjustedCallEnergy = program.getCallEnergy(op, callEnergyWord, getEnergyLimitLeft);
          energyCost += adjustedCallEnergy.longValueSafe();
          break;
        case 0xf0:
          energyCost = energyCosts.getCREATE() + calcMemEnergy(energyCosts, oldMemSize,
              memNeeded(stack.get(stack.size() - 2), stack.get(stack.size() - 3)), 0, op);
          break;
        case 0xf5:
          DataWord codeSize = stack.get(stack.size() - 3);
          energyCost = energyCosts.getCREATE();
          energyCost += calcMemEnergy(energyCosts, oldMemSize,
              memNeeded(stack.get(stack.size() - 2), stack.get(stack.size() - 3)), 0, op);
          energyCost += DataWord.sizeInWords(codeSize.intValueSafe()) * energyCosts.getSHA3_WORD();

          break;
        case 0xa0:
        case 0xa1:
        case 0xa2:
        case 0xa3:
        case 0xa4:
          int nTopics = op - OpCodeV2.LOG0;
          BigInteger dataSize = stack.get(stack.size() - 2).value();
          BigInteger dataCost = dataSize
              .multiply(BigInteger.valueOf(energyCosts.getLOG_DATA_ENERGY()));
          if (program.getEnergyLimitLeft().value().compareTo(dataCost) < 0) {
            throw new OutOfEnergyException(
                "Not enough energy for '%s' operation executing: opEnergy[%d], programEnergy[%d]",
                opName,
                dataCost.longValueExact(), program.getEnergyLimitLeft().longValueSafe());
          }
          energyCost = energyCosts.getLOG_ENERGY()
              + energyCosts.getLOG_TOPIC_ENERGY() * nTopics
              + energyCosts.getLOG_DATA_ENERGY() * stack.get(stack.size() - 2).longValue()
              + calcMemEnergy(energyCosts, oldMemSize,
              memNeeded(stack.peek(), stack.get(stack.size() - 2)), 0, op);

          checkMemorySize(op, memNeeded(stack.peek(), stack.get(stack.size() - 2)));
          break;
        case 0x0a:

          DataWord exp = stack.get(stack.size() - 2);
          int bytesOccupied = exp.bytesOccupied();
          energyCost =
              (long) energyCosts.getEXP_ENERGY() + energyCosts.getEXP_BYTE_ENERGY() * bytesOccupied;
          break;
        case 0xd5:
        case 0xd6:
          energyCost = energyCosts.getStakeAndUnstake();
          break;
        case 0xd7:
          energyCost = energyCosts.getWithdrawReward();
          break;
        case 0xda:
          energyCost = energyCosts.getTokenIssue();
          break;
        case 0xdb:
          energyCost = energyCosts.getUpdateAsset();
          break;
        default:
          break;
      }

      program.spendEnergy(energyCost, opName);
      program.checkCPUTimeLimit(opName);

      // Execute operation
      switch (op) {
        /**
         * Stop and Arithmetic Operations
         */
        // STOP
        case 0x00: {
          program.setHReturn(EMPTY_BYTE_ARRAY);
          program.stop();
        }
        break;
        // ADD
        case 0x01: {
          DataWord word1 = program.stackPop();
          DataWord word2 = program.stackPop();

          if (logger.isDebugEnabled()) {
            hint = word1.value() + " + " + word2.value();
          }

          word1.add(word2);
          program.stackPush(word1);
          program.step();

        }
        break;
        // MUL
        case 0x02: {
          DataWord word1 = program.stackPop();
          DataWord word2 = program.stackPop();

          if (logger.isDebugEnabled()) {
            hint = word1.value() + " * " + word2.value();
          }

          word1.mul(word2);
          program.stackPush(word1);
          program.step();
        }
        break;
        // SUB
        case 0x03: {
          DataWord word1 = program.stackPop();
          DataWord word2 = program.stackPop();

          if (logger.isDebugEnabled()) {
            hint = word1.value() + " - " + word2.value();
          }

          word1.sub(word2);
          program.stackPush(word1);
          program.step();
        }
        break;
        case 0x04: {
          DataWord word1 = program.stackPop();
          DataWord word2 = program.stackPop();

          if (logger.isDebugEnabled()) {
            hint = word1.value() + " / " + word2.value();
          }

          word1.div(word2);
          program.stackPush(word1);
          program.step();
        }
        break;
        case 0x05: {
          DataWord word1 = program.stackPop();
          DataWord word2 = program.stackPop();

          if (logger.isDebugEnabled()) {
            hint = word1.sValue() + " / " + word2.sValue();
          }

          word1.sDiv(word2);
          program.stackPush(word1);
          program.step();
        }
        break;
        case 0x06: {
          DataWord word1 = program.stackPop();
          DataWord word2 = program.stackPop();

          if (logger.isDebugEnabled()) {
            hint = word1.value() + " % " + word2.value();
          }

          word1.mod(word2);
          program.stackPush(word1);
          program.step();
        }
        break;
        case 0x07: {
          DataWord word1 = program.stackPop();
          DataWord word2 = program.stackPop();

          if (logger.isDebugEnabled()) {
            hint = word1.sValue() + " #% " + word2.sValue();
          }

          word1.sMod(word2);
          program.stackPush(word1);
          program.step();
        }
        break;
        case 0x0a: {
          DataWord word1 = program.stackPop();
          DataWord word2 = program.stackPop();

          if (logger.isDebugEnabled()) {
            hint = word1.value() + " ** " + word2.value();
          }

          word1.exp(word2);
          program.stackPush(word1);
          program.step();
        }
        break;
        case 0x0b: {
          DataWord word1 = program.stackPop();
          BigInteger k = word1.value();

          if (k.compareTo(_32_) < 0) {
            DataWord word2 = program.stackPop();
            if (logger.isDebugEnabled()) {
              hint = word1 + "  " + word2.value();
            }
            word2.signExtend(k.byteValue());
            program.stackPush(word2);
          }
          program.step();
        }
        break;
        case 0x19: {
          DataWord word1 = program.stackPop();
          word1.bnot();

          if (logger.isDebugEnabled()) {
            hint = "" + word1.value();
          }

          program.stackPush(word1);
          program.step();
        }
        break;
        case 0x10: {
          // TODO: can be improved by not using BigInteger
          DataWord word1 = program.stackPop();
          DataWord word2 = program.stackPop();

          if (logger.isDebugEnabled()) {
            hint = word1.value() + " < " + word2.value();
          }

          if (word1.value().compareTo(word2.value()) < 0) {
            word1.and(DataWord.ZERO);
            word1.getData()[31] = 1;
          } else {
            word1.and(DataWord.ZERO);
          }
          program.stackPush(word1);
          program.step();
        }
        break;
        case 0x12: {
          // TODO: can be improved by not using BigInteger
          DataWord word1 = program.stackPop();
          DataWord word2 = program.stackPop();

          if (logger.isDebugEnabled()) {
            hint = word1.sValue() + " < " + word2.sValue();
          }

          if (word1.sValue().compareTo(word2.sValue()) < 0) {
            word1.and(DataWord.ZERO);
            word1.getData()[31] = 1;
          } else {
            word1.and(DataWord.ZERO);
          }
          program.stackPush(word1);
          program.step();
        }
        break;
        case 0x13: {
          // TODO: can be improved by not using BigInteger
          DataWord word1 = program.stackPop();
          DataWord word2 = program.stackPop();

          if (logger.isDebugEnabled()) {
            hint = word1.sValue() + " > " + word2.sValue();
          }

          if (word1.sValue().compareTo(word2.sValue()) > 0) {
            word1.and(DataWord.ZERO);
            word1.getData()[31] = 1;
          } else {
            word1.and(DataWord.ZERO);
          }
          program.stackPush(word1);
          program.step();
        }
        break;
        case 0x11: {
          // TODO: can be improved by not using BigInteger
          DataWord word1 = program.stackPop();
          DataWord word2 = program.stackPop();

          if (logger.isDebugEnabled()) {
            hint = word1.value() + " > " + word2.value();
          }

          if (word1.value().compareTo(word2.value()) > 0) {
            word1.and(DataWord.ZERO);
            word1.getData()[31] = 1;
          } else {
            word1.and(DataWord.ZERO);
          }
          program.stackPush(word1);
          program.step();
        }
        break;
        case 0x14: {
          DataWord word1 = program.stackPop();
          DataWord word2 = program.stackPop();

          if (logger.isDebugEnabled()) {
            hint = word1.value() + " == " + word2.value();
          }

          if (word1.xor(word2).isZero()) {
            word1.and(DataWord.ZERO);
            word1.getData()[31] = 1;
          } else {
            word1.and(DataWord.ZERO);
          }
          program.stackPush(word1);
          program.step();
        }
        break;
        case 0x15: {
          DataWord word1 = program.stackPop();
          if (word1.isZero()) {
            word1.getData()[31] = 1;
          } else {
            word1.and(DataWord.ZERO);
          }

          if (logger.isDebugEnabled()) {
            hint = "" + word1.value();
          }

          program.stackPush(word1);
          program.step();
        }
        break;

        /**
         * Bitwise Logic Operations
         */
        case 0x16: {
          DataWord word1 = program.stackPop();
          DataWord word2 = program.stackPop();

          if (logger.isDebugEnabled()) {
            hint = word1.value() + " && " + word2.value();
          }

          word1.and(word2);
          program.stackPush(word1);
          program.step();
        }
        break;
        case 0x17: {
          DataWord word1 = program.stackPop();
          DataWord word2 = program.stackPop();

          if (logger.isDebugEnabled()) {
            hint = word1.value() + " || " + word2.value();
          }

          word1.or(word2);
          program.stackPush(word1);
          program.step();
        }
        break;
        case 0x18: {
          DataWord word1 = program.stackPop();
          DataWord word2 = program.stackPop();

          if (logger.isDebugEnabled()) {
            hint = word1.value() + " ^ " + word2.value();
          }

          word1.xor(word2);
          program.stackPush(word1);
          program.step();
        }
        break;
        case 0x1a: {
          DataWord word1 = program.stackPop();
          DataWord word2 = program.stackPop();
          final DataWord result;
          if (word1.value().compareTo(_32_) < 0) {
            byte tmp = word2.getData()[word1.intValue()];
            word2.and(DataWord.ZERO);
            word2.getData()[31] = tmp;
            result = word2;
          } else {
            result = new DataWord();
          }

          if (logger.isDebugEnabled()) {
            hint = "" + result.value();
          }

          program.stackPush(result);
          program.step();
        }
        break;
        case 0x1b: {
          DataWord word1 = program.stackPop();
          DataWord word2 = program.stackPop();
          final DataWord result = word2.shiftLeft(word1);

          if (logger.isInfoEnabled()) {
            hint = "" + result.value();
          }

          program.stackPush(result);
          program.step();
        }
        break;
        case 0x1c: {
          DataWord word1 = program.stackPop();
          DataWord word2 = program.stackPop();
          final DataWord result = word2.shiftRight(word1);

          if (logger.isInfoEnabled()) {
            hint = "" + result.value();
          }

          program.stackPush(result);
          program.step();
        }
        break;
        case 0x1d: {
          DataWord word1 = program.stackPop();
          DataWord word2 = program.stackPop();
          final DataWord result = word2.shiftRightSigned(word1);

          if (logger.isInfoEnabled()) {
            hint = "" + result.value();
          }

          program.stackPush(result);
          program.step();
        }
        break;
        case 0x08: {
          DataWord word1 = program.stackPop();
          DataWord word2 = program.stackPop();
          DataWord word3 = program.stackPop();
          word1.addmod(word2, word3);
          program.stackPush(word1);
          program.step();
        }
        break;
        case 0x09: {
          DataWord word1 = program.stackPop();
          DataWord word2 = program.stackPop();
          DataWord word3 = program.stackPop();
          word1.mulmod(word2, word3);
          program.stackPush(word1);
          program.step();
        }
        break;

        /**
         * SHA3
         */
        case 0x20: {
          DataWord memOffsetData = program.stackPop();
          DataWord lengthData = program.stackPop();
          byte[] buffer = program
              .memoryChunk(memOffsetData.intValueSafe(), lengthData.intValueSafe());

          byte[] encoded = sha3(buffer);
          DataWord word = new DataWord(encoded);

          if (logger.isDebugEnabled()) {
            hint = word.toString();
          }

          program.stackPush(word);
          program.step();
        }
        break;

        /**
         * Environmental Information
         */
        case 0x30: {
          DataWord address = program.getContractAddress();
          if (VMConfig.allowMultiSign()) { // allowMultiSigns proposal
            address = new DataWord(address.getLast20Bytes());
          }

          if (logger.isDebugEnabled()) {
            hint = ADDRESS_LOG + Hex.toHexString(address.getLast20Bytes());
          }

          program.stackPush(address);
          program.step();
        }
        break;
        case 0x31: {
          DataWord address = program.stackPop();
          DataWord balance = program.getBalance(address);

          if (logger.isDebugEnabled()) {
            hint = ADDRESS_LOG
                + Hex.toHexString(address.getLast20Bytes())
                + " balance: " + balance.toString();
          }

          program.stackPush(balance);
          program.step();
        }
        break;
        case 0xd8: {
          DataWord address = program.stackPop();
          DataWord rewardBalance = program.getRewardBalance(address);

          if (logger.isDebugEnabled()) {
            hint = ADDRESS_LOG
                    + Hex.toHexString(address.getLast20Bytes())
                    + " reward balance: " + rewardBalance.toString();
          }

          program.stackPush(rewardBalance);
          program.step();
        }
        break;
        case 0xd4: {
          DataWord address = program.stackPop();
          DataWord isContract = program.isContract(address);

          program.stackPush(isContract);
          program.step();
        }
        break;
        case 0xd9: {
          DataWord address = program.stackPop();
          DataWord isSRCandidate = program.isSRCandidate(address);

          program.stackPush(isSRCandidate);
          program.step();
        }
        break;
        case 0x32: {
          DataWord originAddress = program.getOriginAddress();

          if (VMConfig.allowMultiSign()) { //allowMultiSign proposal
            originAddress = new DataWord(originAddress.getLast20Bytes());
          }

          if (logger.isDebugEnabled()) {
            hint = ADDRESS_LOG + Hex.toHexString(originAddress.getLast20Bytes());
          }

          program.stackPush(originAddress);
          program.step();
        }
        break;
        case 0x33: {
          DataWord callerAddress = program.getCallerAddress();
          /**
           since we use 21 bytes address instead of 20 as etherum, we need to make sure
           the address length in vm is matching with 20
           */
          callerAddress = new DataWord(callerAddress.getLast20Bytes());
          if (logger.isDebugEnabled()) {
            hint = ADDRESS_LOG + Hex.toHexString(callerAddress.getLast20Bytes());
          }

          program.stackPush(callerAddress);
          program.step();
        }
        break;
        case 0x34: {
          DataWord callValue = program.getCallValue();

          if (logger.isDebugEnabled()) {
            hint = "value: " + callValue;
          }

          program.stackPush(callValue);
          program.step();
        }
        break;
        case 0xd2:
          DataWord tokenValue = program.getTokenValue();

          if (logger.isDebugEnabled()) {
            hint = "tokenValue: " + tokenValue;
          }

          program.stackPush(tokenValue);
          program.step();
          break;
        case 0xd3:
          DataWord _tokenId = program.getTokenId();

          if (logger.isDebugEnabled()) {
            hint = "tokenId: " + _tokenId;
          }

          program.stackPush(_tokenId);
          program.step();
          break;
        case 0x35: {
          DataWord dataOffs = program.stackPop();
          DataWord value = program.getDataValue(dataOffs);

          if (logger.isDebugEnabled()) {
            hint = DATA_LOG + value;
          }

          program.stackPush(value);
          program.step();
        }
        break;
        case 0x36: {
          DataWord dataSize = program.getDataSize();

          if (logger.isDebugEnabled()) {
            hint = SIZE_LOG + dataSize.value();
          }

          program.stackPush(dataSize);
          program.step();
        }
        break;
        case 0x37: {
          DataWord memOffsetData = program.stackPop();
          DataWord dataOffsetData = program.stackPop();
          DataWord lengthData = program.stackPop();

          byte[] msgData = program.getDataCopy(dataOffsetData, lengthData);

          if (logger.isDebugEnabled()) {
            hint = DATA_LOG + Hex.toHexString(msgData);
          }

          program.memorySave(memOffsetData.intValueSafe(), msgData);
          program.step();
        }
        break;
        case 0x3d: {
          DataWord dataSize = program.getReturnDataBufferSize();

          if (logger.isDebugEnabled()) {
            hint = SIZE_LOG + dataSize.value();
          }

          program.stackPush(dataSize);
          program.step();
        }
        break;
        case 0x3e: {
          DataWord memOffsetData = program.stackPop();
          DataWord dataOffsetData = program.stackPop();
          DataWord lengthData = program.stackPop();

          byte[] msgData = program.getReturnDataBufferData(dataOffsetData, lengthData);

          if (msgData == null) {
            throw new Program.ReturnDataCopyIllegalBoundsException(dataOffsetData, lengthData,
                program.getReturnDataBufferSize().longValueSafe());
          }

          if (logger.isDebugEnabled()) {
            hint = DATA_LOG + Hex.toHexString(msgData);
          }

          program.memorySave(memOffsetData.intValueSafe(), msgData);
          program.step();
        }
        break;
        case 0x38:
        case 0x3b: {

          int length;
          if (op == 0x38) {
            length = program.getCode().length;
          } else {
            DataWord address = program.stackPop();
            length = program.getCodeAt(address).length;
          }
          DataWord codeLength = new DataWord(length);

          if (logger.isDebugEnabled()) {
            hint = SIZE_LOG + length;
          }

          program.stackPush(codeLength);
          program.step();
          break;
        }
        case 0x39:
        case 0x3c: {

          byte[] fullCode = EMPTY_BYTE_ARRAY;
          if (op == 0x39) {
            fullCode = program.getCode();
          }

          if (op == 0x3c) {
            DataWord address = program.stackPop();
            fullCode = program.getCodeAt(address);
          }

          int memOffset = program.stackPop().intValueSafe();
          int codeOffset = program.stackPop().intValueSafe();
          int lengthData = program.stackPop().intValueSafe();

          int sizeToBeCopied =
              (long) codeOffset + lengthData > fullCode.length
                  ? (fullCode.length < codeOffset ? 0 : fullCode.length - codeOffset)
                  : lengthData;

          byte[] codeCopy = new byte[lengthData];

          if (codeOffset < fullCode.length) {
            System.arraycopy(fullCode, codeOffset, codeCopy, 0, sizeToBeCopied);
          }

          if (logger.isDebugEnabled()) {
            hint = "code: " + Hex.toHexString(codeCopy);
          }

          program.memorySave(memOffset, codeCopy);
          program.step();
          break;
        }
        case 0x3f: {
          DataWord address = program.stackPop();
          byte[] codeHash = program.getCodeHashAt(address);
          program.stackPush(codeHash);
          program.step();
        }
        break;
        case 0x3a: {
          DataWord energyPrice = new DataWord(0);

          if (logger.isDebugEnabled()) {
            hint = "price: " + energyPrice.toString();
          }

          program.stackPush(energyPrice);
          program.step();
        }
        break;

        /**
         * Block Information
         */
        case 0x40: {

          int blockIndex = program.stackPop().intValueSafe();

          DataWord blockHash = program.getBlockHash(blockIndex);

          if (logger.isDebugEnabled()) {
            hint = "blockHash: " + blockHash;
          }

          program.stackPush(blockHash);
          program.step();
        }
        break;
        case 0x41: {
          DataWord coinbase = program.getCoinbase();

          if (logger.isDebugEnabled()) {
            hint = "coinbase: " + Hex.toHexString(coinbase.getLast20Bytes());
          }

          program.stackPush(coinbase);
          program.step();
        }
        break;
        case 0x42: {
          DataWord timestamp = program.getTimestamp();

          if (logger.isDebugEnabled()) {
            hint = "timestamp: " + timestamp.value();
          }

          program.stackPush(timestamp);
          program.step();
        }
        break;
        case 0x43: {
          DataWord number = program.getNumber();

          if (logger.isDebugEnabled()) {
            hint = "number: " + number.value();
          }

          program.stackPush(number);
          program.step();
        }
        break;
        case 0x44: {
          DataWord difficulty = program.getDifficulty();

          if (logger.isDebugEnabled()) {
            hint = "difficulty: " + difficulty;
          }

          program.stackPush(difficulty);
          program.step();
        }
        break;
        case 0x45: {
          // todo: this energylimit is the block's energy limit
          DataWord energyLimit = new DataWord(0);

          if (logger.isDebugEnabled()) {
            hint = "energylimit: " + energyLimit;
          }

          program.stackPush(energyLimit);
          program.step();
        }
        break;
        case 0x46: {
          DataWord chainId = program.getChainId();
          program.stackPush(chainId);
          program.step();
          break;
        }
        case 0x47: {
          DataWord selfBalance = program.getBalance(program.getContractAddress());
          program.stackPush(selfBalance);
          program.step();
          break;
        }
        case 0x50: {
          program.stackPop();
          program.step();
        }
        break;
        case 0x80:
        case 0x81:
        case 0x82:
        case 0x83:
        case 0x84:
        case 0x85:
        case 0x86:
        case 0x87:
        case 0x88:
        case 0x89:
        case 0x8a:
        case 0x8b:
        case 0x8c:
        case 0x8d:
        case 0x8e:
        case 0x8f: {

          int n = op - OpCodeV2.DUP1 + 1;
          DataWord word_1 = stack.get(stack.size() - n);
          program.stackPush(word_1.clone());
          program.step();

          break;
        }
        case 0x90:
        case 0x91:
        case 0x92:
        case 0x93:
        case 0x94:
        case 0x95:
        case 0x96:
        case 0x97:
        case 0x98:
        case 0x99:
        case 0x9a:
        case 0x9b:
        case 0x9c:
        case 0x9d:
        case 0x9e:
        case 0x9f: {

          int n = op - OpCodeV2.SWAP1 + 2;
          stack.swap(stack.size() - 1, stack.size() - n);
          program.step();
          break;
        }
        case 0xa0:
        case 0xa1:
        case 0xa2:
        case 0xa3:
        case 0xa4: {

          if (program.isStaticCall()) {
            throw new Program.StaticCallModificationException();
          }
          DataWord address = program.getContractAddress();

          DataWord memStart = stack.pop();
          DataWord memOffset = stack.pop();

          int nTopics = op - OpCodeV2.LOG0;

          List<DataWord> topics = new ArrayList<>();
          for (int i = 0; i < nTopics; ++i) {
            DataWord topic = stack.pop();
            topics.add(topic);
          }

          byte[] data = program.memoryChunk(memStart.intValueSafe(), memOffset.intValueSafe());

          LogInfo logInfo =
              new LogInfo(address.getLast20Bytes(), topics, data);

          if (logger.isDebugEnabled()) {
            hint = logInfo.toString();
          }

          program.getResult().addLogInfo(logInfo);
          program.step();
          break;
        }
        case 0x51: {
          DataWord addr = program.stackPop();
          DataWord data = program.memoryLoad(addr);

          if (logger.isDebugEnabled()) {
            hint = DATA_LOG + data;
          }

          program.stackPush(data);
          program.step();
        }
        break;
        case 0x52: {
          DataWord addr = program.stackPop();
          DataWord value = program.stackPop();

          if (logger.isDebugEnabled()) {
            hint = "addr: " + addr + VALUE_LOG + value;
          }

          program.memorySave(addr, value);
          program.step();
        }
        break;
        case 0x53: {
          DataWord addr = program.stackPop();
          DataWord value = program.stackPop();
          byte[] byteVal = {value.getData()[31]};
          program.memorySave(addr.intValueSafe(), byteVal);
          program.step();
        }
        break;
        case 0x54: {
          DataWord key = program.stackPop();
          DataWord value = program.storageLoad(key);

          if (logger.isDebugEnabled()) {
            hint = "key: " + key + VALUE_LOG + value;
          }

          if (value == null) {
            value = key.and(DataWord.ZERO);
          }

          program.stackPush(value);
          program.step();
        }
        break;
        case 0x55: {
          if (program.isStaticCall()) {
            throw new Program.StaticCallModificationException();
          }

          DataWord addr = program.stackPop();
          DataWord value = program.stackPop();

          if (logger.isDebugEnabled()) {
            hint =
                "[" + program.getContractAddress().toPrefixString() + "] key: " + addr + VALUE_LOG
                    + value;
          }

          program.storageSave(addr, value);
          program.step();
        }
        break;
        case 0x56: {
          DataWord pos = program.stackPop();
          int nextPC = program.verifyJumpDest(pos);

          if (logger.isDebugEnabled()) {
            hint = "~> " + nextPC;
          }

          program.setPC(nextPC);

        }
        break;
        case 0x57: {
          DataWord pos = program.stackPop();
          DataWord cond = program.stackPop();

          if (!cond.isZero()) {
            int nextPC = program.verifyJumpDest(pos);

            if (logger.isDebugEnabled()) {
              hint = "~> " + nextPC;
            }

            program.setPC(nextPC);
          } else {
            program.step();
          }

        }
        break;
        case 0x58: {
          int pc = program.getPC();
          DataWord pcWord = new DataWord(pc);

          if (logger.isDebugEnabled()) {
            hint = pcWord.toString();
          }

          program.stackPush(pcWord);
          program.step();
        }
        break;
        case 0x59: {
          int memSize = program.getMemSize();
          DataWord wordMemSize = new DataWord(memSize);

          if (logger.isDebugEnabled()) {
            hint = "" + memSize;
          }

          program.stackPush(wordMemSize);
          program.step();
        }
        break;
        case 0x5a: {
          DataWord energy = program.getEnergyLimitLeft();
          if (logger.isDebugEnabled()) {
            hint = "" + energy;
          }

          program.stackPush(energy);
          program.step();
        }
        break;

        case 0x60:
        case 0x61:
        case 0x62:
        case 0x63:
        case 0x64:
        case 0x65:
        case 0x66:
        case 0x67:
        case 0x68:
        case 0x69:
        case 0x6a:
        case 0x6b:
        case 0x6c:
        case 0x6d:
        case 0x6e:
        case 0x6f:
        case 0x70:
        case 0x71:
        case 0x72:
        case 0x73:
        case 0x74:
        case 0x75:
        case 0x76:
        case 0x77:
        case 0x78:
        case 0x79:
        case 0x7a:
        case 0x7b:
        case 0x7c:
        case 0x7d:
        case 0x7e:
        case 0x7f: {
          program.step();
          int nPush = op - PUSH1.val() + 1;

          byte[] data = program.sweep(nPush);

          if (logger.isDebugEnabled()) {
            hint = "" + Hex.toHexString(data);
          }

          program.stackPush(data);
          break;
        }
        case 0x5b: {
          program.step();
        }
        break;
        case 0xf0: {
          if (program.isStaticCall()) {
            throw new Program.StaticCallModificationException();
          }
          DataWord value = program.stackPop();
          DataWord inOffset = program.stackPop();
          DataWord inSize = program.stackPop();
          program.createContract(value, inOffset, inSize);

          program.step();
        }
        break;
        case 0xf5: {
          if (program.isStaticCall()) {
            throw new Program.StaticCallModificationException();
          }
          DataWord value = program.stackPop();
          DataWord inOffset = program.stackPop();
          DataWord inSize = program.stackPop();
          DataWord salt = program.stackPop();
          program.createContract2(value, inOffset, inSize, salt);
          program.step();
        }
        break;
        case 0xd1: {
          DataWord tokenId = program.stackPop();
          DataWord address = program.stackPop();
          DataWord tokenBalance = program.getTokenBalance(address, tokenId);
          program.stackPush(tokenBalance);

          program.step();
        }
        break;
        case 0xf1:
        case 0xf2:
        case 0xd0:
        case 0xf4:
        case 0xfa: {
          program.stackPop(); // use adjustedCallEnergy instead of requested
          DataWord codeAddress = program.stackPop();

          DataWord value;
          if (OpCodeV2.callHasValue(op)) {
            value = program.stackPop();
          } else {
            value = DataWord.ZERO;
          }

          if (program.isStaticCall() && (op == 0xf1 || op == 0xd0) && !value.isZero()) {
            throw new Program.StaticCallModificationException();
          }

          if (!value.isZero()) {
            adjustedCallEnergy.add(new DataWord(energyCosts.getSTIPEND_CALL()));
          }

          DataWord tokenId = new DataWord(0);
          boolean isTokenTransferMsg = false;
          if (op == 0xd0) {
            tokenId = program.stackPop();
            if (VMConfig.allowMultiSign()) { // allowMultiSign proposal
              isTokenTransferMsg = true;
            }
          }

          DataWord inDataOffs = program.stackPop();
          DataWord inDataSize = program.stackPop();

          DataWord outDataOffs = program.stackPop();
          DataWord outDataSize = program.stackPop();

          if (logger.isDebugEnabled()) {
            hint = "addr: " + Hex.toHexString(codeAddress.getLast20Bytes())
                + " energy: " + adjustedCallEnergy.shortHex()
                + " inOff: " + inDataOffs.shortHex()
                + " inSize: " + inDataSize.shortHex();
            logger.debug(ENERGY_LOG_FORMATE, String.format("%5s", "[" + program.getPC() + "]"),
                String.format("%-12s", opName),
                program.getEnergyLimitLeft().value(),
                program.getCallDeep(), hint);
          }

          program.memoryExpand(outDataOffs, outDataSize);

          MessageCall msg = new MessageCall(
              op, adjustedCallEnergy, codeAddress, value, inDataOffs, inDataSize,
              outDataOffs, outDataSize, tokenId, isTokenTransferMsg);

          PrecompiledContracts.PrecompiledContract contract =
              PrecompiledContracts.getContractForAddress(codeAddress);

          if (!OpCodeV2.callIsStateless(op)) {
            program.getResult().addTouchAccount(codeAddress.getLast20Bytes());
          }

          if (contract != null) {
            program.callToPrecompiledAddress(msg, contract);
          } else {
            program.callToAddress(msg);
          }

          program.step();
          break;
        }
        case 0xd5: {
          DataWord srAddress = program.stackPop();
          DataWord stakeAmount = program.stackPop();
          boolean result = program.stake(srAddress, stakeAmount);
          program.stackPush(new DataWord(result ? 1 : 0));

          program.step();
        }
        break;
        case 0xd6: {
          boolean result = program.unstake();
          program.stackPush(new DataWord(result ? 1 : 0));

          program.step();
        }
        break;
        case 0xd7: {
          program.withdrawReward();
          program.step();
        }
        break;
        case 0xda: {
          DataWord name = program.stackPop();
          DataWord abbr = program.stackPop();
          DataWord totalSupply = program.stackPop();
          DataWord precision = program.stackPop();

          program.tokenIssue(name, abbr, totalSupply, precision);
          program.step();
          break;
        }
        case 0xdb: {
          program.stackPop();
          DataWord urlDataOffs = program.stackPop();
          DataWord descriptionDataOffs = program.stackPop();

          program.updateAsset(urlDataOffs, descriptionDataOffs);
          program.step();
          break;
        }
        case 0xf3:
        case 0xfd: {
          DataWord offset = program.stackPop();
          DataWord size = program.stackPop();

          byte[] hReturn = program.memoryChunk(offset.intValueSafe(), size.intValueSafe());
          program.setHReturn(hReturn);

          if (logger.isDebugEnabled()) {
            hint = DATA_LOG + Hex.toHexString(hReturn)
                + " offset: " + offset.value()
                + " size: " + size.value();
          }

          program.step();
          program.stop();

          if (op == 0xfd) {
            program.getResult().setRevert();
          }
          break;
        }
        case 0xff: {
          if (program.isStaticCall()) {
            throw new Program.StaticCallModificationException();
          }

          DataWord address = program.stackPop();
          program.suicide(address);
          program.getResult().addTouchAccount(address.getLast20Bytes());

          if (logger.isDebugEnabled()) {
            hint = ADDRESS_LOG + Hex.toHexString(program.getContractAddress().getLast20Bytes());
          }

          program.stop();
        }
        break;
        default:
          break;
      }

      program.setPreviouslyExecutedOp(OpCodeV2.getOpByte(op));
    } catch (RuntimeException e) {
      logger.info("VM halted: [{}]", e.getMessage());
      if (!(e instanceof TransferException)) {
        program.spendAllEnergy();
      }
      program.resetFutureRefund();
      program.stop();
      throw e;
    } finally {
      program.fullTrace();
    }
  }

  public void play(Program program) {
    try {
      if (program.byTestingSuite()) {
        return;
      }

      while (!program.isStopped()) {
        this.step(program);
      }

    } catch (JVMStackOverFlowException | OutOfTimeException e) {
      throw e;
    } catch (RuntimeException e) {
      if (StringUtils.isEmpty(e.getMessage())) {
        logger.warn("Unknown Exception occurred, tx id: {}",
            Hex.toHexString(program.getRootTransactionId()), e);
        program.setRuntimeFailure(new RuntimeException("Unknown Exception"));
      } else {
        program.setRuntimeFailure(e);
      }
    } catch (StackOverflowError soe) {
      logger
          .info("\n !!! StackOverflowError: update your java run command with -Xss !!!\n", soe);
      throw new JVMStackOverFlowException();
    }
  }

  private boolean isDeadAccount(Program program, DataWord address) {
    return program.getContractState().getAccount(convertToTronAddress(address.getLast20Bytes()))
        == null;
  }
}
