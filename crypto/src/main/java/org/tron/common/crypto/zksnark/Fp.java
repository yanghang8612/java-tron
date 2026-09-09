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

import static org.tron.common.crypto.zksnark.FpMontgomery.P;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * Arithmetic in F_p, p = 21888242871839275222246405745257275088696311157297823662689037894645226208583
 *
 * @author Mikhail Kalinin
 * @since 01.09.2017
 */
public class Fp implements Field<Fp> {

  static final Fp ZERO = new Fp(BigInteger.ZERO);
  static final Fp _1 = new Fp(BigInteger.ONE);
  static final Fp NON_RESIDUE = new Fp(new BigInteger(
      "21888242871839275222246405745257275088696311157297823662689037894645226208582"));

  static final Fp _2_INV = new Fp(BigInteger.valueOf(2).modInverse(P));

  private final int[] montgomery;
  // Keep unreduced constructor inputs verbatim. Decoding must reject x >= p, not reduce it.
  // This also preserves the original package-private arithmetic on noncanonical integers.
  private final BigInteger raw;

  Fp(BigInteger v) {
    if (v.signum() >= 0 && v.compareTo(P) < 0) {
      montgomery = FpMontgomery.encode(v);
      raw = null;
    } else {
      montgomery = null;
      raw = v;
    }
  }

  private Fp(int[] ownedMontgomery) {
    montgomery = ownedMontgomery;
    raw = null;
  }

  private BigInteger value() {
    return raw == null ? FpMontgomery.decode(montgomery) : raw;
  }

  static Fp create(byte[] v) {
    return new Fp(new BigInteger(1, v));
  }

  static Fp create(BigInteger v) {
    return new Fp(v);
  }

  @Override
  public Fp add(Fp o) {
    return raw == null && o.raw == null
        ? new Fp(FpMontgomery.add(montgomery, o.montgomery))
        : reduced(value().add(o.value()));
  }

  @Override
  public Fp mul(Fp o) {
    return raw == null && o.raw == null
        ? new Fp(FpMontgomery.multiply(montgomery, o.montgomery))
        : reduced(value().multiply(o.value()));
  }

  @Override
  public Fp sub(Fp o) {
    return raw == null && o.raw == null
        ? new Fp(FpMontgomery.subtract(montgomery, o.montgomery))
        : reduced(value().subtract(o.value()));
  }

  @Override
  public Fp squared() {
    return raw == null ? new Fp(FpMontgomery.multiply(montgomery, montgomery))
        : reduced(raw.multiply(raw));
  }

  @Override
  public Fp dbl() {
    return add(this);
  }

  /** Division by two in Fp; unlike multiplication by 2^-1 this needs no division. */
  Fp half() {
    if (raw == null) {
      return new Fp(FpMontgomery.half(montgomery));
    }
    return reduced(raw).half();
  }

  @Override
  public Fp inverse() {
    // Inversions are infrequent. Retain the JDK implementation and its zero exception.
    return new Fp(value().modInverse(P));
  }

  @Override
  public Fp negate() {
    return raw == null ? new Fp(FpMontgomery.subtract(ZERO.montgomery, montgomery))
        : reduced(raw.negate());
  }

  /** General reduction only for arithmetic on unreduced package-private constructor inputs. */
  private static Fp reduced(BigInteger value) {
    return new Fp(value.mod(P));
  }

  @Override
  public boolean isZero() {
    return raw == null && FpMontgomery.isZero(montgomery);
  }

  /**
   * Checks if provided value is a valid Fp member
   */
  @Override
  public boolean isValid() {
    return raw == null || raw.compareTo(P) < 0;
  }

  Fp2 mul(Fp2 o) {
    return new Fp2(o.a.mul(this), o.b.mul(this));
  }

  public byte[] bytes() {
    return value().toByteArray();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    Fp fp = (Fp) o;

    return raw == null ? fp.raw == null && Arrays.equals(montgomery, fp.montgomery)
        : raw.equals(fp.raw);
  }

  @Override
  public int hashCode() {
    return value().hashCode();
  }

  @Override
  public String toString() {
    return value().toString();
  }
}
