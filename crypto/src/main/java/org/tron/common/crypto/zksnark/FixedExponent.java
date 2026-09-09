package org.tron.common.crypto.zksnark;

import java.math.BigInteger;
import java.util.Arrays;

/** Builds exact signed binary expansions of public, positive constants at class initialization. */
final class FixedExponent {

  private FixedExponent() {
  }

  static byte[] naf(BigInteger value) {
    if (value.signum() <= 0) {
      throw new IllegalArgumentException("NAF constant must be positive");
    }
    byte[] digits = new byte[value.bitLength() + 1];
    int length = 0;
    while (value.signum() != 0) {
      int digit = value.testBit(0) ? 2 - (value.intValue() & 3) : 0;
      digits[length++] = (byte) digit;
      value = value.subtract(BigInteger.valueOf(digit)).shiftRight(1);
    }
    return Arrays.copyOf(digits, length);
  }
}
