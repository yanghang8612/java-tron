# TVM Opcode Data-Growth Benchmark Kit — 4.8.2

Turnkey kit for [issue #6292](https://github.com/tronprotocol/java-tron/issues/6292):
measure how state-touching TVM opcode latency grows with on-chain state size, using a real
**mainnet LITE** DB as 1× and `DbExpand` to inflate `account`/`storage-row` to higher scales.

## What's here
| file | purpose |
|---|---|
| `RUNBOOK.md` | step-by-step operator guide (x86 + LevelDB) |
| `METHODOLOGY.md` | verified per-opcode semantics, hit/miss, limitations |
| `op_account.json` | BALANCE + CALL-family — scales with `account` |
| `op_storage.json` | SLOAD_miss + SSTORE_gate — scales with `storage-row` |
| `op_code.json` | EXTCODE* — 1× baseline (not inflatable) |
| `run_scale.sh` | automate one scale point (edit paths at top) |
| `RESULTS_TEMPLATE.md` | what to fill in / send back |

## The port (branch `bench/op-measure-482` = `release_v4.8.2` + harness)
Harness from `aiden3885/java-tron@feat/op_measure_db_expand2`, ported onto current
`release_v4.8.2` (`f15c1ea741`). Additive + minimal:

- **New servlets** (`/wallet/...`): `runOp`, `preOp`, `generateAddress`, `generateContract`,
  `generateStorageKey`, `getoptime` + the `OpServlet` base.
- **`Toolkit db expand`** (`DbExpand`) — LevelDB state inflation (account / storage-row).
- **VM.java**: per-opcode latency instrumentation for the live block-replay path
  (`/wallet/getoptime`); `VM_OPCODE_LATENCY` histogram registered in the metrics layer.

**Adaptations made for a clean 4.8.2 build (not in the original 4.7.7-era harness):**
- fastjson (removed in 4.8.2) → replaced with the project's `JsonUtil` (Jackson) in the servlets.
- `DbExpand` placed under the 4.8.2 arch-split path `plugins/.../common/org/tron/plugins/`.
- Fixed the harness's `storageKey.txt` (writer) vs `storageKeys.txt` (reader) filename bug.
- Seeded `VM.opTimeRecords["blockNumber"]` to remove a `/getoptime` NPE.
- **Deliberately SKIPPED** the harness's benchmark hacks that break a real node — modified
  consensus actuators/processors (one even used `Math.random()`), a raw-key `Storage` read,
  a lite-node query-filter bypass, ad-hoc telemetry. None are needed for opcode timing.

## Build (on the x86 host)
```bash
./gradlew :framework:buildFullNodeJar :plugins:buildToolkitJar -x test -x check
# -> framework/build/libs/FullNode.jar , plugins/build/libs/Toolkit.jar
```

## Verified (locally, arm64)
The harness pipeline was smoke-tested end-to-end on a fresh genesis node:
`generateAddress → preOp → runOp` produced a valid `benchmark/*.txt` with the trimmed-mean +
histogram (BALANCE over `randomAccount`), `getoptime` returned without NPE, and the
`storageKeys.txt` filename now matches. `DbExpand` is wired (`db expand`) and **runs on x86**;
on arm64 it is blocked by the toolkit's `Arch` guard by design — so run the benchmark on x86.

Start at **`RUNBOOK.md`**.
