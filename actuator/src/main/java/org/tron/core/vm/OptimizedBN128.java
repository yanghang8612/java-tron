package org.tron.core.vm;

import static org.tron.common.utils.ByteUtil.EMPTY_BYTE_ARRAY;
import static org.tron.common.utils.ByteUtil.parseWord;
import static org.tron.common.utils.ByteUtil.stripLeadingZeroes;

import org.apache.commons.lang3.tuple.Pair;
import org.tron.common.crypto.zksnark.optimized.BN128;
import org.tron.common.crypto.zksnark.optimized.BN128Fp;
import org.tron.common.crypto.zksnark.optimized.BN128G1;
import org.tron.common.crypto.zksnark.optimized.BN128G2;
import org.tron.common.crypto.zksnark.optimized.ExecutionDeadline;
import org.tron.common.crypto.zksnark.optimized.Fp;
import org.tron.common.crypto.zksnark.optimized.PairingCheck;
import org.tron.common.runtime.vm.DataWord;
import org.tron.common.utils.BIUtil;

/** BN128 precompile adapter used only after ALLOW_OPTIMIZE_TVM activates. */
final class OptimizedBN128 {

  private static final int PAIR_SIZE = 192;

  private OptimizedBN128() {
  }

  static Pair<Boolean, byte[]> add(byte[] data) {
    if (data == null) {
      data = EMPTY_BYTE_ARRAY;
    }
    byte[] x1 = parseWord(data, 0);
    byte[] y1 = parseWord(data, 1);
    byte[] x2 = parseWord(data, 2);
    byte[] y2 = parseWord(data, 3);
    BN128<Fp> p1 = BN128Fp.create(x1, y1);
    if (p1 == null) {
      return Pair.of(false, EMPTY_BYTE_ARRAY);
    }
    BN128<Fp> p2 = BN128Fp.create(x2, y2);
    if (p2 == null) {
      return Pair.of(false, EMPTY_BYTE_ARRAY);
    }
    BN128<Fp> res = p1.add(p2).toEthNotation();
    return Pair.of(true, encodeRes(res.x().bytes(), res.y().bytes()));
  }

  static Pair<Boolean, byte[]> multiply(byte[] data, long deadlineInUs) {
    if (data == null) {
      data = EMPTY_BYTE_ARRAY;
    }
    byte[] x = parseWord(data, 0);
    byte[] y = parseWord(data, 1);
    byte[] s = parseWord(data, 2);
    BN128<Fp> p = BN128Fp.create(x, y);
    if (p == null) {
      return Pair.of(false, EMPTY_BYTE_ARRAY);
    }
    BN128<Fp> res = p.mul(BIUtil.toBI(s), deadlineInUs);
    if (res == null) {
      return Pair.of(false, EMPTY_BYTE_ARRAY);
    }
    res = res.toEthNotation();
    return Pair.of(true, encodeRes(res.x().bytes(), res.y().bytes()));
  }

  static Pair<Boolean, byte[]> pairing(byte[] data, long deadlineInUs) {
    if (data == null) {
      data = EMPTY_BYTE_ARRAY;
    }
    if (data.length % PAIR_SIZE > 0) {
      return Pair.of(false, EMPTY_BYTE_ARRAY);
    }
    PairingCheck check = PairingCheck.create(deadlineInUs);
    for (int offset = 0; offset < data.length; offset += PAIR_SIZE) {
      if (ExecutionDeadline.isExceeded(deadlineInUs)) {
        return Pair.of(false, EMPTY_BYTE_ARRAY);
      }
      Pair<BN128G1, BN128G2> pair = decodePair(data, offset, deadlineInUs);
      if (pair == null) {
        return Pair.of(false, EMPTY_BYTE_ARRAY);
      }
      check.addPair(pair.getLeft(), pair.getRight());
    }
    if (!check.run()) {
      return Pair.of(false, EMPTY_BYTE_ARRAY);
    }
    return Pair.of(true, new DataWord(check.result()).getData());
  }

  private static Pair<BN128G1, BN128G2> decodePair(byte[] in, int offset,
      long deadlineInUs) {
    byte[] x = parseWord(in, offset, 0);
    byte[] y = parseWord(in, offset, 1);
    BN128G1 p1 = BN128G1.create(x, y);
    if (p1 == null) {
      return null;
    }
    byte[] b = parseWord(in, offset, 2);
    byte[] a = parseWord(in, offset, 3);
    byte[] d = parseWord(in, offset, 4);
    byte[] c = parseWord(in, offset, 5);
    BN128G2 p2 = BN128G2.create(a, b, c, d, deadlineInUs);
    if (p2 == null) {
      return null;
    }
    return Pair.of(p1, p2);
  }

  private static byte[] encodeRes(byte[] w1, byte[] w2) {
    byte[] res = new byte[64];
    w1 = stripLeadingZeroes(w1);
    w2 = stripLeadingZeroes(w2);
    System.arraycopy(w1, 0, res, 32 - w1.length, w1.length);
    System.arraycopy(w2, 0, res, 64 - w2.length, w2.length);
    return res;
  }
}
