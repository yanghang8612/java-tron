# BN128 Pairing 保守优化与复现

这组改动优化 TVM BN128 Pairing 的纯 Java 算术和 Miller loop。
对照版本固定为 `bd2450fe06`，不依赖基准测试时的 `master` 指向。
生产改动保留原有 G1/G2 解码、坐标范围检查、曲线上检查、完整 G2 子群检查、
BigInteger 乘法/平方/求逆、最终幂算法、能量计费和 TVM 超时机制。

## 实现范围

| 改动 | 等价关系或前提 |
| --- | --- |
| Fp 加减、倍增和取负用条件加减 p 约简 | 规范元素的结果最多需要一次修正；非规范值仍回退 `BigInteger.mod(p)` |
| 简化 Fp2 平方、乘法和求逆 | 扩域满足 `i² = -1`，乘以 p−1 可化为取负；平方实部为 `(a+b)(a-b)` |
| 专门计算乘以 `9+i` | `(a+bi)(9+i) = (9a-b)+(a+9b)i`，9 倍用倍增和加法 |
| Fp2 Frobenius 用共轭 | 奇数次取虚部的负值，偶数次保持；仍规范化原先需要约简的虚部 |
| `z=1` 的仿射快速路径 | 合法仿射输入无须再次求逆、平方和坐标缩放 |
| 点加重用已算好的 z² | 只消除同一调用内的重复平方 |
| multi-Miller loop | 每个 bit 共享一次 Fp12 平方：`(∏fᵢ)² = ∏fᵢ²`，各 pair 的倍点、加点和末尾 Frobenius 修正保持原公式 |
| 即时消费直线系数 | 不再为每个 Miller loop 创建完整的直线系数列表；无跨调用缓存 |
| 跳过 `1²` 和对 1 的最终幂 | 恒等元的确定性简化；所有输入仍先经过完整校验 |

固定 limb/Montgomery 后端、用 endomorphism 替换 G2 子群检查、随机化批量校验、
共享可变对象池、跨交易缓存及多线程执行均未纳入这次保守改动。
这些方案需要各自的独立验证，不能由本次结果推断其安全性或收益。

## 正确性验证

`Bn128Verification` 使用固定种子，同时检查代数性质并生成全量结果摘要。
它在旧实现和新实现中分别编译、分别运行，摘要必须完全一致；JUnit 中还固定了旧实现的摘要。
每次执行覆盖 73,875 项断言，包括：

- Fp 的边界、负数和超过一个模数的非规范值，与直接 BigInteger 运算对比。
- Fp2 平方、乘法、逆元、Frobenius 和乘以 `9+i`。
- Fp12 平方、逆元、稀疏乘法、cyclotomic squaring。
- G1/G2 标量乘法、点加、非单位 Jacobian Z 的坐标转换。
- 正确和不成立的 pairing、相消、无穷远点、空输入、错误长度及最多 128 pair 的输入。
- 越界坐标、非曲线点，以及 32 个在 twist 曲线上但不在正确子群的点。
- G1 为无穷远点时仍拒绝非法 G2；前面有合法 pair 时仍拒绝后面的非法 pair。
- 同一个 `PairingCheck` 多次 `run()` 的原有累积行为。

摘要还包含最终 Fp12 的全部 12 个底域分量，而非只比较 0/1，
避免“不成立的 pairing 始终返回 0”掩盖算术错误。
额外的 JUnit 测试覆盖 projective 输入的 multi-Miller、一对相消的 projective 点、
真实预编译返回字节、旧/新能量计费以及 BN128 Add/Mul 的一致性。

原版与优化版在本机 Corretto 17.0.19 ARM64 和 Zulu 8u482 ARM64 上的摘要均为：

```text
VERIFY assertions=73875 sha256=d57360f3621d7c4b6b52f5e66b3d349e187e5234786457f489123a486d4358f5
```

这提供了等价性回归证据，不代表密码学实现已经经过独立安全审计。

## 服务器复现

脚本支持 JDK 8，并只用 Python 3 标准库。无需安装 Python 依赖。
默认每组 3 个独立 JVM、每 JVM 301 个正式样本，warmup 至少 30 秒且至少 300 次调用。

先做新旧实现差分检查，无须下载或编译 java-tron 的其他依赖：

```bash
python3 scripts/bn128_compare.py --java-home "$JAVA_HOME" --verify-only
```

完整 TVM CALL 压测，自动构建实际预编译和 TVM 所需 classpath：

```bash
python3 scripts/bn128_compare.py \
  --java-home "$JAVA_HOME" --tvm --engines tvm \
  --counts 1,2,3,4,5,6,7,8,9,16,32 \
  --samples 301 --forks 3 \
  --warmup-seconds 30 --minimum-warmup 300
```

如需同时测实际预编译直调入口，去掉 `--engines tvm`，默认会依次测 `direct,tvm`。
只想测含完整 G2 校验的密码学路径、跳过 TVM 依赖构建，则去掉 `--tvm`，默认使用 `core`。
`core` 的解码顺序与当前预编译一致，但不能用它的成功率代替 TVM 的真实超时率。

计数支持 `1..256`，例如用 `--counts 1,4,8,16,32,64,128,256` 测更大的输入。
大输入仍会完整计算，再由后续 TVM 指令检查超时，因此总运行时间可能很长。

每次调用都会新建结果目录并启动新 JVM，不受 Gradle `UP-TO-DATE` 或测试缓存影响。
需要重复测同一构建时，可复用本机刚生成的 classpath：

```bash
python3 scripts/bn128_compare.py \
  --engines tvm \
  --classpath-file actuator/build/bn128-benchmark-classpath.txt \
  --counts 3,4,5,7,8,9 --samples 501 --forks 3
```

classpath 文件包含绝对路径，不能从其他服务器直接复制使用；更换 JDK/构建后应重新生成。
`--baseline` 可以覆盖基线 commit；基准脚本会先用同一套差分验证确认候选行为没有变化。

## 测量方式与输出

- 新旧版本在不同 JVM 中执行，所有参数相同：默认 `-Xms1g -Xmx1g -XX:+UseParallelGC`。
- 3 个 fork 的先后顺序为旧/新、新/旧、旧/新；多个 pair 数在采样时打乱顺序。
- 默认输入为不同的非零有效 G1/G2 点，使用完整宽度标量构造。每组轮换 8 份输入。
- 输入构造与 TVM `Program`/mock repository 的创建均不计入 TVM 执行时间。
- 正式计时包含 `CALLDATACOPY → CALL(0x08) → RETURN`，验证 CALL 成功标志及返回字节。
- 除真实 TVM 超时次数外，还单独报告墙钟耗时超过 80ms 的次数，避免只依赖指令边界计时。
- 测量不运行在 Gradle 测试进程中，不装载 JaCoCo；不在采样期间调用 `System.gc()`。
- 分配量来自当前 Java 线程的 ThreadMXBean；TVM 路径的分配量包含计时外的 Program 构造。
- `--scenarios repeated` 可另测重复点输入；不要把它与不同点输入混合统计。
- `--variants baseline,fields,affine,miller,optimized` 可拆分比较各组改动；这些中间组合仅用于实验。

输出目录位于 `build/bn128-compare-时间/`，包含：

- `manifest.json`：基线 commit、候选源码 SHA-256、JVM 参数及系统信息。
- `candidate.patch`：此次候选生产代码相对基线的 diff。
- `*-verification.txt`：各实现的回归摘要。
- `*.log`：各 JVM warmup 次数/时长、每个原始样本、超时标记及分配量。
- `runs.json`：结构化的逐 fork 原始样本。
- `summary.json` / `summary.md`：合并样本的 P50/P95/P99、均值、超时率及倍率。

倍率用同一 pair 数的旧版 P50 除以新版 P50。均值降低比例与倍率不同：
耗时减半对应 2 倍速度。80ms 容量同时区分 P99 达标与全部实测样本达标。
它们都是当前机器、JVM、负载和样本集下的观测，不是主网所有节点的保证。

本次完整压测结果见 [结果报告](bn128-pairing-results-2026-09-09.md)。
