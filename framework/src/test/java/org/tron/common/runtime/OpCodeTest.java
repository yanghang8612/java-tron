package org.tron.common.runtime;

import com.googlecode.cqengine.query.simple.In;
import lombok.extern.slf4j.Slf4j;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.testng.Assert;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.vm.OpCode;
import org.tron.core.vm.process.OpCodeV2;

import java.util.HashSet;
import java.util.Set;

import static org.tron.common.runtime.TvmTestUtils.generateDeploySmartContractAndGetTransaction;
import static org.tron.common.runtime.TvmTestUtils.generateTriggerSmartContractAndGetTransaction;
import static org.tron.core.vm.process.OpCodeV2.*;


@Slf4j
public class OpCodeTest {

  /**
   * Init data.
   */
  @Before
  public void init() {

  }

  @Test
  public void OpCodeTest() throws ContractValidateException, ContractExeException {

    int[] opsBasic = OpCodeV2.opsBasic;
    Assert.assertEquals(opsBasic.length, 256, "ops length error");

    Set<Byte> opSetTrc10_3_2_0 = new HashSet<>();
    opSetTrc10_3_2_0.add(OpCode.CALLTOKEN.val());
    opSetTrc10_3_2_0.add(OpCode.TOKENBALANCE.val());
    opSetTrc10_3_2_0.add(OpCode.CALLTOKENVALUE.val());
    opSetTrc10_3_2_0.add(OpCode.CALLTOKENID.val());

    Set<Byte> opSetConstantinople_3_6_0 = new HashSet<>();
    opSetConstantinople_3_6_0.add(OpCode.CREATE2.val());
    opSetConstantinople_3_6_0.add(OpCode.SHL.val());
    opSetConstantinople_3_6_0.add(OpCode.SHR.val());
    opSetConstantinople_3_6_0.add(OpCode.SAR.val());
    opSetConstantinople_3_6_0.add(OpCode.EXTCODEHASH.val());

    Set<Byte> opSetSolidity059_3_6_5 = new HashSet<>();
    opSetSolidity059_3_6_5.add(OpCode.ISCONTRACT.val());

    Set<Byte> opSetIstanbul_4_1_0 = new HashSet<>();
    opSetIstanbul_4_1_0.add(OpCode.CHAINID.val());
    opSetIstanbul_4_1_0.add(OpCode.SELFBALANCE.val());

    Set<Byte> opSetStaking_4_2_0 = new HashSet<>();
    opSetStaking_4_2_0.add(OpCode.STAKE.val());
    opSetStaking_4_2_0.add(OpCode.UNSTAKE.val());
    opSetStaking_4_2_0.add(OpCode.WITHDRAWREWARD.val());
    opSetStaking_4_2_0.add(OpCode.REWARDBALANCE.val());
    opSetStaking_4_2_0.add(OpCode.ISSRCANDIDATE.val());
    opSetStaking_4_2_0.add(OpCode.TOKENISSUE.val());
    opSetStaking_4_2_0.add(OpCode.UPDATEASSET.val());

    int opCountBasic = 134;

    for (int i = 0; i < opsBasic.length; i++) {
      int val = opsBasic[i];
      OpCode op = OpCode.code((byte)i);
      Assert.assertEquals(val > 0, op != null, "ops [" + i + "] diff Value");
      if (op == null){
        continue;
      }

      int ver = val >> 19;
      int require = val >> 14 & 0b11111;
      int ret = val >> 9 & 0b11111;
      int tier = val >> 5 & 0b1111;
      int flag = val & 0b11111;
      Assert.assertTrue(ver > 0, "ops [" + op.name() + "] diff Has");
      Assert.assertEquals(require, op.require(), "ops [" + op.name() + "] diff Require");
      Assert.assertEquals(tier, op.getTier().ordinal(), "ops [" + op.name() + "] diff Tier");
      Assert.assertEquals(ret, op.ret(), "ops [" + op.name() + "] tier Ret");

      switch (ver << 19)
      {
        case VER_TRC10_3_2_0:
          Assert.assertTrue(opSetTrc10_3_2_0.contains((byte)(i)), "ops [" + op.name() + "] diff VER_TRC10_3_2_0");
          opSetTrc10_3_2_0.remove((byte)(i));
          break;
        case VER_CONSTANTINOPLE_3_6_0:
          Assert.assertTrue(opSetConstantinople_3_6_0.contains((byte)(i)), "ops [" + op.name() + "] diff VER_CONSTANTINOPLE_3_6_0");
          opSetConstantinople_3_6_0.remove((byte)(i));
          break;
        case VER_SOLIDITY059_3_6_5:
          Assert.assertTrue(opSetSolidity059_3_6_5.contains((byte)(i)), "ops [" + op.name() + "] diff VER_SOLIDITY059_3_6_5");
          opSetSolidity059_3_6_5.remove((byte)(i));
          break;
        case VER_ISTANBUL_4_1_0:
          Assert.assertTrue(opSetIstanbul_4_1_0.contains((byte)(i)), "ops [" + op.name() + "] diff VER_ISTANBUL_4_1_0");
          opSetIstanbul_4_1_0.remove((byte)(i));
          break;
        case VER_STAKING_4_2_0:
          Assert.assertTrue(opSetStaking_4_2_0.contains((byte)(i)), "ops [" + op.name() + "] diff VER_STAKING_4_2_0");
          opSetStaking_4_2_0.remove((byte)(i));
          break;
        default:
          opCountBasic--;
      }

      if (flag <= 0){
        continue;
      }

      Assert.assertEquals(op.isCall(), (flag & 0b10000) == 0b10000, "ops [" + op.name() + "] isCall Ret");
      Assert.assertEquals(op.callIsDelegate(), (flag & 0b10001) == 0b10001, "ops [" + op.name() + "] callIsDelegate Ret");
      Assert.assertEquals(op.callHasValue(), (flag & 0b10100) == 0b10100, "ops [" + op.name() + "] callHasValue Ret");
      Assert.assertEquals(op.callIsStateless(), (flag & 0b11000) == 0b11000, "ops [" + op.name() + "] callIsStateless Ret");
      Assert.assertEquals(op.callIsStatic(), (flag & 0b10010) == 0b10010, "ops [" + op.name() + "] callIsStatic Ret");
    }
    Assert.assertEquals(opCountBasic, 0, "ops basic count invalid");
    Assert.assertEquals(opSetTrc10_3_2_0.size(), 0, "ops opSetTrc10_3_2_0 have been left");
    Assert.assertEquals(opSetConstantinople_3_6_0.size(), 0, "ops opSetConstantinople_3_6_0 have been left");
    Assert.assertEquals(opSetSolidity059_3_6_5.size(), 0, "ops opSetSolidity059_3_6_5 have been left");
    Assert.assertEquals(opSetIstanbul_4_1_0.size(), 0, "ops opSetIstanbul_4_1_0 have been left");
    Assert.assertEquals(opSetStaking_4_2_0.size(), 0, "ops opSetStaking_4_2_0 have been left");
    logger.info("OpCodeTest passed");
  }

  /**
   * Release resources.
   */
  @After
  public void destroy() {
  }
}

