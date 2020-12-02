/*
 * Copyright (c) [2016] [ <ether.camp> ]
 * This file is part of the ethereumJ library.
 *
 * The ethereumJ library is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The ethereumJ library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with the ethereumJ library. If not, see <http://www.gnu.org/licenses/>.
 */
package org.tron.core.vm.process;


import lombok.extern.slf4j.Slf4j;
import org.tron.core.vm.OpCode;

/**
 * Instruction set for the Ethereum Virtual Machine See Yellow Paper:
 * http://www.gavwood.com/Paper.pdf - Appendix G. Virtual Machine Specification
 */
@Slf4j
public class OpCodeV2 {
  // use 4 bytes to identify all info.

  public static final int VER_BASIC_3_0_0 = 0b00001 << 19;
  public static final int VER_TRC10_3_2_0 = 0b00010 << 19;
  public static final int VER_CONSTANTINOPLE_3_6_0 = 0b00100 << 19;
  public static final int VER_SOLIDITY059_3_6_5 = 0b01000 << 19;
  public static final int VER_ISTANBUL_4_1_0 = 0b10000 << 19;
  public static final int VER_STAKING_4_2_0 = 0b100000 << 19; // This one is not in effect yet.

  /*|<- 13 bits (bit-flag of HardForks) ->|<- 5 bits ->|<- 5 bits ->|<- 4 bits ->|<- 5 bits -> */
  private static final int OP_0_0_SpecialTier = ((0 << 5 | 0) << 4 | 7) << 5;
  private static final int OP_0_1_BaseTier = ((0 << 5 | 1) << 4 | 1) << 5;
  private static final int OP_0_1_VeryLowTier = ((0 << 5 | 1) << 4 | 2) << 5;
  private static final int OP_0_1_ExtTier = ((0 << 5 | 1) << 4 | 6) << 5;

  private static final int OP_0_1_LowTier = ((0 << 5 | 1) << 4 | 3) << 5;

  private static final int OP_1_0_ZeroTier = ((1 << 5 | 0) << 4 | 0) << 5;
  private static final int OP_1_0_BaseTier = ((1 << 5 | 0) << 4 | 1) << 5;
  private static final int OP_1_0_MidTier = ((1 << 5 | 0) << 4 | 4) << 5;

  private static final int OP_1_1_HighTier = ((1 << 5 | 1) << 4 | 5) << 5;
  private static final int OP_1_1_ExtTier = ((1 << 5 | 1) << 4 | 6) << 5;
  private static final int OP_1_1_VeryLowTier = ((1 << 5 | 1) << 4 | 2) << 5;
  private static final int OP_1_1_SpecialTier = ((1 << 5 | 1) << 4 | 7) << 5;

  private static final int OP_2_0_ZeroTier = ((2 << 5 | 0) << 4 | 0) << 5;
  private static final int OP_2_0_VeryLowTier = ((2 << 5 | 0) << 4 | 2) << 5;
  private static final int OP_2_0_HighTier = ((2 << 5 | 0) << 4 | 5) << 5;
  private static final int OP_2_0_SpecialTier = ((2 << 5 | 0) << 4 | 7) << 5;

  private static final int OP_2_1_VeryLowTier = ((2 << 5 | 1) << 4 | 2) << 5;
  private static final int OP_2_1_LowerTier = ((2 << 5 | 1) << 4 | 3) << 5;
  private static final int OP_2_1_ExtTier = ((2 << 5 | 1) << 4 | 6) << 5;
  private static final int OP_2_1_SpecialTier = ((2 << 5 | 1) << 4 | 7) << 5;

  private static final int OP_3_0_VeryLowTier = ((3 << 5 | 0) << 4 | 2) << 5;

  private static final int OP_3_1_MidTier = ((3 << 5 | 1) << 4 | 4) << 5;
  private static final int OP_3_1_SpecialTier = ((3 << 5 | 1) << 4 | 7) << 5;
  private static final int OP_4_0_ExtTier = ((4 << 5 | 0) << 4 | 6) << 5;

  private static final int OP_4_1_SpecialTier = ((4 << 5 | 1) << 4 | 7) << 5;
  private static final int OP_6_1_SpecialTier = ((6 << 5 | 1) << 4 | 7) << 5;
  private static final int OP_7_1_SpecialTier = ((7 << 5 | 1) << 4 | 7) << 5;
  private static final int OP_8_1_SpecialTier = ((8 << 5 | 1) << 4 | 7) << 5;


//  public static final int[] opsBasic = new int[256];
//  static {
//    opsBasic[0x00] =  VER_BASIC_3_0_0 | ((0 << 5 | 0) << 4 | 0) << 5;
//    opsBasic[0x01] = ...;
//  }

  public static final int[] opsBasic = {
      // STOP(0x00, 0, 0,OpCode.Tier.ZeroTier), // Halts execution (0x00)
      VER_BASIC_3_0_0 | ((0 << 5 | 0) << 4 | 0) << 5,
      // ADD(0x01, 2, 1, OpCode.Tier.VeryLowTier), // (0x01) Addition operation
      VER_BASIC_3_0_0 | OP_2_1_VeryLowTier,
      // MUL(0x02, 2, 1, OpCode.Tier.LowTier), // (0x02) Multiplication operation
      VER_BASIC_3_0_0 | OP_2_1_LowerTier,
      // SUB(0x03, 2, 1, OpCode.Tier.VeryLowTier), // (0x03) Subtraction operations
      VER_BASIC_3_0_0 | OP_2_1_VeryLowTier,
      //DIV(0x04, 2, 1,OpCode.Tier.LowTier), // (0x04) Integer division operation
      VER_BASIC_3_0_0 | OP_2_1_LowerTier,
      // SDIV(0x05, 2, 1,OpCode.Tier.LowTier), //(0x05) Signed integer division operation
      VER_BASIC_3_0_0 | OP_2_1_LowerTier,
      // MOD(0x06, 2, 1,OpCode.Tier.LowTier), // (0x06) Modulo remainder operation
      VER_BASIC_3_0_0 | OP_2_1_LowerTier,
      // SMOD(0x07, 2, 1,OpCode.Tier.LowTier), // (0x07) Signed modulo remainder operation
      VER_BASIC_3_0_0 | OP_2_1_LowerTier,
      // ADDMOD(0x08, 3, 1,OpCode.Tier.MidTier), // (0x08) Addition combined with modulo remainder operation
      VER_BASIC_3_0_0 | OP_3_1_MidTier,
      // MULMOD(0x09, 3, 1,OpCode.Tier.MidTier), // (0x09) Multiplication combined with modulo remainder operation
      VER_BASIC_3_0_0 | OP_3_1_MidTier,
      // EXP(0x0a, 2, 1,OpCode.Tier.SpecialTier), // (0x0a) Exponential operation
      VER_BASIC_3_0_0 | OP_2_1_SpecialTier,
      // SIGNEXTEND(0x0b, 2, 1,OpCode.Tier.LowTier), // (0x0b) Extend length of signed integer
      VER_BASIC_3_0_0 | OP_2_1_LowerTier,

      /* 0x0c ~ 0x0f is invalid */
      0, 0, 0, 0,

      /*  Bitwise Logic & Comparison Operations   */
      // LT(0X10, 2, 1,OpCode.Tier.VeryLowTier), // (0x10) Less-than comparison
      VER_BASIC_3_0_0 | OP_2_1_VeryLowTier,
      // GT(0X11, 2, 1,OpCode.Tier.VeryLowTier), // (0x11) Greater-than comparison
      VER_BASIC_3_0_0 | OP_2_1_VeryLowTier,
      // SLT(0X12, 2, 1,OpCode.Tier.VeryLowTier), // (0x12) Signed less-than comparison
      VER_BASIC_3_0_0 | OP_2_1_VeryLowTier,
      // SGT(0X13, 2, 1,OpCode.Tier.VeryLowTier), // (0x13) Signed greater-than comparison
      VER_BASIC_3_0_0 | OP_2_1_VeryLowTier,
      // EQ(0X14, 2, 1,OpCode.Tier.VeryLowTier), // (0x14) Equality comparison
      VER_BASIC_3_0_0 | OP_2_1_VeryLowTier,
      // ISZERO(0x15, 1, 1,OpCode.Tier.VeryLowTier), // (0x15) Negation operation
      VER_BASIC_3_0_0 | OP_1_1_VeryLowTier,
      // AND(0x16, 2, 1,OpCode.Tier.VeryLowTier), // (0x16) Bitwise AND operation
      VER_BASIC_3_0_0 | OP_2_1_VeryLowTier,
      // OR(0x17, 2, 1,OpCode.Tier.VeryLowTier), // (0x17) Bitwise OR operation
      VER_BASIC_3_0_0 | OP_2_1_VeryLowTier,
      // XOR(0x18, 2, 1,OpCode.Tier.VeryLowTier), // (0x18) Bitwise XOR operation
      VER_BASIC_3_0_0 | OP_2_1_VeryLowTier,
      // NOT(0x19, 1, 1,OpCode.Tier.VeryLowTier), // (0x19) Bitwise NOT operationr
      VER_BASIC_3_0_0 | OP_1_1_VeryLowTier,
      // BYTE(0x1a, 2, 1,OpCode.Tier.VeryLowTier), // (0x1a) Retrieve single byte from word
      VER_BASIC_3_0_0 | OP_2_1_VeryLowTier,
      // SHL(0x1b, 2, 1,OpCode.Tier.VeryLowTier), // (0x1b) Shift left
      VER_CONSTANTINOPLE_3_6_0 | OP_2_1_VeryLowTier,
      // SHR(0x1c, 2, 1,OpCode.Tier.VeryLowTier), // (0x1c) Logical shift right
      VER_CONSTANTINOPLE_3_6_0 | OP_2_1_VeryLowTier,
      // SAR(0x1d, 2, 1,OpCode.Tier.VeryLowTier), // (0x1d) Arithmetic shift right
      VER_CONSTANTINOPLE_3_6_0 | OP_2_1_VeryLowTier,

      /* 0x1e ~ 0x1f is invalid */
      0, 0,

      /*  Cryptographic Operations    */
      // SHA3(0x20, 2, 1,OpCode.Tier.SpecialTier), // (0x20) Compute SHA3-256 hash
      VER_BASIC_3_0_0 | OP_2_1_SpecialTier,

      /* 0x21 ~ 0x2f is invalid */
      0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,

      /*  Environmental Information   */
      // ADDRESS(0x30, 0, 1,OpCode.Tier.BaseTier), // (0x30)  Get address of currently executing account
      VER_BASIC_3_0_0 | OP_0_1_BaseTier,
      // BALANCE(0x31, 1, 1,OpCode.Tier.ExtTier), // (0x31) Get balance of the given account
      VER_BASIC_3_0_0 | OP_1_1_ExtTier,
      // ORIGIN(0x32, 0, 1,OpCode.Tier.BaseTier), // (0x32) Get execution origination address
      VER_BASIC_3_0_0 | OP_0_1_BaseTier,
      // CALLER(0x33, 0, 1,OpCode.Tier.BaseTier), // (0x33) Get caller address
      VER_BASIC_3_0_0 | OP_0_1_BaseTier,
      // CALLVALUE(0x34, 0, 1,OpCode.Tier.BaseTier), // (0x34) Get deposited value by the instruction/transaction responsible for this execution
      VER_BASIC_3_0_0 | OP_0_1_BaseTier,
      // CALLDATALOAD(0x35, 1, 1,OpCode.Tier.VeryLowTier), // (0x35) Get input data of current environment
      VER_BASIC_3_0_0 | OP_1_1_VeryLowTier,
      // CALLDATASIZE(0x36, 0, 1,OpCode.Tier.BaseTier), // (0x36) Get size of input data in current environment
      VER_BASIC_3_0_0 | OP_0_1_BaseTier,
      // CALLDATACOPY(0x37, 3, 0,OpCode.Tier.VeryLowTier), // (0x37) Copy input data in current environment to memory
      VER_BASIC_3_0_0 | OP_3_0_VeryLowTier,
      // CODESIZE(0x38, 0, 1,OpCode.Tier.BaseTier), // (0x38) Get size of code running in current environment
      VER_BASIC_3_0_0 | OP_0_1_BaseTier,
      // CODECOPY(0x39, 3, 0,OpCode.Tier.VeryLowTier), // (0x39) Copy code running in current environment to memory // [len code_start mem_start CODECOPY]
      VER_BASIC_3_0_0 | OP_3_0_VeryLowTier,
      // GASPRICE(0x3a, 0, 1,OpCode.Tier.BaseTier), // (0x3a) Get price of gas in current environment
      VER_BASIC_3_0_0 | OP_0_1_BaseTier,
      // EXTCODESIZE(0x3b, 1, 1,OpCode.Tier.ExtTier), // (0x3b) Get size of code running in current environment with given offset
      VER_BASIC_3_0_0 | OP_1_1_ExtTier,
      // EXTCODECOPY(0x3c, 4, 0,OpCode.Tier.ExtTier), // (0x3c) Copy code running in current environment to memory with given offset
      VER_BASIC_3_0_0 | OP_4_0_ExtTier,
      // RETURNDATASIZE(0x3d, 0, 1,OpCode.Tier.BaseTier),
      VER_BASIC_3_0_0 | OP_0_1_BaseTier,
      // RETURNDATACOPY(0x3e, 3, 0,OpCode.Tier.VeryLowTier),
      VER_BASIC_3_0_0 | OP_3_0_VeryLowTier,
      // EXTCODEHASH(0x3f, 1, 1,OpCode.Tier.ExtTier), // (0x3f) Returns the keccak256 hash of a contract’s code
      VER_CONSTANTINOPLE_3_6_0 | OP_1_1_ExtTier,

      /*  Block Information   */
      // BLOCKHASH(0x40, 1, 1,OpCode.Tier.ExtTier), // (0x40) Get hash of most recent complete block
      VER_BASIC_3_0_0 | OP_1_1_ExtTier,
      // COINBASE(0x41, 0, 1,OpCode.Tier.BaseTier), // (0x41) Get the block’s coinbase address
      VER_BASIC_3_0_0 | OP_0_1_BaseTier,
      // TIMESTAMP(0x42, 0, 1,OpCode.Tier.BaseTier), // (x042) Get the block’s timestamp
      VER_BASIC_3_0_0 | OP_0_1_BaseTier,
      // NUMBER(0x43, 0, 1,OpCode.Tier.BaseTier), // (0x43) Get the block’s number
      VER_BASIC_3_0_0 | OP_0_1_BaseTier,
      // DIFFICULTY(0x44, 0, 1,OpCode.Tier.BaseTier), // (0x44) Get the block’s difficulty
      VER_BASIC_3_0_0 | OP_0_1_BaseTier,
      // GASLIMIT(0x45, 0, 1,OpCode.Tier.BaseTier), // (0x45) Get the block’s gas limit
      VER_BASIC_3_0_0 | OP_0_1_BaseTier,
      // CHAINID(0x46, 0, 1,OpCode.Tier.BaseTier), // (0x46) Get the chain id
      VER_ISTANBUL_4_1_0 | OP_0_1_BaseTier,
      // SELFBALANCE(0x47, 0, 1, Tier.LowTier), // (0x47) Get current account balance
      VER_ISTANBUL_4_1_0 | OP_0_1_LowTier,

      /* 0x48 ~ 0x4f is invalid */
      0,0,0,0,0,0,0,0,

      /*  Memory, Storage and Flow Operations */
      // POP(0x50, 1, 0,OpCode.Tier.BaseTier), // (0x50) Remove item from stack
      VER_BASIC_3_0_0 | OP_1_0_BaseTier,
      // MLOAD(0x51, 1, 1,OpCode.Tier.VeryLowTier), // (0x51) Load word from memory
      VER_BASIC_3_0_0 | OP_1_1_VeryLowTier,
      // MSTORE(0x52, 2, 0,OpCode.Tier.VeryLowTier), // (0x52) Save word to memory
      VER_BASIC_3_0_0 | OP_2_0_VeryLowTier,

      // MSTORE8(0x53, 2, 0, OpCode.Tier.VeryLowTier), //  (0x53) Save byte to memory
      VER_BASIC_3_0_0 | OP_2_0_VeryLowTier,
      // SLOAD(0x54, 1, 1, OpCode.Tier.SpecialTier), //  (0x54) Load word from storage
      VER_BASIC_3_0_0 | OP_1_1_SpecialTier,
      // SSTORE(0x55, 2, 0, OpCode.Tier.SpecialTier), //  (0x55) Save word to storage
      VER_BASIC_3_0_0 | OP_2_0_SpecialTier,
      // JUMP(0x56, 1, 0, OpCode.Tier.MidTier), //  (0x56) Alter the program counter
      VER_BASIC_3_0_0 | OP_1_0_MidTier,
      // JUMPI(0x57, 2, 0, OpCode.Tier.HighTier), //  (0x57) Conditionally alter the program counter
      VER_BASIC_3_0_0 | OP_2_0_HighTier,
      // PC(0x58, 0, 1, OpCode.Tier.BaseTier), //  (0x58) Get the program counter
      VER_BASIC_3_0_0 | OP_0_1_BaseTier,
      // MSIZE(0x59, 0, 1, OpCode.Tier.BaseTier), //  (0x59) Get the size of active memory
      VER_BASIC_3_0_0 | OP_0_1_BaseTier,
      // GAS(0x5a, 0, 1, OpCode.Tier.BaseTier), //  (0x5a) Get the amount of available gas
      VER_BASIC_3_0_0 | OP_0_1_BaseTier,
      // JUMPDEST(0x5b, 0, 0, OpCode.Tier.SpecialTier), //  (0x5b)
      VER_BASIC_3_0_0 | OP_0_0_SpecialTier,

      /* 0x5c ~ 0x5f is invalid */
      0,0,0,0,

      /*  Push Operations */
      // PUSH1(0x60, 0, 1, OpCode.Tier.VeryLowTier), //  (0x60) Place 1-byte item on stack
      VER_BASIC_3_0_0 | OP_0_1_VeryLowTier,
      // PUSH2(0x61, 0, 1, OpCode.Tier.VeryLowTier), //  (0x61) Place 2-byte item on stack
      VER_BASIC_3_0_0 | OP_0_1_VeryLowTier,
      // PUSH3(0x62, 0, 1, OpCode.Tier.VeryLowTier), //  (0x62) Place 3-byte item on stack
      VER_BASIC_3_0_0 | OP_0_1_VeryLowTier,
      // PUSH4(0x63, 0, 1, OpCode.Tier.VeryLowTier), //  (0x63) Place 4-byte item on stack
      VER_BASIC_3_0_0 | OP_0_1_VeryLowTier,
      // PUSH5(0x64, 0, 1, OpCode.Tier.VeryLowTier), //  (0x64) Place 5-byte item on stack
      VER_BASIC_3_0_0 | OP_0_1_VeryLowTier,
      // PUSH6(0x65, 0, 1, OpCode.Tier.VeryLowTier), //  (0x65) Place 6-byte item on stack
      VER_BASIC_3_0_0 | OP_0_1_VeryLowTier,
      // PUSH7(0x66, 0, 1, OpCode.Tier.VeryLowTier), //  (0x66) Place 7-byte item on stack
      VER_BASIC_3_0_0 | OP_0_1_VeryLowTier,
      // PUSH8(0x67, 0, 1, OpCode.Tier.VeryLowTier), //  (0x67) Place 8-byte item on stack
      VER_BASIC_3_0_0 | OP_0_1_VeryLowTier,
      // PUSH9(0x68, 0, 1, OpCode.Tier.VeryLowTier), //  (0x68) Place 9-byte item on stack
      VER_BASIC_3_0_0 | OP_0_1_VeryLowTier,
      // PUSH10(0x69, 0, 1, OpCode.Tier.VeryLowTier), //  (0x69) Place 10-byte item on stack
      VER_BASIC_3_0_0 | OP_0_1_VeryLowTier,
      // PUSH11(0x6a, 0, 1, OpCode.Tier.VeryLowTier), //  (0x6a) Place 11-byte item on stack
      VER_BASIC_3_0_0 | OP_0_1_VeryLowTier,
      // PUSH12(0x6b, 0, 1, OpCode.Tier.VeryLowTier), //  (0x6b) Place 12-byte item on stack
      VER_BASIC_3_0_0 | OP_0_1_VeryLowTier,
      // PUSH13(0x6c, 0, 1, OpCode.Tier.VeryLowTier), //  (0x6c) Place 13-byte item on stack
      VER_BASIC_3_0_0 | OP_0_1_VeryLowTier,
      // PUSH14(0x6d, 0, 1, OpCode.Tier.VeryLowTier), //  (0x6d) Place 14-byte item on stack
      VER_BASIC_3_0_0 | OP_0_1_VeryLowTier,
      // PUSH15(0x6e, 0, 1, OpCode.Tier.VeryLowTier), //  (0x6e) Place 15-byte item on stack
      VER_BASIC_3_0_0 | OP_0_1_VeryLowTier,
      // PUSH16(0x6f, 0, 1, OpCode.Tier.VeryLowTier), //  (0x6f) Place 16-byte item on stack
      VER_BASIC_3_0_0 | OP_0_1_VeryLowTier,

      // PUSH17(0x70, 0, 1, OpCode.Tier.VeryLowTier), //  (0x70) Place 17-byte item on stack
      VER_BASIC_3_0_0 | OP_0_1_VeryLowTier,
      // PUSH18(0x71, 0, 1, OpCode.Tier.VeryLowTier), //  (0x71) Place 18-byte item on stack
      VER_BASIC_3_0_0 | OP_0_1_VeryLowTier,
      // PUSH19(0x72, 0, 1, OpCode.Tier.VeryLowTier), //  (0x72) Place 19-byte item on stack
      VER_BASIC_3_0_0 | OP_0_1_VeryLowTier,
      // PUSH20(0x73, 0, 1, OpCode.Tier.VeryLowTier), //  (0x73) Place 20-byte item on stack
      VER_BASIC_3_0_0 | OP_0_1_VeryLowTier,
      // PUSH21(0x74, 0, 1, OpCode.Tier.VeryLowTier), //  (0x74) Place 21-byte item on stack
      VER_BASIC_3_0_0 | OP_0_1_VeryLowTier,
      // PUSH22(0x75, 0, 1, OpCode.Tier.VeryLowTier), //  (0x75) Place 22-byte item on stack
      VER_BASIC_3_0_0 | OP_0_1_VeryLowTier,
      // PUSH23(0x76, 0, 1, OpCode.Tier.VeryLowTier), //  (0x76) Place 23-byte item on stack
      VER_BASIC_3_0_0 | OP_0_1_VeryLowTier,
      // PUSH24(0x77, 0, 1, OpCode.Tier.VeryLowTier), //  (0x77) Place 24-byte item on stack
      VER_BASIC_3_0_0 | OP_0_1_VeryLowTier,
      // PUSH25(0x78, 0, 1, OpCode.Tier.VeryLowTier), //  (0x78) Place 25-byte item on stack
      VER_BASIC_3_0_0 | OP_0_1_VeryLowTier,
      // PUSH26(0x79, 0, 1, OpCode.Tier.VeryLowTier), //  (0x79) Place 26-byte item on stack
      VER_BASIC_3_0_0 | OP_0_1_VeryLowTier,
      // PUSH27(0x7a, 0, 1, OpCode.Tier.VeryLowTier), //  (0x7a) Place 27-byte item on stack
      VER_BASIC_3_0_0 | OP_0_1_VeryLowTier,
      // PUSH28(0x7b, 0, 1, OpCode.Tier.VeryLowTier), //  (0x7b) Place 28-byte item on stack
      VER_BASIC_3_0_0 | OP_0_1_VeryLowTier,
      // PUSH29(0x7c, 0, 1, OpCode.Tier.VeryLowTier), //  (0x7c) Place 29-byte item on stack
      VER_BASIC_3_0_0 | OP_0_1_VeryLowTier,
      // PUSH30(0x7d, 0, 1, OpCode.Tier.VeryLowTier), //  (0x7d) Place 30-byte item on stack
      VER_BASIC_3_0_0 | OP_0_1_VeryLowTier,
      // PUSH31(0x7e, 0, 1, OpCode.Tier.VeryLowTier), //  (0x7e) Place 31-byte item on stack
      VER_BASIC_3_0_0 | OP_0_1_VeryLowTier,
      // PUSH32(0x7f, 0, 1, OpCode.Tier.VeryLowTier), //  (0x7f) Place 32-byte (full word) item on stack
      VER_BASIC_3_0_0 | OP_0_1_VeryLowTier,

      /*  Duplicate Nth item from the stack   */
      // DUP1(0x80, 1, 2, OpCode.Tier.VeryLowTier), //  (0x80) Duplicate 1st item on stack
      VER_BASIC_3_0_0 | ((1 << 5 | 2) << 4 | 2) << 5, // OP_1_2_VeryLowTier,
      // DUP2(0x81, 2, 3, OpCode.Tier.VeryLowTier), //  (0x81) Duplicate 2nd item on stack
      VER_BASIC_3_0_0 | ((2 << 5 | 3) << 4 | 2) << 5, // OP_2_3_VeryLowTier,
      // DUP3(0x82, 3, 4, OpCode.Tier.VeryLowTier), //  (0x82) Duplicate 3rd item on stack
      VER_BASIC_3_0_0 | ((3 << 5 | 4) << 4 | 2) << 5, // OP_3_4_VeryLowTier,
      // DUP4(0x83, 4, 5, OpCode.Tier.VeryLowTier), //  (0x83) Duplicate 4th item on stack
      VER_BASIC_3_0_0 | ((4 << 5 | 5) << 4 | 2) << 5, // OP_4_5_VeryLowTier,
      // DUP5(0x84, 5, 6, OpCode.Tier.VeryLowTier), //  (0x84) Duplicate 5th item on stack
      VER_BASIC_3_0_0 | ((5 << 5 | 6) << 4 | 2) << 5, // OP_5_6_VeryLowTier,
      // DUP6(0x85, 6, 7, OpCode.Tier.VeryLowTier), //  (0x85) Duplicate 6th item on stack
      VER_BASIC_3_0_0 | ((6 << 5 | 7) << 4 | 2) << 5, // OP_6_7_VeryLowTier,
      // DUP7(0x86, 7, 8, OpCode.Tier.VeryLowTier), //  (0x86) Duplicate 7th item on stack
      VER_BASIC_3_0_0 | ((7 << 5 | 8) << 4 | 2) << 5, // OP_7_8_VeryLowTier,
      // DUP8(0x87, 8, 9, OpCode.Tier.VeryLowTier), //  (0x87) Duplicate 8th item on stack
      VER_BASIC_3_0_0 | ((8 << 5 | 9) << 4 | 2) << 5, // OP_8_9_VeryLowTier,
      // DUP9(0x88, 9, 10, OpCode.Tier.VeryLowTier), //  (0x88) Duplicate 9th item on stack
      VER_BASIC_3_0_0 | ((9 << 5 | 10) << 4 | 2) << 5, // OP_9_10_VeryLowTier,
      // DUP10(0x89, 10, 11, OpCode.Tier.VeryLowTier), //  (0x89) Duplicate 10th item on stack
      VER_BASIC_3_0_0 | ((10 << 5 | 11) << 4 | 2) << 5, // OP_10_11_VeryLowTier,
      // DUP11(0x8a, 11, 12, OpCode.Tier.VeryLowTier), //  (0x8a) Duplicate 11th item on stack
      VER_BASIC_3_0_0 | ((11 << 5 | 12) << 4 | 2) << 5, // OP_11_12_VeryLowTier,
      // DUP12(0x8b, 12, 13, OpCode.Tier.VeryLowTier), //  (0x8b) Duplicate 12th item on stack
      VER_BASIC_3_0_0 | ((12 << 5 | 13) << 4 | 2) << 5, // OP_12_13_VeryLowTier,
      // DUP13(0x8c, 13, 14, OpCode.Tier.VeryLowTier), //  (0x8c) Duplicate 13th item on stack
      VER_BASIC_3_0_0 | ((13 << 5 | 14) << 4 | 2) << 5, // OP_13_14_VeryLowTier,
      // DUP14(0x8d, 14, 15, OpCode.Tier.VeryLowTier), //  (0x8d) Duplicate 14th item on stack
      VER_BASIC_3_0_0 | ((14 << 5 | 15) << 4 | 2) << 5, // OP_14_15_VeryLowTier,
      // DUP15(0x8e, 15, 16, OpCode.Tier.VeryLowTier), //  (0x8e) Duplicate 15th item on stack
      VER_BASIC_3_0_0 | ((15 << 5 | 16) << 4 | 2) << 5, // OP_15_16_VeryLowTier,
      // DUP16(0x8f, 16, 17, OpCode.Tier.VeryLowTier), //  (0x8f) Duplicate 16th item on stack
      VER_BASIC_3_0_0 | ((16 << 5 | 17) << 4 | 2) << 5, // OP_16_17_VeryLowTier,

      /*  Swap the Nth item from the stack with the top   */
      // SWAP1(0x90, 2, 2, OpCode.Tier.VeryLowTier), //  (0x90) Exchange 2nd item from stack with the top
      VER_BASIC_3_0_0 | ((2 << 5 | 2) << 4 | 2) << 5, // OP_2_2_VeryLowTier,
      // SWAP2(0x91, 3, 3, OpCode.Tier.VeryLowTier), //  (0x91) Exchange 3rd item from stack with the top
      VER_BASIC_3_0_0 | ((3 << 5 | 3) << 4 | 2) << 5, // OP_3_3_VeryLowTier,
      // SWAP3(0x92, 4, 4, OpCode.Tier.VeryLowTier), //  (0x92) Exchange 4th item from stack with the top
      VER_BASIC_3_0_0 | ((4 << 5 | 4) << 4 | 2) << 5, // OP_4_4_VeryLowTier,
      // SWAP4(0x93, 5, 5, OpCode.Tier.VeryLowTier), //  (0x93) Exchange 5th item from stack with the top
      VER_BASIC_3_0_0 | ((5 << 5 | 5) << 4 | 2) << 5, // OP_5_5_VeryLowTier,
      // SWAP5(0x94, 6, 6, OpCode.Tier.VeryLowTier), //  (0x94) Exchange 6th item from stack with the top
      VER_BASIC_3_0_0 | ((6 << 5 | 6) << 4 | 2) << 5, // OP_6_6_VeryLowTier,
      // SWAP6(0x95, 7, 7, OpCode.Tier.VeryLowTier), //  (0x95) Exchange 7th item from stack with the top
      VER_BASIC_3_0_0 | ((7 << 5 | 7) << 4 | 2) << 5, // OP_7_7_VeryLowTier,
      // SWAP7(0x96, 8, 8, OpCode.Tier.VeryLowTier), //  (0x96) Exchange 8th item from stack with the top
      VER_BASIC_3_0_0 | ((8 << 5 | 8) << 4 | 2) << 5, // OP_8_8_VeryLowTier,
      // SWAP8(0x97, 9, 9, OpCode.Tier.VeryLowTier), //  (0x97) Exchange 9th item from stack with the top
      VER_BASIC_3_0_0 | ((9 << 5 | 9) << 4 | 2) << 5, // OP_9_9_VeryLowTier,
      // SWAP9(0x98, 10, 10, OpCode.Tier.VeryLowTier), //  (0x98) Exchange 10th item from stack with the top
      VER_BASIC_3_0_0 | ((10 << 5 | 10) << 4 | 2) << 5, // OP_10_10_VeryLowTier,
      // SWAP10(0x99, 11, 11, OpCode.Tier.VeryLowTier), //  (0x99) Exchange 11th item from stack with the top
      VER_BASIC_3_0_0 | ((11 << 5 | 11) << 4 | 2) << 5, // OP_11_11_VeryLowTier,
      // SWAP11(0x9a, 12, 12, OpCode.Tier.VeryLowTier), //  (0x9a) Exchange 12th item from stack with the top
      VER_BASIC_3_0_0 | ((12 << 5 | 12) << 4 | 2) << 5, // OP_12_12_VeryLowTier,
      // SWAP12(0x9b, 13, 13, OpCode.Tier.VeryLowTier), //  (0x9b) Exchange 13th item from stack with the top
      VER_BASIC_3_0_0 | ((13 << 5 | 13) << 4 | 2) << 5, // OP_13_13_VeryLowTier,
      // SWAP13(0x9c, 14, 14, OpCode.Tier.VeryLowTier), //  (0x9c) Exchange 14th item from stack with the top
      VER_BASIC_3_0_0 | ((14 << 5 | 14) << 4 | 2) << 5, // OP_14_14_VeryLowTier,
      // SWAP14(0x9d, 15, 15, OpCode.Tier.VeryLowTier), //  (0x9d) Exchange 15th item from stack with the top
      VER_BASIC_3_0_0 | ((15 << 5 | 15) << 4 | 2) << 5, // OP_15_15_VeryLowTier,
      // SWAP15(0x9e, 16, 16, OpCode.Tier.VeryLowTier), //  (0x9e) Exchange 16th item from stack with the top
      VER_BASIC_3_0_0 | ((16 << 5 | 16) << 4 | 2) << 5, // OP_16_16_VeryLowTier,
      // SWAP16(0x9f, 17, 17, OpCode.Tier.VeryLowTier), //  (0x9f) Exchange 17th item from stack with the top
      VER_BASIC_3_0_0 | ((17 << 5 | 17) << 4 | 2) << 5, // OP_17_17_VeryLowTier,

      /**
       * (0xa[n]) log some data for some addres with 0..n tags [addr [tag0..tagn] data]
       */
      // LOG0(0xa0, 2, 0, OpCode.Tier.SpecialTier),
      VER_BASIC_3_0_0 | OP_2_0_SpecialTier,
      // LOG1(0xa1, 3, 0, OpCode.Tier.SpecialTier),
      VER_BASIC_3_0_0 | ((3 << 5 | 0) << 4 | 7) << 5, // OP_3_0_SpecialTier,
      // LOG2(0xa2, 4, 0, OpCode.Tier.SpecialTier),
      VER_BASIC_3_0_0 | ((4 << 5 | 0) << 4 | 7) << 5, // OP_4_0_SpecialTier,
      // LOG3(0xa3, 5, 0, OpCode.Tier.SpecialTier),
      VER_BASIC_3_0_0 | ((5 << 5 | 0) << 4 | 7) << 5, // OP_5_0_SpecialTier,
      // LOG4(0xa4, 6, 0, OpCode.Tier.SpecialTier),
      VER_BASIC_3_0_0 | ((6 << 5 | 0) << 4 | 7) << 5, // OP_6_0_SpecialTier,

      /* 0xa5 ~ 0xaf is invalid */
      0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,

      /* 0xb0 ~ 0xbf is invalid */
      0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,

      /* 0xc0 ~ 0xcf is invalid */
      0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,

      /*  System operations   */
      // CALLTOKEN(0xd0, 8, 1,OpCode.Tier.SpecialTier, CallFlags.Call, CallFlags.HasValue), // (0xd0) Message-call into an account with trc10 token
      VER_TRC10_3_2_0 | OP_8_1_SpecialTier | 0b10100, // Call  = 0b10000, hasValue = 0b00100
      // TOKENBALANCE(0xd1, 2, 1, OpCode.Tier.ExtTier),
      VER_TRC10_3_2_0 | OP_2_1_ExtTier,
      // CALLTOKENVALUE(0xd2, 0, 1, OpCode.Tier.BaseTier),
      VER_TRC10_3_2_0 | OP_0_1_BaseTier,
      // CALLTOKENID(0xd3, 0, 1, OpCode.Tier.BaseTier),
      VER_TRC10_3_2_0 | OP_0_1_BaseTier,

      // ISCONTRACT(0xd4, 1, 1, OpCode.Tier.ExtTier),
      VER_SOLIDITY059_3_6_5 | OP_1_1_ExtTier,
      // STAKE(0xd5, 2, 1, OpCode.Tier.ExtTier),
      VER_STAKING_4_2_0 | OP_2_1_ExtTier,
      // UNSTAKE(0xd6, 0, 1, OpCode.Tier.ExtTier),
      VER_STAKING_4_2_0 | OP_0_1_ExtTier,
      // WITHDRAWREWARD(0xd7, 1, 1, OpCode.Tier.ExtTier),
      VER_STAKING_4_2_0 | OP_1_1_ExtTier,
      // REWARDBALANCE(0xd8, 1, 1, OpCode.Tier.ExtTier),
      VER_STAKING_4_2_0 | OP_1_1_ExtTier,
      // ISSRCANDIDATE(0xd9, 1, 1, OpCode.Tier.ExtTier),
      VER_STAKING_4_2_0 | OP_1_1_ExtTier,
      // TOKENISSUE(0xda, 1, 1, OpCode.Tier.HighTier),
      VER_STAKING_4_2_0 | OP_1_1_HighTier,
      // UPDATEASSET(0xdb, 1, 1, OpCode.Tier.HighTier),
      VER_STAKING_4_2_0 | OP_1_1_HighTier,

      /* 0xdc ~ 0xdf is invalid */
      0, 0, 0, 0,

      /* 0xe0 ~ 0xef is invalid */
      0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,

      // CREATE(0xf0, 3, 1,OpCode.Tier.SpecialTier),   // (0xf0) Create a new account with associated code // [in_size] [in_offs] [gas_val] CREATE
      VER_BASIC_3_0_0 | OP_3_1_SpecialTier,

      // CALL(0xf1, 7, 1,OpCode.Tier.SpecialTier, CallFlags.Call, CallFlags.HasValue), // (cxf1) Message-call into an account
      VER_BASIC_3_0_0 | OP_7_1_SpecialTier | 0b10100, // Call  = 0b10000, hasValue = 0b00100

      /* [out_data_size] [out_data_start] [in_data_size] [in_data_start] [value] [to_addr] */
      // CALLCODE(0xf2, 7, 1,OpCode.Tier.SpecialTier, CallFlags.Call, CallFlags.HasValue, CallFlags.Stateless), // (0xf2) Calls self, but grabbing the code from the TO argument instead of from one's own
      VER_BASIC_3_0_0 | OP_7_1_SpecialTier | 0b11100, // Call  = 0b10000, stateless = 0b01000, hasValue = 0b00100
      // RETURN(0xf3, 2, 0,OpCode.Tier.ZeroTier), // (0xf3) Halt execution returning output data
      VER_BASIC_3_0_0 | OP_2_0_ZeroTier,

      /**
       * (0xf4)  similar in idea to CALLCODE, except that it propagates the sender and value from the
       * parent scope to the child scope, ie. the call created has the same sender and value as the
       * original call. also the Value parameter is omitted for this opCode
       */
      // DELEGATECALL(0xf4, 6, 1,OpCode.Tier.SpecialTier, CallFlags.Call, CallFlags.Stateless, CallFlags.Delegate),
      VER_BASIC_3_0_0 | OP_6_1_SpecialTier | 0b11001, // Call  = 0b10000, stateless = 0b01000, delegate = 0b00001

      // CREATE2(0xf5, 4, 1,OpCode.Tier.SpecialTier), // (0xf5) Skinny CREATE2, same as CREATE but with deterministic address
      VER_CONSTANTINOPLE_3_6_0 | OP_4_1_SpecialTier,

      /* 0xf6 ~ 0xf9 is invalid */
      0, 0, 0, 0,

      /**
       * opcode that can be used to call another contract (or itself) while disallowing any
       * modifications to the state during the call (and its subcalls, if present). Any opcode that
       * attempts to perform such a modification (see below for details) will result in an exception
       * instead of performing the modification.
       */
      // STATICCALL(0xfa, 6, 1,OpCode.Tier.SpecialTier, CallFlags.Call, CallFlags.Static),
      VER_BASIC_3_0_0 | OP_6_1_SpecialTier | 0b10010, // Call  = 0b10000, Static  = 0b00010

      /* 0xfb ~ 0xfc is invalid */
      0, 0,

      /**
       * (0xfd) The `REVERT` instruction will stop execution, roll back all state changes done so far
       * and provide a pointer to a memory section, which can be interpreted as an error code or
       * message. While doing so, it will not consume all the remaining gas.
       */
      // REVERT(0xfd, 2, 0,OpCode.Tier.ZeroTier),
      VER_BASIC_3_0_0 | OP_2_0_ZeroTier,
      /* 0xfe is invalid */
      0,
      // SUICIDE(0xff, 1, 0,OpCode.Tier.ZeroTier) // (0xff) Halt execution and register account for later deletion
      VER_BASIC_3_0_0 | OP_1_0_ZeroTier
  };

  private static class Tiers {
    public static final int ZeroTier = 0;
    public static final int BaseTier = 1;
    public static final int VeryLowTier = 2;
    public static final int LowTier = 3;
    public static final int MidTier = 4;
    public static final int HighTier = 5;
    public static final int ExtTier = 6;
    public static final int SpecialTier = 7;
    public static final int InvalidTier = 8;
  }
  private static final int[] tierLevels = new int[9];
  static {
    tierLevels[Tiers.ZeroTier] = 0;
    tierLevels[Tiers.BaseTier] = 2;
    tierLevels[Tiers.VeryLowTier] = 3;
    tierLevels[Tiers.LowTier] = 5;
    tierLevels[Tiers.MidTier] = 8;
    tierLevels[Tiers.HighTier] = 10;
    tierLevels[Tiers.ExtTier] = 20;
    tierLevels[Tiers.SpecialTier] = 1;
    tierLevels[Tiers.InvalidTier] = 0;
  }

//
//  /**
//   * Halts execution (0x00)
//   */
//  public static final int STOP = 0x00;
//
//  /*  Arithmetic Operations   */
//
//  /**
//   * (0x01) Addition operation
//   */
//  public static final int ADD = 0x01;
//  /**
//   * (0x02) Multiplication operation
//   */
//  public static final int MUL = 0x02;
//  /**
//   * (0x03) Subtraction operations
//   */
//  public static final int SUB = 0x03;
//  /**
//   * (0x04) Integer division operation
//   */
//  public static final int DIV = 0x04;
//  /**
//   * (0x05) Signed integer division operation
//   */
//  public static final int SDIV = 0x05;
//  /**
//   * (0x06) Modulo remainder operation
//   */
//  public static final int MOD = 0x06;
//  /**
//   * (0x07) Signed modulo remainder operation
//   */
//  public static final int SMOD = 0x07;
//  /**
//   * (0x08) Addition combined with modulo remainder operation
//   */
//  public static final int ADDMOD = 0x08;
//  /**
//   * (0x09) Multiplication combined with modulo remainder operation
//   */
//  public static final int MULMOD = 0x09;
//  /**
//   * (0x0a) Exponential operation
//   */
//  public static final int EXP = 0x0a;
//  /**
//   * (0x0b) Extend length of signed integer
//   */
//  public static final int SIGNEXTEND = 0x0b;
//
//  /*  Bitwise Logic & Comparison Operations   */
//
//  /**
//   * (0x10) Less-than comparison
//   */
//  public static final int LT = 0X10;
//  /**
//   * (0x11) Greater-than comparison
//   */
//  public static final int GT = 0X11;
//  /**
//   * (0x12) Signed less-than comparison
//   */
//  public static final int SLT = 0X12;
//  /**
//   * (0x13) Signed greater-than comparison
//   */
//  public static final int SGT = 0X13;
//  /**
//   * (0x14) Equality comparison
//   */
//  public static final int EQ = 0X14;
//  /**
//   * (0x15) Negation operation
//   */
//  public static final int ISZERO = 0x15;
//  /**
//   * (0x16) Bitwise AND operation
//   */
//  public static final int AND = 0x16;
//  /**
//   * (0x17) Bitwise OR operation
//   */
//  public static final int OR = 0x17;
//  /**
//   * (0x18) Bitwise XOR operation
//   */
//  public static final int XOR = 0x18;
//  /**
//   * (0x19) Bitwise NOT operationr
//   */
//  public static final int NOT = 0x19;
//  /**
//   * (0x1a) Retrieve single byte from word
//   */
//  public static final int BYTE = 0x1a;
//  /**
//   * (0x1b) Shift left
//   */
//  public static final int SHL = 0x1b;
//  /**
//   * (0x1c) Logical shift right
//   */
//  public static final int SHR = 0x1c;
//  /**
//   * (0x1d) Arithmetic shift right
//   */
//  public static final int SAR = 0x1d;
//
//  /*  Cryptographic Operations    */
//
//  /**
//   * (0x20) Compute SHA3-256 hash
//   */
//  public static final int SHA3 = 0x20;
//
//  /*  Environmental Information   */
//
//  /**
//   * (0x30)  Get address of currently executing account
//   */
//  public static final int ADDRESS = 0x30;
//  /**
//   * (0x31) Get balance of the given account
//   */
//  public static final int BALANCE = 0x31;
//  /**
//   * (0x32) Get execution origination address
//   */
//  public static final int ORIGIN = 0x32;
//  /**
//   * (0x33) Get caller address
//   */
//  public static final int CALLER = 0x33;
//  /**
//   * (0x34) Get deposited value by the instruction/transaction responsible for this execution
//   */
//  public static final int CALLVALUE = 0x34;
//  /**
//   * (0x35) Get input data of current environment
//   */
//  public static final int CALLDATALOAD = 0x35;
//  /**
//   * (0x36) Get size of input data in current environment
//   */
//  public static final int CALLDATASIZE = 0x36;
//  /**
//   * (0x37) Copy input data in current environment to memory
//   */
//  public static final int CALLDATACOPY = 0x37;
//  /**
//   * (0x38) Get size of code running in current environment
//   */
//  public static final int CODESIZE = 0x38;
//  /**
//   * (0x39) Copy code running in current environment to memory
//   */
//  public static final int CODECOPY = 0x39;
//
//  public static final int RETURNDATASIZE = 0x3d;
//
//  public static final int RETURNDATACOPY = 0x3e;
//  /**
//   * (0x3a) Get price of gas in current environment
//   */
//  public static final int GASPRICE = 0x3a;
//  /**
//   * (0x3b) Get size of code running in current environment with given offset
//   */
//  public static final int EXTCODESIZE = 0x3b;
//  /**
//   * (0x3c) Copy code running in current environment to memory with given offset
//   */
//  public static final int EXTCODECOPY = 0x3c;
//  /**
//   * (0x3f) Returns the keccak256 hash of a contract’s code
//   */
//  public static final int EXTCODEHASH = 0x3f;
//
//  /*  Block Information   */
//
//  /**
//   * (0x40) Get hash of most recent complete block
//   */
//  public static final int BLOCKHASH = 0x40;
//  /**
//   * (0x41) Get the block’s coinbase address
//   */
//  public static final int COINBASE = 0x41;
//  /**
//   * (x042) Get the block’s timestamp
//   */
//  public static final int TIMESTAMP = 0x42;
//  /**
//   * (0x43) Get the block’s number
//   */
//  public static final int NUMBER = 0x43;
//  /**
//   * (0x44) Get the block’s difficulty
//   */
//  public static final int DIFFICULTY = 0x44;
//  /**
//   * (0x45) Get the block’s gas limit
//   */
//  public static final int GASLIMIT = 0x45;
//  /**
//   *  (0x46) Get the chain id
//   */
//  public static final int CHAINID = 0x46;
//  /**
//   *  (0x47) Get current account balance
//   */
//  public static final int SELFBALANCE = 0x47;
//
//
//  /*  Memory, Storage and Flow Operations */
//
//  /**
//   * (0x50) Remove item from stack
//   */
//  public static final int POP = 0x50;
//  /**
//   * (0x51) Load word from memory
//   */
//  public static final int MLOAD = 0x51;
//  /**
//   * (0x52) Save word to memory
//   */
//  public static final int MSTORE = 0x52;
//  /**
//   * (0x53) Save byte to memory
//   */
//  public static final int MSTORE8 = 0x53;
//  /**
//   * (0x54) Load word from storage
//   */
//  public static final int SLOAD = 0x54;
//  /**
//   * (0x55) Save word to storage
//   */
//  public static final int SSTORE = 0x55;
//  /**
//   * (0x56) Alter the program counter
//   */
//  public static final int JUMP = 0x56;
//  /**
//   * (0x57) Conditionally alter the program counter
//   */
//  public static final int JUMPI = 0x57;
//  /**
//   * (0x58) Get the program counter
//   */
//  public static final int PC = 0x58;
//  /**
//   * (0x59) Get the size of active memory
//   */
//  public static final int MSIZE = 0x59;
//  /**
//   * (0x5a) Get the amount of available gas
//   */
//  public static final int GAS = 0x5a;
//  /**
//   * (0x5b)
//   */
//  public static final int JUMPDEST = 0x5b;
//
//  /*  Push Operations */
//
//  /**
//   * (0x60) Place 1-byte item on stack
//   */
//  public static final int PUSH1 = 0x60;
//  /**
//   * (0x61) Place 2-byte item on stack
//   */
//  public static final int PUSH2 = 0x61;
//  /**
//   * (0x62) Place 3-byte item on stack
//   */
//  public static final int PUSH3 = 0x62;
//  /**
//   * (0x63) Place 4-byte item on stack
//   */
//  public static final int PUSH4 = 0x63;
//  /**
//   * (0x64) Place 5-byte item on stack
//   */
//  public static final int PUSH5 = 0x64;
//  /**
//   * (0x65) Place 6-byte item on stack
//   */
//  public static final int PUSH6 = 0x65;
//  /**
//   * (0x66) Place 7-byte item on stack
//   */
//  public static final int PUSH7 = 0x66;
//  /**
//   * (0x67) Place 8-byte item on stack
//   */
//  public static final int PUSH8 = 0x67;
//  /**
//   * (0x68) Place 9-byte item on stack
//   */
//  public static final int PUSH9 = 0x68;
//  /**
//   * (0x69) Place 10-byte item on stack
//   */
//  public static final int PUSH10 = 0x69;
//  /**
//   * (0x6a) Place 11-byte item on stack
//   */
//  public static final int PUSH11 = 0x6a;
//  /**
//   * (0x6b) Place 12-byte item on stack
//   */
//  public static final int PUSH12 = 0x6b;
//  /**
//   * (0x6c) Place 13-byte item on stack
//   */
//  public static final int PUSH13 = 0x6c;
//  /**
//   * (0x6d) Place 14-byte item on stack
//   */
//  public static final int PUSH14 = 0x6d;
//  /**
//   * (0x6e) Place 15-byte item on stack
//   */
//  public static final int PUSH15 = 0x6e;
//  /**
//   * (0x6f) Place 16-byte item on stack
//   */
//  public static final int PUSH16 = 0x6f;
//  /**
//   * (0x70) Place 17-byte item on stack
//   */
//  public static final int PUSH17 = 0x70;
//  /**
//   * (0x71) Place 18-byte item on stack
//   */
//  public static final int PUSH18 = 0x71;
//  /**
//   * (0x72) Place 19-byte item on stack
//   */
//  public static final int PUSH19 = 0x72;
//  /**
//   * (0x73) Place 20-byte item on stack
//   */
//  public static final int PUSH20 = 0x73;
//  /**
//   * (0x74) Place 21-byte item on stack
//   */
//  public static final int PUSH21 = 0x74;
//  /**
//   * (0x75) Place 22-byte item on stack
//   */
//  public static final int PUSH22 = 0x75;
//  /**
//   * (0x76) Place 23-byte item on stack
//   */
//  public static final int PUSH23 = 0x76;
//  /**
//   * (0x77) Place 24-byte item on stack
//   */
//  public static final int PUSH24 = 0x77;
//  /**
//   * (0x78) Place 25-byte item on stack
//   */
//  public static final int PUSH25 = 0x78;
//  /**
//   * (0x79) Place 26-byte item on stack
//   */
//  public static final int PUSH26 = 0x79;
//  /**
//   * (0x7a) Place 27-byte item on stack
//   */
//  public static final int PUSH27 = 0x7a;
//  /**
//   * (0x7b) Place 28-byte item on stack
//   */
//  public static final int PUSH28 = 0x7b;
//  /**
//   * (0x7c) Place 29-byte item on stack
//   */
//  public static final int PUSH29 = 0x7c;
//  /**
//   * (0x7d) Place 30-byte item on stack
//   */
//  public static final int PUSH30 = 0x7d;
//  /**
//   * (0x7e) Place 31-byte item on stack
//   */
//  public static final int PUSH31 = 0x7e;
//  /**
//   * (0x7f) Place 32-byte (full word) item on stack
//   */
//  public static final int PUSH32 = 0x7f;
//
//  /*  Duplicate Nth item from the stack   */
//
//  /**
//   * (0x80) Duplicate 1st item on stack
//   */
//  public static final int DUP1 = 0x80;
//  /**
//   * (0x81) Duplicate 2nd item on stack
//   */
//  public static final int DUP2 = 0x81;
//  /**
//   * (0x82) Duplicate 3rd item on stack
//   */
//  public static final int DUP3 = 0x82;
//  /**
//   * (0x83) Duplicate 4th item on stack
//   */
//  public static final int DUP4 = 0x83;
//  /**
//   * (0x84) Duplicate 5th item on stack
//   */
//  public static final int DUP5 = 0x84;
//  /**
//   * (0x85) Duplicate 6th item on stack
//   */
//  public static final int DUP6 = 0x85;
//  /**
//   * (0x86) Duplicate 7th item on stack
//   */
//  public static final int DUP7 = 0x86;
//  /**
//   * (0x87) Duplicate 8th item on stack
//   */
//  public static final int DUP8 = 0x87;
//  /**
//   * (0x88) Duplicate 9th item on stack
//   */
//  public static final int DUP9 = 0x88;
//  /**
//   * (0x89) Duplicate 10th item on stack
//   */
//  public static final int DUP10 = 0x89;
//  /**
//   * (0x8a) Duplicate 11th item on stack
//   */
//  public static final int DUP11 = 0x8a;
//  /**
//   * (0x8b) Duplicate 12th item on stack
//   */
//  public static final int DUP12 = 0x8b;
//  /**
//   * (0x8c) Duplicate 13th item on stack
//   */
//  public static final int DUP13 = 0x8c;
//  /**
//   * (0x8d) Duplicate 14th item on stack
//   */
//  public static final int DUP14 = 0x8d;
//  /**
//   * (0x8e) Duplicate 15th item on stack
//   */
//  public static final int DUP15 = 0x8e;
//  /**
//   * (0x8f) Duplicate 16th item on stack
//   */
//  public static final int DUP16 = 0x8f;
//
//  /*  Swap the Nth item from the stack with the top   */
//
//  /**
//   * (0x90) Exchange 2nd item from stack with the top
//   */
//  public static final int SWAP1 = 0x90;
//  /**
//   * (0x91) Exchange 3rd item from stack with the top
//   */
//  public static final int SWAP2 = 0x91;
//  /**
//   * (0x92) Exchange 4th item from stack with the top
//   */
//  public static final int SWAP3 = 0x92;
//  /**
//   * (0x93) Exchange 5th item from stack with the top
//   */
//  public static final int SWAP4 = 0x93;
//  /**
//   * (0x94) Exchange 6th item from stack with the top
//   */
//  public static final int SWAP5 = 0x94;
//  /**
//   * (0x95) Exchange 7th item from stack with the top
//   */
//  public static final int SWAP6 = 0x95;
//  /**
//   * (0x96) Exchange 8th item from stack with the top
//   */
//  public static final int SWAP7 = 0x96;
//  /**
//   * (0x97) Exchange 9th item from stack with the top
//   */
//  public static final int SWAP8 = 0x97;
//  /**
//   * (0x98) Exchange 10th item from stack with the top
//   */
//  public static final int SWAP9 = 0x98;
//  /**
//   * (0x99) Exchange 11th item from stack with the top
//   */
//  public static final int SWAP10 = 0x99;
//  /**
//   * (0x9a) Exchange 12th item from stack with the top
//   */
//  public static final int SWAP11 = 0x9a;
//  /**
//   * (0x9b) Exchange 13th item from stack with the top
//   */
//  public static final int SWAP12 = 0x9b;
//  /**
//   * (0x9c) Exchange 14th item from stack with the top
//   */
//  public static final int SWAP13 = 0x9c;
//  /**
//   * (0x9d) Exchange 15th item from stack with the top
//   */
//  public static final int SWAP14 = 0x9d;
//  /**
//   * (0x9e) Exchange 16th item from stack with the top
//   */
//  public static final int SWAP15 = 0x9e;
//  /**
//   * (0x9f) Exchange 17th item from stack with the top
//   */
//  public static final int SWAP16 = 0x9f;
//
//  /**
//   * (0xa[n]) log some data for some addres with 0..n tags [addr [tag0..tagn] data]
//   */
//  public static final int LOG0 = 0xa0;
//  public static final int LOG1 = 0xa1;
//  public static final int LOG2 = 0xa2;
//  public static final int LOG3 = 0xa3;
//  public static final int LOG4 = 0xa4;
//
//  /*  System operations   */
//
//  /**
//   * (0xd0) Message-call into an account with trc10 token
//   */
//  public static final int CALLTOKEN = 0xd0;
//
//  public static final int TOKENBALANCE = 0xd1;
//
//  public static final int CALLTOKENVALUE = 0xd2;
//
//  public static final int CALLTOKENID = 0xd3;
//
//  public static final int ISCONTRACT = 0xd4;
//
//  public static final int STAKE = 0xd5;
//
//  public static final int UNSTAKE = 0xd6;
//
//  public static final int WITHDRAWREWARD = 0xd7;
//
//  public static final int REWARDBALANCE = 0xd8;
//
//  public static final int ISSRCANDIDATE = 0xd9;
//
//  public static final int TOKENISSUE = 0xda;
//
//  public static final int UPDATEASSET = 0xdb;
//  /**
//   * (0xf0) Create a new account with associated code
//   */
//  public static final int CREATE = 0xf0;
//  /**
//   * (cxf1) Message-call into an account
//   */
//  public static final int CALL = 0xf1;
//  //       [out_data_size] [out_data_start] [in_data_size] [in_data_start] [value] [to_addr]
//  // [gas] CALL
//  /**
//   * (0xf2) Calls self, but grabbing the code from the TO argument instead of from one's own
//   * address
//   */
//  public static final int CALLCODE = 0xf2;
//  /**
//   * (0xf3) Halt execution returning output data
//   */
//  public static final int RETURN = 0xf3;
//
//  /**
//   * (0xf4)  similar in idea to CALLCODE, except that it propagates the sender and value from the
//   * parent scope to the child scope, ie. the call created has the same sender and value as the
//   * original call. also the Value parameter is omitted for this opCode
//   */
//  public static final int DELEGATECALL = 0xf4;
//
//  /**
//   * (0xf5) Skinny CREATE2, same as CREATE but with deterministic address
//   */
//  public static final int CREATE2 = 0xf5;
//
//  /**
//   * opcode that can be used to call another contract (or itself) while disallowing any
//   * modifications to the state during the call (and its subcalls, if present). Any opcode that
//   * attempts to perform such a modification (see below for details) will result in an exception
//   * instead of performing the modification.
//   */
//  public static final int STATICCALL = 0xfa;
//
//  /**
//   * (0xfd) The `REVERT` instruction will stop execution, roll back all state changes done so far
//   * and provide a pointer to a memory section, which can be interpreted as an error code or
//   * message. While doing so, it will not consume all the remaining gas.
//   */
//  public static final int REVERT = 0xfd;
//  /**
//   * (0xff) Halt execution and register account for later deletion
//   */
//  public static final int SUICIDE = 0xff;

  /*|<- 13 bits (bit-flag of HardForks) ->|<- 5 bits (require) ->|<- 5 bits (return) ->|<- 4 bits (tier) ->|<- 5 bits (call flags) -> */
  public static int getHardFork(int val) {
    return val >> 19 & 0b1_1111_1111_1111;
  }

  public static int getRequire(int val) {
    return val >> 14 & 0b11111;
  }

  public static int getRet(int val) {
    return val >> 9 & 0b11111;
  }

  public static int getTierLevel(int val) {
    return tierLevels[val >> 5 & 0b1111];
  }

  public static int getCallFlags(int val) {
    return val & 0b11111;
  }

  public static <T extends Number> String getOpName(T op) {
    return OpCode.code(op.byteValue()).name();
  }

  public static <T extends Number> boolean isNormalCall(T op) {
    return checkCallFlag(op.byteValue(), 1 << 4);
  }

  public static <T extends Number> boolean isStatelessCall(T op) {
    return checkCallFlag(op.byteValue(), 1 << 3);
  }

  public static <T extends Number> boolean hasCallValue(T op) {
    return checkCallFlag(op.byteValue(), 1 << 2);
  }

  public static <T extends Number> boolean isStaticCall(T op) {
    return checkCallFlag(op.byteValue(), 1 << 1);
  }

  public static <T extends Number> boolean isDelegateCall(T op) {
    return checkCallFlag(op.byteValue(), 1);
  }

  private static boolean checkCallFlag(byte op, int flag) {
    return (opsBasic[op & 0xff] & flag) != 0;
  }

  private static class CallFlags {
    /**
     * Indicates that opcode is a call
     */
    public static final int CALL = 0b10000;

    /**
     * Indicates that the code is executed in the context of the caller
     */
    public static final int Stateless = 0b01000;

    /**
     * Indicates that the opcode has value parameter (3rd on stack)
     */
    public static final int HasValue = 0b00100;

    /**
     * Indicates that any state modifications are disallowed during the call
     */
    public static final int Static = 0b00010;

    /**
     * Indicates that value and message sender are propagated from parent to child scope
     */
    public static final int Delegate = 0b00001;
  }

  private static boolean callFlagJudge(int callFlags, int callFlag) {
    return (callFlags & callFlag) > 0;
  }

  public static void main(String[] args) {
    System.out.println(getOpName(0xf0));
    System.out.println(getOpName((byte) 0xf0));
  }
}


