# TVM Opcode Data-Growth Benchmark — Operator Runbook (4.8.2)

Issue: [tronprotocol/java-tron#6292](https://github.com/tronprotocol/java-tron/issues/6292)
Branch: `bench/op-measure-482` (op_measure harness ported onto `release_v4.8.2`)

This runbook measures how the latency of **state-touching TVM opcodes** grows as the
on-chain state (`account`, `storage-row`) grows. It uses a real **mainnet LITE** database
as the 1× baseline and `DbExpand` to synthetically inflate the state to higher scales.

---

## 0. What you need

- An **x86_64 Linux** server. (DbExpand uses LevelDB, which the 4.8.2 toolkit blocks on
  arm64 — see Appendix A. The benchmark must run on x86.)
- The JDK this project builds with (JDK8 for x86 per the repo's arch rules).
- A **mainnet LITE** `output-directory` (LevelDB engine — the default; **not** RocksDB).
  The official lite snapshot works:
  `http://34.86.86.229/backupYYYYMMDD/LiteFullNode_output-directory.tgz` (~61 GB packed).
- Free disk ≈ **base state size + one expanded copy** (we expand one scale at a time and
  delete it before the next, so peak disk stays bounded — see §3).
- The two jars built **on this x86 host** from the `bench/op-measure-482` branch:
  - `framework/build/libs/FullNode.jar`
  - `plugins/build/libs/Toolkit.jar`

Build (on the x86 host):
```bash
./gradlew :framework:buildFullNodeJar :plugins:buildToolkitJar -x test -x check
```

---

## 1. Why an *isolated* node (read this — it affects correctness)

The inflated state is **synthetic** (random keys with real account values). It is **not
consensus-valid**. The benchmark node must therefore run **isolated**: it must NOT connect
to peers and must NOT try to sync — syncing on a tampered DB would fail validation / fork.

The node only needs to **open the static DB and serve the harness HTTP endpoints**. No
block production, no P2P. Configure (`bench-node.conf`, derived from your **mainnet**
config so genesis/chainId match the lite DB):

```
node {
  http { fullNodeEnable = true,  fullNodePort = 8190 }   # enable the harness endpoints
  p2p  { version = 11111 }                                # any isolated id
  active   = []
  passive  = []
}
seed.node = { ip.list = [ ] }                             # ISOLATED: no peers, no sync
block { needSyncCheck = false }                            # don't wait on sync
```
Keep everything else (genesis.block, storage engine = leveldb, etc.) identical to your
mainnet config so the lite DB opens cleanly.

---

## 2. The opcode configs (in this kit)

Copy the `op_*.json` files into the **working directory** from which you launch
`FullNode.jar` (the harness reads `<op_config>.json` from the node's CWD and writes results
to `./benchmark/`).

| config | opcodes | scales with | what it measures |
|---|---|---|---|
| `op_account.json` | BALANCE, CALL, CALLCODE, DELEGATECALL, STATICCALL, CALLTOKEN | **`account`** store size | account-store read latency (real hit) |
| `op_storage.json` | SLOAD_miss, SSTORE_gate | **`storage-row`** store size | storage-row negative-lookup / SSTORE gate read |
| `op_code.json` | EXTCODESIZE, EXTCODEHASH, EXTCODECOPY | — (**not inflatable**, 1× only) | code/contract read baseline |

Read `METHODOLOGY.md` for exactly what each op touches and the hit/miss semantics. The
short version:
- **BALANCE / CALL-family** genuinely hit the `account` store at scale → primary curve.
- **SLOAD** is a structural **miss** (storage-row key = `sha3(addr)[0:16] ‖ slot[16:32]`,
  not reproducible from a dumped key) → it measures the **negative-lookup cost** as the
  store grows. Report it as a miss-path number, not a hit.
- **SSTORE** in the timed path only reads the account gate + composes the key (no row read,
  no commit) → an account-store signal, not a write benchmark.
- **EXTCODE\*** read the `code`/`contract` stores, which `DbExpand` does **not** inflate →
  they stay at 1× regardless of scale; keep them as a fixed baseline only.

`round` is 100000 per op. The harness drops outliers (>10× avg) and emits an 11-bucket
histogram; report the **trimmed mean** (`avg2`) + histogram, not the raw avg.

---

## 3. Per-scale loop (account curve)

Run scales **in increasing order**. Helper script: `run_scale.sh` (edit the paths at the
top). Manual steps per scale `R` ∈ {1, 2, 3, 5, 10}:

```bash
BASE=/data/lite-base/database          # pristine mainnet-lite database dir (LevelDB)
WORK=/data/bench                        # scratch (needs base + one expanded copy of free disk)
PORT=8190

# ---- 3.1 expand the account CF to rate R (R=1 means: skip expand, use base as-is) ----
#   target-type 1 = originals + real-valued synthetic cold accounts (every key resolves
#   to a real account). --expend-rate is the integer multiplier (note: "expend", misspelled).
java -jar Toolkit.jar db expand \
  --database        "$BASE/.." \
  --target-database "$WORK/scale-$R/expanded" \
  --target-db       account \
  --target-type     1 \
  --expend-rate     "$R"
#   (DbExpand prints "DB size: <M> M" and "Expand DB size: <M> M" — record both.)

# ---- 3.2 assemble a full DB dir: all base CFs, with account replaced by the expanded one
cp -r "$BASE" "$WORK/scale-$R/database"
rm -rf "$WORK/scale-$R/database/account"
cp -r "$WORK/scale-$R/expanded/account" "$WORK/scale-$R/database/account"
du -sh "$WORK/scale-$R/database/account"          # X-axis: on-disk account size

# ---- 3.3 start the ISOLATED bench node on the assembled DB ----
( cd "$WORK/scale-$R" && cp /path/to/op_account.json op.json \
   && nohup java -jar FullNode.jar -c bench-node.conf -d "$WORK/scale-$R/outdir" \
        >/dev/null 2>&1 & )
# NOTE: point storage.db.directory (in bench-node.conf) at "$WORK/scale-$R/database",
#       or arrange -d so the node opens the assembled DB. Wait until the HTTP port answers.

# ---- 3.4 dump operand corpora AGAINST the inflated store ----
curl -s "http://127.0.0.1:$PORT/wallet/generateAddress"     # -> accountAddress.txt
curl -s "http://127.0.0.1:$PORT/wallet/generateContract"    # -> contractAddress.txt
# (generateStorageKey only needed for op_storage)

# ---- 3.5 warm up, then measure ----
curl -s "http://127.0.0.1:$PORT/wallet/preOp?op_config=op_account"
curl -s "http://127.0.0.1:$PORT/wallet/runOp?op_config=op_account"
#   -> ./benchmark/account_ops.txt   (TSV: opName avgCost minCost maxCost avg2 removeNum hist; ns)

# ---- 3.6 collect & advance ----
cp ./benchmark/account_ops.txt /results/account_ops_scale-$R.txt
#   stop the node, then:
rm -rf "$WORK/scale-$R"            # reclaim disk before the next scale
```

**Storage curve:** same loop with `--target-db storage-row`, `op_config=op_storage`, and
run `generateStorageKey` in 3.4. **Code baseline:** run `op_code` once at 1× only.

---

## 4. Make the curve meaningful (critical)

The signal you want — disk/index cost growing with state size — only appears when the
**inflated store exceeds the OS page cache / RAM**. If the whole store fits in RAM, every
read is a cache hit and the curve is flat (measuring CPU, not data growth).

- Record the host **RAM**. Choose scales so the `account`/`storage-row` size spans **below
  and above** RAM (e.g. if RAM=64 GB and base account=30 GB, scales 1×/2×/3× straddle it).
- Operands are **random across the whole keyspace** (`randomAccount`/`randomKey`), so a
  store >> RAM forces real LevelDB index/SST/bloom work — the DoS-relevant cost.
- Run scales **increasing**, and **repeat each scale ≥3×** (restart node between repeats to
  reset the in-process repository cache) to get variance. Report mean ± stdev of the
  trimmed `avg2` across repeats.
- Capture `free -g`, the host CPU, and `du -sh` of the store per scale alongside results.

---

## 5. What to send back

For each (op, scale) cell, the `benchmark/*.txt` files plus, per scale: the `du -sh` store
size, the DbExpand "DB size" lines, host RAM/CPU, and JDK. Drop them into
`RESULTS_TEMPLATE.md` (or just paste the raw `.txt` + sizes) and I'll build the curves,
variance, and the issue write-up.

---

## Appendix A — arm64 / engine constraints
- The 4.8.2 toolkit throws `LEVELDB: unsupported on aarch64` (`Arch.throwIfUnsupportedArm64Exception`).
  `DbExpand` is LevelDB-only, so it runs on **x86 only**. (The FullNode itself runs on arm64,
  but the expand step does not.)
- `DbExpand` inflates only `account` and `storage-row` (it rejects other db names). It cannot
  inflate `code`/`contract`, so EXTCODE\*/call-code reads stay at 1×.
- Use `--target-type 1`. Avoid `--target-type 4` (writes empty values that read back as
  absent accounts — a miss, not a hit).
