/*
 * Derived from gnark-crypto v0.19.2, ecc/bn254/g2.go.
 * Copyright 2020-2025 Consensys Software Inc.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * Java adaptation: immutable point operations, existing twist constants, execution deadline.
 */
package org.tron.common.crypto.zksnark.optimized;

/** Fast r-torsion test on the BN254 twist, as used by Besu's gnark backend. */
final class G2SubgroupCheck {

  private G2SubgroupCheck() {
  }

  /** The caller MUST already have checked field membership and the twist curve equation. */
  static boolean isGroupMember(BN128<Fp2> point, long deadlineInUs) {
    if (point.isZero()) {
      return true;
    }
    // For u = 4965661367192848881 and psi the twist Frobenius, test
    // [2]psi^3([u]Q) = psi^2([u]Q) + psi([u]Q) + [u]Q + Q.
    // multiplyBySeed is exact on the FULL twist: no scalar reduction or subgroup GLV.
    BN128<Fp2> a = multiplyBySeed(point, deadlineInUs);
    if (a == null) {
      return false;
    }
    BN128G2 b = new BN128G2(a).mulByP();
    BN128G2 c = b.mulByP();
    BN128<Fp2> right = c.add(b).add(a.add(point));
    BN128<Fp2> left = c.mulByP().dbl();
    return sameJacobianPoint(left, right);
  }

  /** Compare Jacobian points without inversion; BN128.equals compares raw coordinates. */
  static boolean sameJacobianPoint(BN128<Fp2> p, BN128<Fp2> q) {
    if (p.isZero() || q.isZero()) {
      return p.isZero() && q.isZero();
    }
    Fp2 pz2 = p.z.squared();
    Fp2 qz2 = q.z.squared();
    return p.x.mul(qz2).equals(q.x.mul(pz2))
        && p.y.mul(qz2.mul(q.z)).equals(q.y.mul(pz2.mul(p.z)));
  }

  /** gnark's exact addition chain: 53 doublings and 25 additions, no modulo-r assumption. */
  static BN128<Fp2> multiplyBySeed(BN128<Fp2> q, long deadlineInUs) {
    BN128<Fp2> res = q.dbl();
    BN128<Fp2> t0 = q.add(res);
    BN128<Fp2> t2 = q.add(t0);
    BN128<Fp2> t1 = res.add(t2);
    res = t1.dbl().add(t0);
    t0 = t0.add(res);
    t2 = t2.add(t0);
    t1 = t1.add(t2);
    t0 = t0.add(t1);
    t1 = t1.add(t0);
    t0 = t0.add(t1);
    t2 = t2.add(t0);
    BN128<Fp2> t3 = t2.dbl();
    t1 = t1.add(t3);
    t2 = t2.add(t1);
    res = res.add(t2);
    t2 = t2.add(res);
    t3 = t2.dbl();
    res = res.add(t3);
    t0 = t0.add(res);
    t1 = t1.add(t0);
    t3 = t1.dbl().dbl().add(t1);
    t2 = t2.add(t3);
    t1 = t1.add(t2);
    t2 = t2.add(t1);
    t2 = doubleTimes(t2, 17, deadlineInUs);
    if (t2 == null) {
      return null;
    }
    t1 = t1.add(t2);
    t1 = doubleTimes(t1, 14, deadlineInUs);
    if (t1 == null) {
      return null;
    }
    t0 = t0.add(t1);
    t0 = doubleTimes(t0, 16, deadlineInUs);
    if (t0 == null) {
      return null;
    }
    return res.add(t0);
  }

  private static BN128<Fp2> doubleTimes(BN128<Fp2> point, int count, long deadlineInUs) {
    for (int i = 0; i < count; i++) {
      if (ExecutionDeadline.isExceeded(deadlineInUs)) {
        return null;
      }
      point = point.dbl();
    }
    return point;
  }
}
