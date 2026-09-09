# BN128 纯 Java 第二轮优化

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
