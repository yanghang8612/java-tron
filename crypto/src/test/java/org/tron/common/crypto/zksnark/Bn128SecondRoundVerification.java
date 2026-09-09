package org.tron.common.crypto.zksnark;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Random;

/** Independent BigInteger oracles and exceptional-point tests for the second optimization round. */
public final class Bn128SecondRoundVerification {

  private long assertions;

  public static void main(String[] args) throws Exception {
    if (args.length == 1) {
      Class.forName("org.tron.common.crypto.zksnark." + args[0]);
      if (!Fp._1.equals(new Fp(BigInteger.ONE)) || !Fp2.ZERO.isZero()
          || !Fp12._1.equals(new Fp12(Fp6._1, Fp6.ZERO))
          || !Params.B_Fp.equals(new Fp(BigInteger.valueOf(3)))
          || !Params.B_Fp2.equals(Params.B_Fp.mul(Params.TWIST.inverse()))) {
        throw new AssertionError("Cyclic constant initialization: " + args[0]);
      }
      System.out.println("INITIALIZATION OK " + args[0]);
      return;
    }
    System.out.println(verify());
  }

  static String verify() {
    Bn128SecondRoundVerification test = new Bn128SecondRoundVerification();
    test.fields();
    test.signedDigits();
    test.points();
    test.finalExponent();
    return "SECOND_ROUND assertions=" + test.assertions;
  }

  private void fields() {
    BigInteger p = Params.P;
    BigInteger inverseTwo = BigInteger.valueOf(2).modInverse(p);
    BigInteger inverseR = BigInteger.ONE.shiftLeft(256).modInverse(p);
    Random random = new Random(0x5611128L);
    // Exercise every limb boundary and long carry/borrow chains, not only random points.
    for (int bit = 0; bit <= 256; bit++) {
      BigInteger power = BigInteger.ONE.shiftLeft(bit);
      for (int delta = -2; delta <= 2; delta++) {
        fieldCase(power.add(BigInteger.valueOf(delta)), p.subtract(power), inverseTwo);
        fieldCase(p.add(BigInteger.valueOf(delta)), power, inverseTwo);
        // Also place the boundary patterns in the Montgomery limbs themselves.
        fieldCase(power.add(BigInteger.valueOf(delta)).multiply(inverseR).mod(p),
            p.subtract(power).multiply(inverseR).mod(p), inverseTwo);
        fieldCase(p.add(BigInteger.valueOf(delta)).multiply(inverseR).mod(p),
            power.multiply(inverseR).mod(p), inverseTwo);
      }
    }
    for (int i = 0; i < 100000; i++) {
      BigInteger a = new BigInteger(254, random).mod(p);
      BigInteger b = new BigInteger(254, random).mod(p);
      fieldCase(a, b, inverseTwo);
      if (i < 2000 && a.signum() != 0) {
        equal(a.modInverse(p), value(new Fp(a).inverse()));
      }
    }
    try {
      Fp.ZERO.inverse();
      throw new AssertionError("zero inverse must fail");
    } catch (ArithmeticException expected) {
      assertions++;
    }
  }

  private void fieldCase(BigInteger a, BigInteger b, BigInteger inverseTwo) {
    BigInteger p = Params.P;
    Fp x = new Fp(a);
    Fp y = new Fp(b);
    equal(a, value(x)); // Unreduced input must not become an accepted coordinate.
    equal(Arrays.toString(a.toByteArray()), Arrays.toString(x.bytes()));
    equal(a.hashCode(), x.hashCode());
    equal(a.toString(), x.toString());
    equal(a.compareTo(p) < 0, x.isValid()); // Preserve existing internal constructor semantics.
    equal(a.signum() == 0, x.isZero());
    equal(a.equals(b), x.equals(y));
    equal(a.add(b).mod(p), value(x.add(y)));
    equal(a.subtract(b).mod(p), value(x.sub(y)));
    equal(a.multiply(b).mod(p), value(x.mul(y)));
    equal(a.multiply(a).mod(p), value(x.squared()));
    equal(a.shiftLeft(1).mod(p), value(x.dbl()));
    equal(a.negate().mod(p), value(x.negate()));
    equal(a.multiply(inverseTwo).mod(p), value(x.half()));
    equal(new Fp(a.mod(p)), x.half().dbl());
    equal(a, value(x)); // No arithmetic operation may mutate an operand.
    equal(b, value(y));
  }

  private void signedDigits() {
    for (BigInteger constant : new BigInteger[]{BigInteger.ONE, BigInteger.valueOf(3),
        Params.R.subtract(BigInteger.ONE), Params.PAIRING_FINAL_EXPONENT_Z}) {
      byte[] digits = FixedExponent.naf(constant);
      BigInteger reconstructed = BigInteger.ZERO;
      equal((byte) 1, digits[digits.length - 1]);
      for (int i = digits.length - 1; i >= 0; i--) {
        equal(true, Math.abs(digits[i]) <= 1);
        if (i > 0 && digits[i] != 0) {
          equal((byte) 0, digits[i - 1]);
        }
        reconstructed = reconstructed.shiftLeft(1).add(BigInteger.valueOf(digits[i]));
      }
      equal(constant, reconstructed);
    }
  }

  private void points() {
    Random random = new Random(0x56116L);
    byte[] digits = FixedExponent.naf(Params.R.subtract(BigInteger.ONE));
    for (int i = 0; i < 128; i++) {
      BN128<Fp> p = Bn128TestSupport.g1().mul(new BigInteger(256, random));
      BN128<Fp2> q = i < 64 ? Bn128TestSupport.randomTwistPoint(random)
          : Bn128TestSupport.g2().mul(new BigInteger(256, random));
      pointCase(p, Bn128TestSupport.randomFp(random));
      pointCase(q, Bn128TestSupport.randomFp2(random));
      equal(q.mul(Params.R.subtract(BigInteger.ONE)).toAffine(),
          q.mulByNaf(digits).toAffine());
      equal(q.mul(Params.R).isZero(), q.mulByNaf(digits).add(q).isZero());
    }
    equal(true, BN128Fp2.ZERO.mulByNaf(digits).isZero());
    // Multiplication inputs greater than r, up to the full 256-bit ABI range, stay valid.
    BN128G1 p = Bn128TestSupport.g1();
    equal(p.toAffine(), p.mul(Params.R.add(BigInteger.ONE)).toAffine());
    BigInteger max = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);
    equal(p.mul(max.mod(Params.R)).toAffine(), p.mul(max).toAffine());
  }

  private <T extends Field<T>> void pointCase(BN128<T> p, T scale) {
    BN128<T> affine = p.toAffine();
    BN128<T> scaled = p.instance(affine.x.mul(scale.squared()),
        affine.y.mul(scale.squared().mul(scale)), scale);
    BN128<T> negative = p.instance(affine.x, affine.y.negate(), affine.z);
    // The right-hand sides exercise the general (non-unit Z2) formula.
    equal(p.add(scaled).toAffine(), p.add(affine).toAffine());
    equal(scaled.add(scaled).toAffine(), affine.add(affine).toAffine());
    equal(true, scaled.add(negative).isZero());
    equal(affine, p.zero().add(affine).toAffine());
    equal(affine, affine.add(p.zero()).toAffine());
    equal(p.isOnCurve(), affine.isOnCurve());
  }

  private void finalExponent() {
    Random random = new Random(0x561112L);
    for (int i = 0; i < 128; i++) {
      Fp12 a = Bn128TestSupport.randomFp12(random);
      Fp12 easy = a.unitaryInverse().mul(a.inverse());
      easy = easy.mul(easy.frobeniusMap(2));
      equal(easy.cyclotomicExp(Params.PAIRING_FINAL_EXPONENT_Z).unitaryInverse(),
          easy.negExp(Params.PAIRING_FINAL_EXPONENT_Z));
    }
    equal(Fp12._1, Fp12._1.negExp(Params.PAIRING_FINAL_EXPONENT_Z));
  }

  private static BigInteger value(Fp value) {
    return Bn128TestSupport.value(value);
  }

  private void equal(Object expected, Object actual) {
    assertions++;
    if (!expected.equals(actual)) {
      throw new AssertionError("Assertion " + assertions + ": expected " + expected
          + ", actual " + actual);
    }
  }
}
