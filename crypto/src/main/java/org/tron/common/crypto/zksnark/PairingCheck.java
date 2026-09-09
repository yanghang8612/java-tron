/*
 * Copyright (c) [2016] [ <ether.camp> ]
 * This file is part of the ethereumJ library.
 *
 * The ethereumJ library is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The ethereumJ library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with the ethereumJ library. If not, see <http://www.gnu.org/licenses/>.
 */
package org.tron.common.crypto.zksnark;

import static org.tron.common.crypto.zksnark.Params.B_Fp2;
import static org.tron.common.crypto.zksnark.Params.PAIRING_FINAL_EXPONENT_Z;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of a Pairing Check operation over points of two twisted Barreto–Naehrig curves
 * {@link BN128Fp}, {@link BN128Fp2}<br/> <br/>
 *
 * The Pairing itself is a transformation of the form G1 x G2 -> Gt, <br/> where G1 and G2 are
 * members of {@link BN128G1} {@link BN128G2} respectively, <br/> Gt is a subgroup of roots of unity
 * in {@link Fp12} field, root degree equals to {@link Params#R} <br/> <br/>
 *
 * Pairing Check input is a sequence of point pairs, the result is either 1 or 0, 1 is considered as
 * success, 0 as fail <br/> <br/>
 *
 * Usage: <ul> <li>add pairs sequentially with {@link #addPair(BN128G1, BN128G2)}</li> <li>run check
 * with {@link #run()} after all paris have been added</li> <li>get result with {@link
 * #result()}</li> </ul>
 *
 * Arithmetic has been ported from <a href="https://github.com/scipr-lab/libff/blob/master/libff/algebra/curves/alt_bn128/alt_bn128_pairing.cpp">libff</a>
 * Ate pairing algorithms
 *
 * @author Mikhail Kalinin
 * @since 01.09.2017
 */
public class PairingCheck {

  static final BigInteger LOOP_COUNT = new BigInteger("29793968203157093288");

  List<Pair> pairs = new ArrayList<>();
  Fp12 product = Fp12._1;

  private PairingCheck() {
  }

  public static PairingCheck create() {
    return new PairingCheck();
  }

  private Fp12 millerLoop() {
    // Infinity contributes one, but input validation has already happened in the decoder.
    List<Pair> active = new ArrayList<>(pairs.size());
    for (Pair pair : pairs) {
      if (!pair.g1.isZero() && !pair.g2.isZero()) {
        active.add(Pair.of(pair.g1.toAffine(), pair.g2.toAffine()));
      }
    }
    if (active.isEmpty()) {
      return Fp12._1;
    }
    BN128G2[] addends = new BN128G2[active.size()];
    for (int k = 0; k < active.size(); k++) {
      addends[k] = active.get(k).g2;
    }

    // Product of Miller loops: (f1 * ... * fn)^2 = f1^2 * ... * fn^2.
    // Share the Fp12 square across all pairs and consume each line immediately,
    // retaining only O(number of pairs) state instead of a table of all lines.
    Fp12 f = Fp12._1;
    for (int i = LOOP_COUNT.bitLength() - 2; i >= 0; i--) {
      if (i != LOOP_COUNT.bitLength() - 2) { // the first square would be 1^2
        f = f.squared();
      }
      for (int k = 0; k < active.size(); k++) {
        Pair pair = active.get(k);
        Precomputed doubling = flippedMillerLoopDoubling(addends[k]);
        addends[k] = doubling.g2;
        f = multiplyLine(f, pair.g1, doubling.coeffs);
        if (LOOP_COUNT.testBit(i)) {
          Precomputed addition = flippedMillerLoopMixedAddition(pair.g2, addends[k]);
          addends[k] = addition.g2;
          f = multiplyLine(f, pair.g1, addition.coeffs);
        }
      }
    }
    for (int k = 0; k < active.size(); k++) {
      Pair pair = active.get(k);
      BN128G2 q1 = pair.g2.mulByP();
      BN128G2 q2 = q1.mulByP();
      q2 = new BN128G2(q2.x, q2.y.negate(), q2.z);
      Precomputed addition = flippedMillerLoopMixedAddition(q1, addends[k]);
      f = multiplyLine(f, pair.g1, addition.coeffs);
      addition = flippedMillerLoopMixedAddition(q2, addition.g2);
      f = multiplyLine(f, pair.g1, addition.coeffs);
    }
    return f;
  }

  private static Fp12 multiplyLine(Fp12 f, BN128G1 g1, EllCoeffs c) {
    return f.mulBy024(c.ell0, g1.y.mul(c.ellVW), g1.x.mul(c.ellVV));
  }

  private static Precomputed flippedMillerLoopMixedAddition(BN128G2 base, BN128G2 addend) {

    Fp2 x1 = addend.x, y1 = addend.y, z1 = addend.z;
    Fp2 x2 = base.x, y2 = base.y;

    Fp2 d = x1.sub(x2.mul(z1));             // d = x1 - x2 * z1
    Fp2 e = y1.sub(y2.mul(z1));             // e = y1 - y2 * z1
    Fp2 f = d.squared();                    // f = d^2
    Fp2 g = e.squared();                    // g = e^2
    Fp2 h = d.mul(f);                       // h = d * f
    Fp2 i = x1.mul(f);                      // i = x1 * f
    Fp2 j = h.add(z1.mul(g)).sub(i.dbl());  // j = h + z1 * g - 2 * i

    Fp2 x3 = d.mul(j);                           // x3 = d * j
    Fp2 y3 = e.mul(i.sub(j)).sub(h.mul(y1));     // y3 = e * (i - j) - h * y1)
    Fp2 z3 = z1.mul(h);                          // z3 = Z1*H

    Fp2 ell0 = e.mul(x2).sub(d.mul(y2)).mulByNonResidue();
    Fp2 ellVV = e.negate();                             // ell_VV = -e
    Fp2 ellVW = d;                                      // ell_VW = d

    return Precomputed.of(
        new BN128G2(x3, y3, z3),
        new EllCoeffs(ell0, ellVW, ellVV)
    );
  }

  private static Precomputed flippedMillerLoopDoubling(BN128G2 g2) {

    Fp2 x = g2.x, y = g2.y, z = g2.z;

    Fp2 a = x.mul(y).half();                   // a = x * y / 2
    Fp2 b = y.squared();                        // b = y^2
    Fp2 c = z.squared();                        // c = z^2
    Fp2 d = c.add(c).add(c);                    // d = 3 * c
    Fp2 e = B_Fp2.mul(d);                       // e = twist_b * d
    Fp2 f = e.add(e).add(e);                    // f = 3 * e
    Fp2 g = b.add(f).half();                   // g = (b + f) / 2
    Fp2 h = y.add(z).squared().sub(b.add(c));   // h = (y + z)^2 - (b + c)
    Fp2 i = e.sub(b);                           // i = e - b
    Fp2 j = x.squared();                        // j = x^2
    Fp2 e2 = e.squared();                       // e2 = e^2

    Fp2 rx = a.mul(b.sub(f));                       // rx = a * (b - f)
    Fp2 ry = g.squared().sub(e2.add(e2).add(e2));   // ry = g^2 - 3 * e^2
    Fp2 rz = b.mul(h);                              // rz = b * h

    Fp2 ell0 = i.mulByNonResidue(); // ell_0 = twist * i, where twist = 9 + sqrt(-1)
    Fp2 ellVW = h.negate();         // ell_VW = -h
    Fp2 ellVV = j.add(j).add(j);    // ell_VV = 3 * j

    return Precomputed.of(
        new BN128G2(rx, ry, rz),
        new EllCoeffs(ell0, ellVW, ellVV)
    );
  }

  public static Fp12 finalExponentiation(Fp12 el) {

    // first chunk
    Fp12 w = new Fp12(el.a, el.b.negate()); // el.b = -el.b
    Fp12 x = el.inverse();
    Fp12 y = w.mul(x);
    Fp12 z = y.frobeniusMap(2);
    Fp12 pre = z.mul(y);

    // last chunk
    Fp12 a = pre.negExp(PAIRING_FINAL_EXPONENT_Z);
    Fp12 b = a.cyclotomicSquared();
    Fp12 c = b.cyclotomicSquared();
    Fp12 d = c.mul(b);
    Fp12 e = d.negExp(PAIRING_FINAL_EXPONENT_Z);
    Fp12 f = e.cyclotomicSquared();
    Fp12 g = f.negExp(PAIRING_FINAL_EXPONENT_Z);
    Fp12 h = d.unitaryInverse();
    Fp12 i = g.unitaryInverse();
    Fp12 j = i.mul(e);
    Fp12 k = j.mul(h);
    Fp12 l = k.mul(b);
    Fp12 m = k.mul(e);
    Fp12 n = m.mul(pre);
    Fp12 o = l.frobeniusMap(1);
    Fp12 p = o.mul(n);
    Fp12 q = k.frobeniusMap(2);
    Fp12 r = q.mul(p);
    Fp12 s = pre.unitaryInverse();
    Fp12 t = s.mul(l);
    Fp12 u = t.frobeniusMap(3);
    Fp12 v = u.mul(r);

    return v;
  }

  public void addPair(BN128G1 g1, BN128G2 g2) {
    pairs.add(Pair.of(g1, g2));
  }

  public void run() {
    Fp12 miller = millerLoop();
    if (!miller.equals(Fp12._1)) {
      product = product.mul(miller);
    }
    if (!product.equals(Fp12._1)) {
      product = finalExponentiation(product);
    }
  }

  public int result() {
    return product.equals(Fp12._1) ? 1 : 0;
  }

  static class Precomputed {

    BN128G2 g2;
    EllCoeffs coeffs;

    Precomputed(BN128G2 g2, EllCoeffs coeffs) {
      this.g2 = g2;
      this.coeffs = coeffs;
    }

    static Precomputed of(BN128G2 g2, EllCoeffs coeffs) {
      return new Precomputed(g2, coeffs);
    }
  }

  static class Pair {

    BN128G1 g1;
    BN128G2 g2;

    Pair(BN128G1 g1, BN128G2 g2) {
      this.g1 = g1;
      this.g2 = g2;
    }

    static Pair of(BN128G1 g1, BN128G2 g2) {
      return new Pair(g1, g2);
    }

  }

  static class EllCoeffs {

    Fp2 ell0;
    Fp2 ellVW;
    Fp2 ellVV;

    EllCoeffs(Fp2 ell0, Fp2 ellVW, Fp2 ellVV) {
      this.ell0 = ell0;
      this.ellVW = ellVW;
      this.ellVV = ellVV;
    }
  }
}
