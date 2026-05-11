package org.tron.plugins.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class Base58CheckTest {

  // Well-known TRON address: 21 bytes, 0x41 prefix
  private static final String ADDR = "TWjkoz18Y48SgWoxEeGG11ezCCzee8wo1A";

  @Test
  public void decodeKnownAddressYields21Bytes() {
    byte[] decoded = Base58Check.decode58Check(ADDR);
    assertEquals(21, decoded.length);
    assertEquals((byte) 0x41, decoded[0]);
  }

  @Test
  public void roundTripPreservesBytes() {
    byte[] decoded = Base58Check.decode58Check(ADDR);
    String reEncoded = Base58Check.encode58Check(decoded);
    assertEquals(ADDR, reEncoded);
  }

  @Test
  public void tamperedChecksumIsRejected() {
    String candidate = ADDR.substring(0, ADDR.length() - 1) + "A";
    final String tampered = candidate.equals(ADDR)
        ? ADDR.substring(0, ADDR.length() - 1) + "B"
        : candidate;
    assertThrows(IllegalArgumentException.class, () -> Base58Check.decode58Check(tampered));
  }

  @Test
  public void zeroLengthInputRejected() {
    assertThrows(IllegalArgumentException.class, () -> Base58Check.decode58Check(""));
  }
}
