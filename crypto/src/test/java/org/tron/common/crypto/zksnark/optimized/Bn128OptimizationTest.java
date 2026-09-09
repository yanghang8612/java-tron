package org.tron.common.crypto.zksnark.optimized;

import static org.junit.Assert.assertEquals;

import java.math.BigInteger;
import org.junit.Test;

public class Bn128OptimizationTest {

  @Test
  public void secondRoundArithmeticAndExceptionalPoints() {
    Bn128SecondRoundVerification.verify();
  }

  @Test
  public void arithmeticValidationAndPairingProperties() throws Exception {
    // Captured by running the same corpus against unmodified bd2450fe06 sources.
    assertEquals("VERIFY assertions=73875 sha256="
            + "d57360f3621d7c4b6b52f5e66b3d349e187e5234786457f489123a486d4358f5",
        Bn128Verification.verify());
  }

  @Test
  public void multiMillerAcceptsProjectivePointsAndPreservesCancellation() {
    BN128<Fp> p = Bn128TestSupport.g1().mul(BigInteger.valueOf(12345));
    BN128<Fp2> q = Bn128TestSupport.g2().mul(BigInteger.valueOf(67890));
    PairingCheck projective = PairingCheck.create();
    projective.addPair(new BN128G1(p), new BN128G2(q));
    projective.run();
    PairingCheck affine = PairingCheck.create();
    affine.addPair(new BN128G1(p.toAffine()), new BN128G2(q.toAffine()));
    affine.run();
    assertEquals(affine.product, projective.product);
    assertEquals(0, projective.result());

    PairingCheck cancelling = PairingCheck.create();
    cancelling.addPair(new BN128G1(p), new BN128G2(q));
    cancelling.addPair(new BN128G1(new BN128Fp(p.x, p.y.negate(), p.z)), new BN128G2(q));
    cancelling.run();
    assertEquals(Fp12._1, cancelling.product);
  }
}
