package org.tron.plugins.utils;

import java.util.Arrays;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.tron.common.utils.Base58;

/**
 * Base58Check codec used inside the plugins runtime. Avoids
 * org.tron.common.utils.StringUtil.encode58Check, which calls
 * CommonParameter.getInstance() and is not initialized in this module.
 */
public final class Base58Check {

  private Base58Check() {
  }

  public static String encode58Check(byte[] input) {
    byte[] checksum = Arrays.copyOf(doubleSha256(input), 4);
    byte[] payload = new byte[input.length + 4];
    System.arraycopy(input, 0, payload, 0, input.length);
    System.arraycopy(checksum, 0, payload, input.length, 4);
    return Base58.encode(payload);
  }

  public static byte[] decode58Check(String address) {
    if (address == null || address.isEmpty()) {
      throw new IllegalArgumentException("empty address");
    }
    byte[] payload = Base58.decode(address);
    if (payload.length < 4) {
      throw new IllegalArgumentException("address too short for base58check");
    }
    byte[] data = Arrays.copyOfRange(payload, 0, payload.length - 4);
    byte[] checksum = Arrays.copyOfRange(payload, payload.length - 4, payload.length);
    byte[] expected = Arrays.copyOf(doubleSha256(data), 4);
    if (!Arrays.equals(checksum, expected)) {
      throw new IllegalArgumentException("base58check checksum mismatch");
    }
    return data;
  }

  private static byte[] doubleSha256(byte[] input) {
    return sha256(sha256(input));
  }

  private static byte[] sha256(byte[] input) {
    SHA256Digest digest = new SHA256Digest();
    digest.update(input, 0, input.length);
    byte[] out = new byte[digest.getDigestSize()];
    digest.doFinal(out, 0);
    return out;
  }
}
