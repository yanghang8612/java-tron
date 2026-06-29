# Methodology & Per-Opcode Semantics (verified against release_v4.8.2)

The harness (`OpServlet.runOp`) executes **one opcode in isolation**: it builds a `Program`
whose own address = `codeAddress`, pushes the config `stacks`, writes `memory`, then brackets
`System.nanoTime()` around exactly `op.execute(program)` for the opcode at `bytecodes[0]`,
against a fresh `RepositoryImpl.createRoot(StoreFactory.getInstance())` per round (the live
node DB). No energy is charged in the timed path. `round = 100000`; outliers >10× the running
avg are dropped; an 11-bucket histogram around the avg is emitted. **Report the trimmed mean
(`avg2`) + histogram.**

Stack encoding: values are bare hex (no `0x`); `DataWord(String)` = `Hex.decode`. In a
config's `stacks` array, **the last element is pushed last → it is on TOP → popped first**.
Tokens resolved at runtime from dumped corpora:

| token | source file | produced by |
|---|---|---|
| `randomAccount` | `accountAddress.txt` | `/wallet/generateAddress` (all account-store keys) |
| `randomKey` | `storageKeys.txt` | `/wallet/generateStorageKey` (≤4M storage-row keys) |
| `randomContract` | `contractAddress.txt` | `/wallet/generateContract` (contract-store keys) |
| `randomAddress` | — | freshly randomized each use (always a miss) |

## Per-op

| op | code | pop order (top→bottom) | store touched | hit/miss | scales with |
|---|---|---|---|---|---|
| BALANCE | 31 | addr | AccountStore.get | **hit** | `account` |
| CALL / CALLCODE | f1/f2 | gas, to, value, inOff, inSize, outOff, outSize | AccountStore + CodeStore(callee) | **hit, no sub-VM** | `account` |
| DELEGATECALL / STATICCALL | f4/fa | gas, to, inOff, inSize, outOff, outSize | same, no value | hit, no sub-VM | `account` |
| CALLTOKEN | d0 | gas, to, value, tokenId, inOff, inSize, outOff, outSize | same; value=0 → no trc10 move | hit, no sub-VM | `account` |
| SLOAD | 54 | slot | storage-row `get(compose(addr,slot))` | **MISS** (see below) | `storage-row` (negative lookup) |
| SSTORE | 55 | addr/slot, value | AccountStore gate + compose + in-mem put | gate hit; **no row read / no commit** | `account` (gate) |
| EXTCODESIZE | 3b | addr | CodeStore.get | hit | — (not inflatable) |
| EXTCODEHASH | 3f | addr | AccountStore + ContractStore | hit | — (not inflatable) |
| EXTCODECOPY | 3c | addr, memOff, codeOff, len | CodeStore.get + arraycopy | hit | — (not inflatable) |

### Why the calls use `to = randomAccount` (not a contract)
If the callee has code, `op.execute` recurses into a full sub-VM (`VM.play`), which would
contaminate the timing with callee execution. A code-less random **account** callee keeps the
measurement to: AccountStore hit + CodeStore (callee) miss + child-repo bookkeeping — a clean
per-op read number. `value = 0` avoids any transfer side effect.

### Why SLOAD is a structural miss
The on-disk storage-row key is `sha3(contractAddress)[0:16] ‖ slot[16:32]`. `randomKey` is a
*full composed* dumped key; pushed as the SLOAD slot operand, only its low 16 bytes survive —
the high 16 are overwritten by `sha3(codeAddress)`. So the lookup targets a key that ~never
exists. This still measures the **real LevelDB negative-lookup cost** (bloom filter + index +
SST seek) at the inflated tree depth — exactly the cost an attacker pays doing SLOAD on cold
slots, so it is the DoS-relevant growth signal. Label it `SLOAD_miss`.

### Why SSTORE is a gate-read, not a write
The harness times only `op.execute`; `Storage.put` writes to an in-memory `rowCache` and the
DB read/write happens in `commit()`, which the harness never calls. So `SSTORE_gate` measures
the AccountStore gate read + `addrHash`/compose + map insert — an account-store signal, not a
storage write.

## Limitations to state in the issue reply
1. **Code/contract stores are not inflatable** by DbExpand → EXTCODE\* and the call code-load
   read a fixed 1× population; treat them as baseline, not a curve.
2. **SLOAD = negative lookup** by construction (above). Report as miss-path.
3. **SSTORE = gate read**, not a row write.
4. **Single host, micro-bench**: `nanoTime` around one `op.execute`, fresh repository per
   round; numbers reflect this host's CPU + disk + page cache, not absolute network behavior.
5. **Curve only bends above RAM**: if the inflated store fits in page cache, reads are cache
   hits and the curve is flat. Span scales below/above RAM and report RAM. (See RUNBOOK §4.)
6. **Lite base**: account/storage-row inflation is synthetic growth on a truncated-history
   base; the size axis is the lever, absolute values won't match a full archive node.
