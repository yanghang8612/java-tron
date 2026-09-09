package org.tron.common.crypto.zksnark;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Random;

/** Deterministic, non-zero fixtures shared by regression tests and the standalone benchmark. */
public final class Bn128TestSupport {

  public static final int PAIR_BYTES = 192;
  private static final BigInteger[] G2 = {
      new BigInteger("10857046999023057135944570762232829481370756359578518086990519993285655852781"),
      new BigInteger("11559732032986387107991004021392285783925812861821192530917403151452391805634"),
      new BigInteger("8495653923123431417604973247489272438418190587263600148770280649306958101930"),
      new BigInteger("4082367875863433681332203403145435568316851327593401208105741076214120093531")
  };

  private Bn128TestSupport() {
  }

  static BN128G1 g1() {
    return BN128G1.create(word(BigInteger.ONE), word(BigInteger.valueOf(2)));
  }

  static BN128G2 g2() {
    return BN128G2.create(word(G2[0]), word(G2[1]), word(G2[2]), word(G2[3]));
  }

  public static byte[][] fixtures(int count) {
    Random random = new Random(0x128254L);
    BN128G1 g1 = g1();
    BN128G2 g2 = g2();
    byte[][] result = new byte[count][];
    for (int i = 0; i < count; i++) {
      // Full-width scalars give dense, distinct coordinates, including for small pair counts.
      BigInteger a = new BigInteger(253, random).add(BigInteger.ONE);
      BigInteger b = new BigInteger(253, random).add(BigInteger.ONE);
      result[i] = encode(g1.mul(a).toAffine(), g2.mul(b).toAffine());
    }
    return result;
  }

  public static byte[] input(byte[][] fixtures, int pairs, int offset, String scenario) {
    byte[] result = new byte[PAIR_BYTES * pairs];
    for (int i = 0; i < pairs; i++) {
      int index = "repeated".equals(scenario) ? 0 : (offset + i) % fixtures.length;
      System.arraycopy(fixtures[index], 0, result, PAIR_BYTES * i, PAIR_BYTES);
    }
    return result;
  }

  static byte[] encode(BN128<Fp> p, BN128<Fp2> q) {
    p = p.toEthNotation();
    q = q.toEthNotation();
    byte[] result = new byte[PAIR_BYTES];
    putWord(result, 0, p.x.v);
    putWord(result, 1, p.y.v);
    putWord(result, 2, q.x.b.v);
    putWord(result, 3, q.x.a.v);
    putWord(result, 4, q.y.b.v);
    putWord(result, 5, q.y.a.v);
    return result;
  }

  static byte[] negateG1(byte[] pair) {
    byte[] result = pair.clone();
    BigInteger y = new BigInteger(1, Arrays.copyOfRange(pair, 32, 64));
    putWord(result, 1, y.negate().mod(Params.P));
    return result;
  }

  static byte[] concat(byte[]... inputs) {
    int size = 0;
    for (byte[] input : inputs) {
      size += input.length;
    }
    byte[] result = new byte[size];
    int offset = 0;
    for (byte[] input : inputs) {
      System.arraycopy(input, 0, result, offset, input.length);
      offset += input.length;
    }
    return result;
  }

  static byte[] word(BigInteger value) {
    byte[] raw = value.toByteArray();
    byte[] word = new byte[32];
    int length = Math.min(raw.length, word.length);
    System.arraycopy(raw, raw.length - length, word, word.length - length, length);
    return word;
  }

  static void putWord(byte[] input, int index, BigInteger value) {
    System.arraycopy(word(value), 0, input, index * 32, 32);
  }

  static PairingCheck decode(byte[] input) {
    if (input == null) {
      input = new byte[0];
    }
    if (input.length % PAIR_BYTES != 0) {
      return null;
    }
    PairingCheck check = PairingCheck.create();
    for (int offset = 0; offset < input.length; offset += PAIR_BYTES) {
      BN128G1 p = BN128G1.create(slice(input, offset, 0), slice(input, offset, 1));
      if (p == null) {
        return null;
      }
      BN128G2 q = BN128G2.create(slice(input, offset, 3), slice(input, offset, 2),
          slice(input, offset, 5), slice(input, offset, 4));
      if (q == null) {
        return null;
      }
      check.addPair(p, q);
    }
    return check;
  }

  /** Same validation and pairing calls as the precompile; -1 means rejected input. */
  public static int execute(byte[] input) {
    PairingCheck check = decode(input);
    if (check == null) {
      return -1;
    }
    check.run();
    return check.result();
  }

  private static byte[] slice(byte[] input, int offset, int index) {
    return Arrays.copyOfRange(input, offset + index * 32, offset + (index + 1) * 32);
  }

  static Fp randomFp(Random random) {
    return new Fp(new BigInteger(254, random).mod(Params.P));
  }

  static Fp2 randomFp2(Random random) {
    return new Fp2(randomFp(random), randomFp(random));
  }

  static Fp6 randomFp6(Random random) {
    return new Fp6(randomFp2(random), randomFp2(random), randomFp2(random));
  }

  static Fp12 randomFp12(Random random) {
    return new Fp12(randomFp6(random), randomFp6(random));
  }

  /** Generate points on the full twist, without assuming they belong to G2. */
  static BN128<Fp2> randomTwistPoint(Random random) {
    while (true) {
      Fp2 x = randomFp2(random);
      Fp2 y = sqrt(x.squared().mul(x).add(Params.B_Fp2));
      if (y != null) {
        return new BN128Fp2(x, y, Fp2._1);
      }
    }
  }

  private static Fp2 sqrt(Fp2 value) {
    if (value.b.isZero()) {
      BigInteger root = sqrt(value.a.v);
      if (root != null) {
        return new Fp2(new Fp(root), Fp.ZERO);
      }
      root = sqrt(value.a.v.negate().mod(Params.P));
      return root == null ? null : new Fp2(Fp.ZERO, new Fp(root));
    }
    BigInteger norm = value.a.v.multiply(value.a.v)
        .add(value.b.v.multiply(value.b.v)).mod(Params.P);
    BigInteger normRoot = sqrt(norm);
    if (normRoot == null) {
      return null;
    }
    BigInteger inverseTwo = BigInteger.valueOf(2).modInverse(Params.P);
    for (int sign = 0; sign < 2; sign++) {
      BigInteger delta = value.a.v.add(normRoot).multiply(inverseTwo).mod(Params.P);
      BigInteger x = sqrt(delta);
      if (x != null && x.signum() != 0) {
        BigInteger y = value.b.v.multiply(x.shiftLeft(1).modInverse(Params.P)).mod(Params.P);
        Fp2 result = new Fp2(new Fp(x), new Fp(y));
        if (result.squared().equals(value)) {
          return result;
        }
      }
      normRoot = normRoot.negate().mod(Params.P);
    }
    return null;
  }

  private static BigInteger sqrt(BigInteger value) {
    BigInteger root = value.modPow(Params.P.add(BigInteger.ONE).shiftRight(2), Params.P);
    return root.multiply(root).mod(Params.P).equals(value) ? root : null;
  }
}
