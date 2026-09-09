package org.tron.core.vm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.math.BigInteger;
import java.util.Arrays;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.Test;
import org.tron.common.runtime.vm.DataWord;
import org.tron.core.vm.config.VMConfig;

public class Bn128PrecompileRegressionTest {

  private static final BigInteger P = new BigInteger(
      "21888242871839275222246405745257275088696311157297823662689037894645226208583");
  private static final String[] GENERATOR = {
      "1", "2",
      "11559732032986387107991004021392285783925812861821192530917403151452391805634",
      "10857046999023057135944570762232829481370756359578518086990519993285655852781",
      "4082367875863433681332203403145435568316851327593401208105741076214120093531",
      "8495653923123431417604973247489272438418190587263600148770280649306958101930"
  };

  @Test
  public void pairingPreservesReturnBytesAndValidation() {
    PrecompiledContracts.BN128Pairing precompile = new PrecompiledContracts.BN128Pairing();
    succeeds(precompile.execute(null), 1);
    succeeds(precompile.execute(new byte[0]), 1);
    succeeds(precompile.execute(new byte[192]), 1);
    byte[] generator = generator();
    succeeds(precompile.execute(generator), 0);
    byte[] negative = generator.clone();
    put(negative, 1, P.subtract(BigInteger.valueOf(2)));
    byte[] cancellation = new byte[384];
    System.arraycopy(generator, 0, cancellation, 0, 192);
    System.arraycopy(negative, 0, cancellation, 192, 192);
    succeeds(precompile.execute(cancellation), 1);
    for (int length : new int[]{1, 191, 193, 383, 385}) {
      rejected(precompile.execute(new byte[length]));
    }
    for (int coordinate = 0; coordinate < 6; coordinate++) {
      for (BigInteger bad : new BigInteger[]{P, P.add(BigInteger.ONE),
          BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE)}) {
        byte[] invalid = generator.clone();
        put(invalid, coordinate, bad);
        rejected(precompile.execute(invalid));
        // Reordering Miller work must not cause early success before a later invalid pair.
        System.arraycopy(invalid, 0, cancellation, 192, 192);
        rejected(precompile.execute(cancellation));
        if (coordinate >= 2) {
          Arrays.fill(invalid, 0, 64, (byte) 0);
          rejected(precompile.execute(invalid));
        }
      }
    }
    byte[] infinity = generator.clone();
    Arrays.fill(infinity, 0, 64, (byte) 0);
    succeeds(precompile.execute(infinity), 1);
    infinity[127] ^= 1;
    rejected(precompile.execute(infinity));
    infinity = generator.clone();
    Arrays.fill(infinity, 64, 192, (byte) 0);
    succeeds(precompile.execute(infinity), 1);
  }

  @Test
  public void energyScheduleIsUnchanged() {
    boolean previous = VMConfig.allowTvmIstanbul();
    PrecompiledContracts.BN128Pairing precompile = new PrecompiledContracts.BN128Pairing();
    try {
      for (int flag = 0; flag <= 1; flag++) {
        VMConfig.initAllowTvmIstanbul(flag);
        long base = flag == 0 ? 100_000L : 45_000L;
        long perPair = flag == 0 ? 80_000L : 34_000L;
        assertEquals(base, precompile.getEnergyForData(null));
        for (int length : new int[]{0, 191, 192, 193, 384, 192 * 128}) {
          assertEquals(base + length / 192 * perPair,
              precompile.getEnergyForData(new byte[length]));
        }
      }
    } finally {
      VMConfig.initAllowTvmIstanbul(previous ? 1 : 0);
    }
  }

  @Test
  public void additionAndMultiplicationStillAgreeWithPairingFixtures() {
    byte[] g = generator();
    PrecompiledContracts.BN128Addition add = new PrecompiledContracts.BN128Addition();
    PrecompiledContracts.BN128Multiplication mul = new PrecompiledContracts.BN128Multiplication();
    byte[] doubled = new byte[128];
    System.arraycopy(g, 0, doubled, 0, 64);
    System.arraycopy(g, 0, doubled, 64, 64);
    byte[] scalar = Arrays.copyOf(g, 96);
    put(scalar, 2, BigInteger.valueOf(2));
    assertArrayEquals(add.execute(doubled).getRight(), mul.execute(scalar).getRight());
    put(scalar, 2, BigInteger.ONE);
    assertArrayEquals(Arrays.copyOf(g, 64), mul.execute(scalar).getRight());
    put(scalar, 2, BigInteger.ZERO);
    assertArrayEquals(new byte[64], mul.execute(scalar).getRight());
    put(doubled, 3, P.subtract(BigInteger.valueOf(2)));
    assertArrayEquals(new byte[64], add.execute(doubled).getRight());
  }

  private static void succeeds(Pair<Boolean, byte[]> result, int expected) {
    assertTrue(result.getLeft());
    assertArrayEquals(new DataWord(expected).getData(), result.getRight());
  }

  private static void rejected(Pair<Boolean, byte[]> result) {
    assertFalse(result.getLeft());
    assertArrayEquals(new byte[0], result.getRight());
  }

  private static byte[] generator() {
    byte[] result = new byte[192];
    for (int i = 0; i < GENERATOR.length; i++) {
      put(result, i, new BigInteger(GENERATOR[i]));
    }
    return result;
  }

  private static void put(byte[] result, int index, BigInteger value) {
    byte[] raw = value.toByteArray();
    Arrays.fill(result, index * 32, (index + 1) * 32, (byte) 0);
    int length = Math.min(32, raw.length);
    System.arraycopy(raw, raw.length - length, result, (index + 1) * 32 - length, length);
  }
}
