package org.tron.common.crypto.zksnark;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * Fixed-width Fp arithmetic, radix 2^32, R = 2^256. No native code or mutable shared scratch.
 * All operands/results are canonical residues in [0,p), stored least-significant limb first.
 * Arrays returned here are owned by their Fp and never subsequently modified.
 */
final class FpMontgomery {

  // Defined here, independently of Params/Fp, to avoid cyclic field-constant initialization.
  static final BigInteger P = new BigInteger(
      "21888242871839275222246405745257275088696311157297823662689037894645226208583");
  private static final long MASK = 0xffffffffL;
  private static final int LIMBS = 8;
  private static final int[] MODULUS = limbs(P);
  private static final int[] R2 = limbs(BigInteger.ONE.shiftLeft(512).mod(P));
  private static final int[] ONE = limbs(BigInteger.ONE);
  // n0 = -p[0]^-1 mod 2^32, derived rather than hand-transcribed.
  private static final long N0 = P.negate().modInverse(BigInteger.ONE.shiftLeft(32))
      .longValue() & MASK;

  private FpMontgomery() {
  }

  static int[] encode(BigInteger value) {
    return multiply(limbs(value), R2);
  }

  static BigInteger decode(int[] value) {
    int[] normal = multiply(value, ONE);
    byte[] bytes = new byte[32];
    for (int i = 0; i < LIMBS; i++) {
      int word = normal[i];
      for (int j = 0; j < 4; j++) {
        bytes[31 - 4 * i - j] = (byte) (word >>> (8 * j));
      }
    }
    return new BigInteger(1, bytes);
  }

  private static int[] limbs(BigInteger value) {
    int[] result = new int[LIMBS];
    for (int i = 0; i < LIMBS; i++) {
      result[i] = value.intValue();
      value = value.shiftRight(32);
    }
    return result;
  }

  static int[] add(int[] a, int[] b) {
    int[] result = new int[LIMBS];
    long carry = 0;
    for (int i = 0; i < LIMBS; i++) {
      carry += (a[i] & MASK) + (b[i] & MASK);
      result[i] = (int) carry;
      carry >>>= 32;
    }
    // a+b < 2p < 2^255, so no 256-bit overflow is possible.
    if (atLeastModulus(result)) {
      subtractModulus(result);
    }
    return result;
  }

  static int[] subtract(int[] a, int[] b) {
    int[] result = new int[LIMBS];
    long borrow = 0;
    for (int i = 0; i < LIMBS; i++) {
      borrow += (a[i] & MASK) - (b[i] & MASK);
      result[i] = (int) borrow;
      borrow >>= 32;
    }
    if (borrow != 0) {
      addModulus(result); // The carry out cancels the borrow from the subtraction.
    }
    return result;
  }

  static int[] half(int[] a) {
    int[] result = a.clone();
    if ((result[0] & 1) != 0) {
      addModulus(result); // a+p < 2p < 2^255; still fits in eight limbs.
    }
    int carry = 0;
    for (int i = LIMBS - 1; i >= 0; i--) {
      int word = result[i];
      result[i] = (word >>> 1) | (carry << 31);
      carry = word & 1;
    }
    return result;
  }

  static boolean isZero(int[] a) {
    int bits = 0;
    for (int word : a) {
      bits |= word;
    }
    return bits == 0;
  }

  /**
   * Schoolbook multiplication followed by word-at-a-time Montgomery REDC.
   * Each inner accumulation is at most (2^32-1)^2 + 2(2^32-1) = 2^64-1.
   * Java long wraparound retains that unsigned 64-bit word; >>> extracts its carry.
   * No signed comparison of a potentially overflowing multiplication is used.
   */
  static int[] multiply(int[] a, int[] b) {
    int[] t = new int[2 * LIMBS + 1];
    for (int i = 0; i < LIMBS; i++) {
      long carry = 0;
      long ai = a[i] & MASK;
      for (int j = 0; j < LIMBS; j++) {
        long sum = ai * (b[j] & MASK) + (t[i + j] & MASK) + carry;
        t[i + j] = (int) sum;
        carry = sum >>> 32;
      }
      t[i + LIMBS] = (int) carry;
    }
    for (int i = 0; i < LIMBS; i++) {
      long m = ((t[i] & MASK) * N0) & MASK;
      long carry = 0;
      for (int j = 0; j < LIMBS; j++) {
        long sum = m * (MODULUS[j] & MASK) + (t[i + j] & MASK) + carry;
        t[i + j] = (int) sum;
        carry = sum >>> 32;
      }
      int k = i + LIMBS;
      while (carry != 0) {
        long sum = (t[k] & MASK) + carry;
        t[k++] = (int) sum;
        carry = sum >>> 32;
      }
      // t[i] is now zero; advancing i is exact division by radix 2^32.
    }
    // REDC(a*b) < p + p^2/R < 2p < 2^255, hence the extra limb is zero.
    int[] result = Arrays.copyOfRange(t, LIMBS, 2 * LIMBS);
    if (atLeastModulus(result)) {
      subtractModulus(result);
    }
    return result;
  }

  private static boolean atLeastModulus(int[] a) {
    for (int i = LIMBS - 1; i >= 0; i--) {
      long difference = (a[i] & MASK) - (MODULUS[i] & MASK);
      if (difference != 0) {
        return difference > 0;
      }
    }
    return true;
  }

  private static void subtractModulus(int[] a) {
    long borrow = 0;
    for (int i = 0; i < LIMBS; i++) {
      borrow += (a[i] & MASK) - (MODULUS[i] & MASK);
      a[i] = (int) borrow;
      borrow >>= 32;
    }
  }

  private static void addModulus(int[] a) {
    long carry = 0;
    for (int i = 0; i < LIMBS; i++) {
      carry += (a[i] & MASK) + (MODULUS[i] & MASK);
      a[i] = (int) carry;
      carry >>>= 32;
    }
  }
}
