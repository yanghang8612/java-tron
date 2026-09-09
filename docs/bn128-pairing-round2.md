# BN128 纯 Java 第二轮优化

实测对照、80ms 边界和局限见 [第二轮结果](bn128-pairing-round2-results-2026-09-09.md)。

第一轮对照提交为 `a05b19e9aa`，原始实现为 `bd2450fe06`。

## 保守算术阶段

- Fp/Fp2 除 2：先保持原有规范化语义，然后偶数右移、奇数加 p 后右移。
- Jacobian 点加：仅当第二点 Z=1 时代入原公式；无穷远点和相同点仍分别处理。
- 曲线上检查：Z=1 时直接使用 b，其他点仍计算 b Z^6。
- G2 子群检查：精确计算 `[r-1]P + P`，固定标量用 NAF 展开减少点加；
  不对标量取模，不使用假定输入属于子群的 endomorphism 恒等式。
- 最终幂的固定种子：仅在 cyclotomic 子群内部使用 NAF 和共轭逆元。
  通用二进制 `cyclotomicExp` 保留作为对照。

原差分语料的 73,875 项断言和全 Fp12 摘要保持不变。
新增 `Bn128SecondRoundVerification` 用 BigInteger 独立验证域运算、各 limb 边界、
NAF 的整数重建、非子群 twist 点、混合坐标异常点及固定最终幂。
这些回归证据不等于独立密码学审计。

## Montgomery 阶段

`FpMontgomery` 使用 8 个 32-bit limb，R=2^256，Fp 内部保存 `xR mod p`。
与 #5611 的 BigInteger REDC 不同，本实现乘法与约简都使用固定宽度 Java 整数。
没有 JNI、Unsafe、对象池、跨交易缓存、概率校验或多线程生产计算。

### 算术边界

1. 所有进入 limb 运算的数都在 `[0,p)`。构造时遇到负数或 `>=p`，保留原值到
   独立的 `raw` 分支；坐标解码器仍使用原来的 `isValid`/曲线/子群检查。
   包内非规范值的算术继续按 BigInteger 模 p 计算；没有提前接受 `p` 或 `p+1`。
2. 加法结果 `<2p<2^255`，最多减一次 p；负的差加一次 p。
3. 乘法先做 8×8 schoolbook 乘积，再逐字消去低 8 个 limb。
   每项累加最多 `(2^32−1)^2 + 2(2^32−1) = 2^64−1`。
   Java long 的低 64 位及无符号 `>>>32` 足以准确表示结果与进位，
   不对可能溢出的乘积做有符号比较。
4. 约简结果 `<p+p²/R<2p<2^255`，没有被丢弃的高有效位，最多减一次 p。
5. Montgomery 表示中的加减和除 2 与普通表示线性等价。转换仅在构造、输出、
   hash/toString 以及求逆处进行；求逆继续依赖 JDK `BigInteger.modInverse`。
6. 每次算术使用自己新建的数组，Fp 中数组为 private final，不对外返回，不修改操作数。
   模数与转换常量独立初始化，避免 Params/Fp/Fp2 之间的静态初始化环。

该实现是针对公开预编译输入的性能优化，不承诺恒定时间，也不适合直接作为秘密标量
密码学库。未修改预编译输入格式、输出、能量、超时或异常处理；上线前仍应独立审计，
并在目标 x86_64/JDK 8 节点做同样的差分和性能复测。

## 历史 PR 参考

[PR #5611](https://github.com/tronprotocol/java-tron/pull/5611) 使用 BigInteger
Montgomery 表示，作者报告过 pairing 耗时下降以及 Add 转换开销增加。
本轮不沿用其移除 `isValid` 的接口改动；坐标不能在检查前按 p 取模，
大于 r 的 256-bit 标量仍是合法乘法输入。
该 PR 的历史测量不作为本轮压测结果。

## 多版本复现

`scripts/bn128_compare.py` 支持额外固定版本，例如：

```bash
python3 scripts/bn128_compare.py --tvm --engines tvm \
  --reference previous=a05b19e9aa --variants baseline,previous,optimized \
  --counts 1,4,8,9,16,32 --samples 301 --forks 3 \
  --warmup-seconds 30 --minimum-warmup 300
```

每个版本在独立 JVM 中执行，输入摘要必须相同。样本、源码和提交信息保存在新建的
build 结果目录中；不能把独立运行/不同机器的尾延迟直接作倍率比较。
旧的 `fields/affine/miller` 是按文件组合的实验配置，可能包含所依赖的其他优化，
不能视为严格的单项归因；第二轮用固定提交的 `--reference` 比较两个完整阶段。

单独比较第二轮两个阶段（保守算术提交为 `13b6cf891b`）：

```bash
python3 scripts/bn128_compare.py --tvm --engines tvm \
  --baseline a05b19e9aa --reference conservative=13b6cf891b \
  --variants baseline,conservative,optimized \
  --counts 1,4,8,16,32 --samples 201 --forks 3 \
  --warmup-seconds 30 --minimum-warmup 300
```

Add/Mul 的转换开销必须另测；下例的 `counts=1` 表示一次预编译调用，不是一个 pair。
使用八组轮换的非零 G1 输入，Mul 的标量均为完整 256-bit 值。预期返回字节提前生成，
加入跨版本输入摘要；每个样本检查成功标志和完整 64-byte 输出。

```bash
python3 scripts/bn128_compare.py --tvm --engines add,mul \
  --baseline a05b19e9aa --counts 1 --samples 1001 --forks 3 \
  --warmup-seconds 30 --minimum-warmup 300
```

`--verify-only` 还运行新增算术断言，并用五个独立 JVM 分别以 Fp、Fp2、Fp12、Params、
BN128G2 为第一个初始化的类，检查静态常量初始化顺序。
