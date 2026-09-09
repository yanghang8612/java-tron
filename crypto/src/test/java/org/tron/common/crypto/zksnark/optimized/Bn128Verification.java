package org.tron.common.crypto.zksnark.optimized;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;

/** Runs identically against the frozen baseline and candidate, including full Fp12 outputs. */
public final class Bn128Verification {

  private final MessageDigest digest;
  private long assertions;

  private Bn128Verification() throws Exception {
    digest = MessageDigest.getInstance("SHA-256");
  }

  public static void main(String[] args) throws Exception {
    System.out.println(verify());
  }

  public static String verify() throws Exception {
    Bn128Verification test = new Bn128Verification();
    test.fields();
    test.groups();
    test.pairings();
    StringBuilder hex = new StringBuilder();
    for (byte value : test.digest.digest()) {
      hex.append(Character.forDigit((value >>> 4) & 15, 16));
      hex.append(Character.forDigit(value & 15, 16));
    }
    return "VERIFY assertions=" + test.assertions + " sha256=" + hex;
  }

  private void fields() {
    Random random = new Random(0x128f1e1dL);
    BigInteger p = Params.P;
    BigInteger[] edges = {BigInteger.ZERO, BigInteger.ONE, p.subtract(BigInteger.ONE), p,
        p.add(BigInteger.ONE), p.shiftLeft(1), p.pow(3), BigInteger.ONE.negate(),
        p.negate(), p.pow(3).negate(), BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE)};
    for (int i = 0; i < 12000; i++) {
      BigInteger a = i < edges.length * edges.length ? edges[i / edges.length]
          : new BigInteger(i % 3 == 0 ? 520 : 254, random);
      BigInteger b = i < edges.length * edges.length ? edges[i % edges.length]
          : new BigInteger(i % 3 == 0 ? 520 : 254, random);
      if (i % 7 == 0) {
        a = a.negate();
      }
      Fp x = new Fp(a);
      Fp y = new Fp(b);
      equal(new Fp(a.add(b).mod(p)), x.add(y));
      equal(new Fp(a.subtract(b).mod(p)), x.sub(y));
      equal(new Fp(a.shiftLeft(1).mod(p)), x.dbl());
      equal(new Fp(a.negate().mod(p)), x.negate());
      equal(new Fp(a.multiply(b).mod(p)), x.mul(y));
      record(Bn128TestSupport.value(x.add(y)));
      record(Bn128TestSupport.value(x.sub(y)));
      record(Bn128TestSupport.value(x.dbl()));
      record(Bn128TestSupport.value(x.negate()));
    }
    for (int i = 0; i < 2000; i++) {
      Fp2 a = Bn128TestSupport.randomFp2(random);
      Fp2 b = Bn128TestSupport.randomFp2(random);
      Fp2 expected = new Fp2(
          Bn128TestSupport.value(a.a).multiply(Bn128TestSupport.value(b.a))
              .subtract(Bn128TestSupport.value(a.b).multiply(Bn128TestSupport.value(b.b))).mod(p),
          Bn128TestSupport.value(a.a).multiply(Bn128TestSupport.value(b.b))
              .add(Bn128TestSupport.value(a.b).multiply(Bn128TestSupport.value(b.a))).mod(p));
      equal(expected, a.mul(b));
      equal(a.mul(a), a.squared());
      equal(Fp2.NON_RESIDUE.mul(a), a.mulByNonResidue());
      equal(Fp2._1, a.mul(a.inverse()));
      equal(a, a.frobeniusMap(1).frobeniusMap(1));
      equal(a, a.frobeniusMap(2));
      record(a.mul(b));
      record(a.squared());
      record(a.inverse());
      record(a.mulByNonResidue());
      record(a.frobeniusMap(1));
    }
    for (int i = 0; i < 200; i++) {
      Fp12 a = Bn128TestSupport.randomFp12(random);
      Fp12 b = Bn128TestSupport.randomFp12(random);
      equal(a.mul(a), a.squared());
      equal(Fp12._1, a.mul(a.inverse()));
      Fp2 c0 = Bn128TestSupport.randomFp2(random);
      Fp2 c2 = Bn128TestSupport.randomFp2(random);
      Fp2 c4 = Bn128TestSupport.randomFp2(random);
      Fp12 sparse = new Fp12(new Fp6(c0, Fp2.ZERO, c2),
          new Fp6(Fp2.ZERO, c4, Fp2.ZERO));
      equal(a.mul(sparse), a.mulBy024(c0, c4, c2));
      record(a.mul(b));
      record(a.squared());
      record(a.inverse());
      record(a.mulBy024(c0, c4, c2));
      // Map into the cyclotomic subgroup before testing cyclotomic squaring.
      Fp12 easy = a.unitaryInverse().mul(a.inverse());
      easy = easy.mul(easy.frobeniusMap(2));
      equal(easy.squared(), easy.cyclotomicSquared());
      record(easy.cyclotomicSquared());
    }
  }

  private void groups() {
    Random random = new Random(0x128600dL);
    BN128G1 g1 = Bn128TestSupport.g1();
    BN128G2 g2 = Bn128TestSupport.g2();
    equal(true, g1.mul(Params.R).isZero());
    equal(true, g2.mul(Params.R).isZero());
    for (int i = 0; i < 64; i++) {
      BigInteger a = new BigInteger(254, random);
      BigInteger b = new BigInteger(254, random);
      BN128<Fp> p = g1.mul(a);
      BN128<Fp2> q = g2.mul(b);
      equal(g1.mul(a.add(b)).toAffine(), p.add(g1.mul(b)).toAffine());
      equal(g2.mul(a.add(b)).toAffine(), q.add(g2.mul(a)).toAffine());
      equal(p.toAffine(), p.toAffine().toAffine());
      equal(q.toAffine(), q.toAffine().toAffine());
      record(Bn128TestSupport.value(p.toAffine().x));
      record(Bn128TestSupport.value(p.toAffine().y));
      record(q.toAffine().x);
      record(q.toAffine().y);
      // Non-unit Jacobian Z must still use the original inverse-based conversion.
      Fp z1 = Bn128TestSupport.randomFp(random);
      Fp2 z2 = Bn128TestSupport.randomFp2(random);
      BN128<Fp> pa = p.toAffine();
      BN128<Fp2> qa = q.toAffine();
      equal(pa, new BN128Fp(pa.x.mul(z1.squared()),
          pa.y.mul(z1.squared().mul(z1)), z1).toAffine());
      equal(qa, new BN128Fp2(qa.x.mul(z2.squared()),
          qa.y.mul(z2.squared().mul(z2)), z2).toAffine());
    }
    equal(true, BN128Fp.ZERO.toAffine().isZero());
    equal(true, BN128Fp2.ZERO.toAffine().isZero());
  }

  private void pairings() {
    byte[][] fixtures = Bn128TestSupport.fixtures(40);
    pairing(null, 1);
    pairing(new byte[0], 1);
    pairing(new byte[192], 1);
    pairing(new byte[192 * 16], 1);
    for (int length : new int[]{1, 31, 32, 191, 193, 383, 385, 1023}) {
      pairing(new byte[length], -1);
    }
    for (int i = 0; i < 32; i++) {
      byte[] pair = fixtures[i];
      pairing(pair, 0);
      byte[] cancellation = Bn128TestSupport.concat(pair, Bn128TestSupport.negateG1(pair));
      pairing(cancellation, 1);
      pairing(Bn128TestSupport.concat(cancellation, fixtures[(i + 1) % fixtures.length]), 0);
      pairing(Bn128TestSupport.input(fixtures, 1 + i % 16, i, "distinct"), 0);
      byte[] infinity = pair.clone();
      Arrays.fill(infinity, 0, 64, (byte) 0);
      pairing(infinity, 1);
      pairing(Bn128TestSupport.concat(pair, infinity, cancellation), 0);
      infinity = pair.clone();
      Arrays.fill(infinity, 64, 192, (byte) 0);
      pairing(infinity, 1);
      for (int coordinate = 0; coordinate < 6; coordinate++) {
        byte[] invalid = pair.clone();
        Bn128TestSupport.putWord(invalid, coordinate, Params.P.add(BigInteger.valueOf(i)));
        pairing(invalid, -1);
      }
      byte[] offCurve = pair.clone();
      offCurve[31] ^= 1;
      pairing(offCurve, -1);
      offCurve = pair.clone();
      offCurve[127] ^= 1;
      pairing(offCurve, -1);
      Arrays.fill(offCurve, 0, 64, (byte) 0);
      pairing(offCurve, -1); // A zero G1 must never bypass validation of G2.
    }
    Random random = new Random(0x128badL);
    for (int i = 0; i < 32; i++) {
      BN128<Fp2> point = Bn128TestSupport.randomTwistPoint(random);
      equal(true, point.isValid());
      equal(false, point.mul(Params.R).isZero());
      byte[] encoded = Bn128TestSupport.encode(Bn128TestSupport.g1(), point);
      pairing(encoded, -1);
      Arrays.fill(encoded, 0, 64, (byte) 0);
      pairing(encoded, -1);
      pairing(Bn128TestSupport.concat(fixtures[0], encoded), -1);
      record(point.x);
      record(point.y);
    }
    for (int n : new int[]{32, 64, 128}) {
      pairing(Bn128TestSupport.input(fixtures, n, 0, "distinct"), 0);
    }
    // Public PairingCheck instances can be run again; preserve their accumulator behavior.
    PairingCheck check = Bn128TestSupport.decode(fixtures[0]);
    check.run();
    record(check.product);
    check.run();
    record(check.product);
  }

  private void pairing(byte[] input, int expected) {
    PairingCheck check = Bn128TestSupport.decode(input);
    if (check == null) {
      equal(-1, expected);
      digest.update((byte) 0xff);
      return;
    }
    check.run();
    equal(expected, check.result());
    // Checking only 0/1 would miss many arithmetic bugs on negative pairing checks.
    record(check.product);
  }

  private void equal(Object expected, Object actual) {
    assertions++;
    if (!expected.equals(actual)) {
      throw new AssertionError("Assertion " + assertions + ": expected " + expected
          + ", actual " + actual);
    }
  }

  private void record(BigInteger value) {
    digest.update(Bn128TestSupport.word(value));
  }

  private void record(Fp2 value) {
    record(Bn128TestSupport.value(value.a));
    record(Bn128TestSupport.value(value.b));
  }

  private void record(Fp12 value) {
    record(value.a.a);
    record(value.a.b);
    record(value.a.c);
    record(value.b.a);
    record(value.b.b);
    record(value.b.c);
  }
}
