# Benchmark Results — please fill in and send back

## Environment
- Host CPU: (model, cores, GHz) ____
- RAM: ____ GB
- Disk: (NVMe/SSD/HDD) ____
- JDK: ____ (e.g. corretto-8 x86_64)
- Base DB: mainnet LITE snapshot date ____ ; engine: LevelDB
- java-tron build: branch `bench/op-measure-482`, commit ____

## Store sizes per scale (X-axis)
| rate | account size (du -sh) | storage-row size | DbExpand "Expand DB size" (M) |
|---|---|---|---|
| 1× |  |  |  |
| 2× |  |  |  |
| 3× |  |  |  |
| 5× |  |  |  |
| 10× |  |  |  |

## Raw outputs
Paste (or attach) every `benchmark/*.txt` produced, named by scale, e.g.:
```
account_ops_account_1.txt
account_ops_account_3.txt
storage_ops_storage-row_1.txt
code_ops_1.txt        # baseline, 1x only
```
Each line is TSV: `opName  avgCost  minCost  maxCost  avg2(trimmed)  removeNum  histogram` (ns).

### account_ops @ 1×
```
(paste benchmark/account_ops.txt here)
```
### account_ops @ 3×
```
```
### ... (repeat per scale; ≥3 repeats per scale if possible, so I can compute variance)

## Notes / anomalies
- Did any store exceed RAM? at which scale? ____
- Anything that errored or looked off? ____
