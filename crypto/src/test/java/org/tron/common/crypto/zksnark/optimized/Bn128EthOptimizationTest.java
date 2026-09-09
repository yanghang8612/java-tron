package org.tron.common.crypto.zksnark.optimized;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;

public class Bn128EthOptimizationTest {

  @Test
  public void seedChainIsExactWithoutReducingByGroupOrder() {
    int[] operations = new int[2];
    ScalarPoint symbolic = new ScalarPoint(BigInteger.ONE, operations);
    ScalarPoint result = (ScalarPoint) G2SubgroupCheck.multiplyBySeed(symbolic, Long.MAX_VALUE);
    assertEquals(Params.PAIRING_FINAL_EXPONENT_Z, result.scalar);
    // Count the actual G2 code, not gnark's stale 62/17 comment (that describes its GT chain).
    assertEquals(53, operations[0]);
    assertEquals(25, operations[1]);
    Random random = new Random(0x5eed128L);
    for (int i = 0; i < 128; i++) {
      BN128<Fp2> q = Bn128TestSupport.randomTwistPoint(random);
      assertEquals(q.mul(Params.PAIRING_FINAL_EXPONENT_Z).toAffine(),
          G2SubgroupCheck.multiplyBySeed(q, Long.MAX_VALUE).toAffine());
    }
  }

  @Test
  public void fastSubgroupMatchesFullScalarMultiplication() {
    Random random = new Random(0x672254L);
    BN128G2 generator = Bn128TestSupport.g2();
    for (int i = 0; i < 512; i++) {
      BN128<Fp2> twist = Bn128TestSupport.randomTwistPoint(random);
      checkSubgroup(twist, random);
      // Kill the r-component to exercise cofactor torsion, not just random mixed points.
      checkSubgroup(twist.mul(Params.R), random);
      BN128<Fp2> valid = generator.mul(new BigInteger(256, random));
      assertTrue(valid.mul(Params.R).isZero());
      checkSubgroup(valid, random);
    }
    assertTrue(G2SubgroupCheck.isGroupMember(BN128Fp2.ZERO, Long.MAX_VALUE));
    assertTrue(G2SubgroupCheck.sameJacobianPoint(BN128Fp2.ZERO, BN128Fp2.ZERO.toAffine()));
    assertFalse(G2SubgroupCheck.sameJacobianPoint(BN128Fp2.ZERO, generator));
    assertFalse(G2SubgroupCheck.sameJacobianPoint(generator, BN128Fp2.ZERO));
    assertFalse(G2SubgroupCheck.sameJacobianPoint(generator,
        new BN128Fp2(generator.x, generator.y.negate(), generator.z)));
  }

  private static void checkSubgroup(BN128<Fp2> point, Random random) {
    boolean expected = point.mul(Params.R).isZero();
    assertEquals(expected, G2SubgroupCheck.isGroupMember(point, Long.MAX_VALUE));
    BN128<Fp2> affine = point.toAffine();
    assertEquals(expected, G2SubgroupCheck.isGroupMember(affine, Long.MAX_VALUE));
    Fp2 z = Bn128TestSupport.randomFp2(random);
    if (z.isZero()) {
      z = Fp2._1;
    }
    BN128<Fp2> scaled = new BN128Fp2(affine.x.mul(z.squared()),
        affine.y.mul(z.squared().mul(z)), point.isZero() ? Fp2.ZERO : z);
    assertTrue(G2SubgroupCheck.sameJacobianPoint(point, scaled));
    assertEquals(expected, G2SubgroupCheck.isGroupMember(scaled, Long.MAX_VALUE));
  }

  @Test
  public void fusedSparseLinesEqualDenseMultiplication() {
    Random random = new Random(0x02401234L);
    for (int i = 0; i < 4096; i++) {
      Fp2[] c = new Fp2[6];
      for (int j = 0; j < c.length; j++) {
        // First 64 cases cover every zero/nonzero coefficient pattern.
        c[j] = i < 64 && (i & (1 << j)) == 0 ? Fp2.ZERO : Bn128TestSupport.randomFp2(random);
      }
      Fp12 first = sparse(c[0], c[1], c[2]);
      Fp12 second = sparse(c[3], c[4], c[5]);
      Fp12 merged = Fp12.multiplySparse024(c[0], c[1], c[2], c[3], c[4], c[5]);
      Fp12 accumulator = i == 0 ? Fp12.ZERO : Bn128TestSupport.randomFp12(random);
      assertEquals(first.mul(second), merged);
      assertTrue(merged.b.c.isZero());
      assertEquals(accumulator.mul(first).mul(second), accumulator.mulBy01234(merged));
      assertEquals(accumulator.mulBy024(c[0], c[2], c[1]).mulBy024(c[3], c[5], c[4]),
          accumulator.mulBy01234(merged));
      assertEquals(accumulator.b.mul(new Fp6(c[0], c[1], Fp2.ZERO)),
          accumulator.b.mulBy01(c[0], c[1]));
    }
  }

  @Test
  public void signedMillerAndFusionMatchBinaryDenseReference() throws Exception {
    byte[][] inputs = Bn128TestSupport.fixtures(32);
    for (int n : new int[]{0, 1, 2, 3, 8, 32}) {
      PairingCheck check = Bn128TestSupport.decode(Bn128TestSupport.input(inputs, n, 0, "distinct"));
      Fp12 expected = binaryDenseReference(check.pairs);
      check.run();
      assertEquals(expected, check.product);
    }
    for (int i = 0; i < 8; i++) {
      PairingCheck check = Bn128TestSupport.decode(Bn128TestSupport.concat(inputs[i],
          new byte[192], Bn128TestSupport.negateG1(inputs[i]), inputs[(i + 1) % inputs.length]));
      assertEquals(binaryDenseReference(check.pairs), run(check));
    }
  }

  private static Fp12 run(PairingCheck check) {
    check.run();
    return check.product;
  }

  private static Fp12 sparse(Fp2 c0, Fp2 c2, Fp2 c4) {
    return new Fp12(new Fp6(c0, Fp2.ZERO, c2), new Fp6(Fp2.ZERO, c4, Fp2.ZERO));
  }

  /** Old binary schedule, but deliberately use dense multiplication rather than sparse helpers. */
  private static Fp12 binaryDenseReference(List<PairingCheck.Pair> input) throws Exception {
    Method doubling = PairingCheck.class.getDeclaredMethod("flippedMillerLoopDoubling", BN128G2.class);
    Method addition = PairingCheck.class.getDeclaredMethod("flippedMillerLoopMixedAddition",
        BN128G2.class, BN128G2.class);
    doubling.setAccessible(true);
    addition.setAccessible(true);
    List<PairingCheck.Pair> pairs = new ArrayList<>();
    for (PairingCheck.Pair p : input) {
      if (!p.g1.isZero() && !p.g2.isZero()) {
        pairs.add(PairingCheck.Pair.of(p.g1.toAffine(), p.g2.toAffine()));
      }
    }
    BN128G2[] points = new BN128G2[pairs.size()];
    for (int i = 0; i < points.length; i++) {
      points[i] = pairs.get(i).g2;
    }
    Fp12 f = Fp12._1;
    for (int bit = PairingCheck.LOOP_COUNT.bitLength() - 2; bit >= 0; bit--) {
      f = f.squared();
      for (int k = 0; k < points.length; k++) {
        PairingCheck.Precomputed step = (PairingCheck.Precomputed) doubling.invoke(null, points[k]);
        points[k] = step.g2;
        f = f.mul(line(pairs.get(k).g1, step.coeffs));
        if (PairingCheck.LOOP_COUNT.testBit(bit)) {
          step = (PairingCheck.Precomputed) addition.invoke(null, pairs.get(k).g2, points[k]);
          points[k] = step.g2;
          f = f.mul(line(pairs.get(k).g1, step.coeffs));
        }
      }
    }
    for (int k = 0; k < points.length; k++) {
      BN128G2 q1 = pairs.get(k).g2.mulByP();
      BN128G2 q2 = q1.mulByP();
      q2 = new BN128G2(q2.x, q2.y.negate(), q2.z);
      PairingCheck.Precomputed step = (PairingCheck.Precomputed) addition.invoke(null, q1, points[k]);
      f = f.mul(line(pairs.get(k).g1, step.coeffs));
      step = (PairingCheck.Precomputed) addition.invoke(null, q2, step.g2);
      f = f.mul(line(pairs.get(k).g1, step.coeffs));
    }
    return PairingCheck.finalExponentiation(f);
  }

  private static Fp12 line(BN128G1 p, PairingCheck.EllCoeffs c) {
    return sparse(c.ell0, p.x.mul(c.ellVV), p.y.mul(c.ellVW));
  }

  /** Run the production chain over integers, before any elliptic-curve order can hide an error. */
  private static final class ScalarPoint extends BN128Fp2 {
    private final BigInteger scalar;
    private final int[] operations;

    private ScalarPoint(BigInteger scalar, int[] operations) {
      super(Fp2.ZERO, Fp2.ZERO, Fp2._1);
      this.scalar = scalar;
      this.operations = operations;
    }

    @Override
    BN128<Fp2> dbl() {
      operations[0]++;
      return new ScalarPoint(scalar.shiftLeft(1), operations);
    }

    @Override
    public BN128<Fp2> add(BN128<Fp2> o) {
      operations[1]++;
      return new ScalarPoint(scalar.add(((ScalarPoint) o).scalar), operations);
    }
  }
}
