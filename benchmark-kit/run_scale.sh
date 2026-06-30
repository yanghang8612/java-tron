#!/usr/bin/env bash
# Run ONE scale point of the TVM data-growth benchmark. x86_64 + LevelDB only.
# Usage: ./run_scale.sh <account|storage-row> <rate> <op_config>
#   ./run_scale.sh account     3 op_account
#   ./run_scale.sh storage-row 3 op_storage
#   ./run_scale.sh account     1 op_account     # 1x baseline (no expand)
set -euo pipefail

# ---- EDIT THESE ----
BASE_DB=/data/lite-base/output-directory/database   # the 'database' dir (holds account/ storage-row/ block/ ... CF subdirs)
WORK=/data/bench                          # scratch (needs free disk >= base + one expanded copy)
RESULTS=/data/bench/results
FULLNODE_JAR=/data/bench/FullNode.jar
TOOLKIT_JAR=/data/bench/Toolkit.jar
NODE_CONF=/data/bench/bench-node.conf     # mainnet config + isolated + http enabled (see RUNBOOK §1)
PORT=8190
KIT=$(cd "$(dirname "$0")" && pwd)        # this kit dir (holds op_*.json)
# --------------------

CF=$1; RATE=$2; OPCFG=$3
S="$WORK/scale-${CF}-${RATE}"
echo "[*] scale: cf=$CF rate=$RATE cfg=$OPCFG -> $S"
rm -rf "$S"; mkdir -p "$S" "$RESULTS"

# 1. assemble DB = copy of base, with $CF replaced by an expanded copy (rate 1 = base as-is)
#    DbExpand reads <--database>/<--target-db>, so --database is the 'database' dir itself.
cp -r "$BASE_DB" "$S/database"
if [ "$RATE" -gt 1 ]; then
  echo "[*] DbExpand $CF x$RATE ..."
  java -jar "$TOOLKIT_JAR" db expand \
    --database "$BASE_DB" \
    --target-database "$S/expanded" \
    --target-db "$CF" --target-type 1 --expend-rate "$RATE" | tee "$RESULTS/expand_${CF}_${RATE}.log"
  rm -rf "$S/database/$CF"
  cp -r "$S/expanded/$CF" "$S/database/$CF"
  rm -rf "$S/expanded"
fi
SIZE=$(du -sh "$S/database/$CF" | cut -f1)
echo "[*] $CF on-disk size: $SIZE"

# 2. start the ISOLATED bench node on the assembled DB
cp "$KIT/${OPCFG}.json" "$S/op.json"
( cd "$S" && nohup java -jar "$FULLNODE_JAR" -c "$NODE_CONF" -d "$S" >"$S/node.log" 2>&1 & echo $! > "$S/node.pid" )
echo "[*] waiting for HTTP :$PORT ..."
for i in $(seq 1 180); do
  code=$(curl -s -m 2 -o /dev/null -w "%{http_code}" "http://127.0.0.1:$PORT/wallet/getnowblock" 2>/dev/null || true)
  [ "$code" != "000" ] && [ -n "$code" ] && { echo "[*] up ($code)"; break; }
  kill -0 "$(cat "$S/node.pid")" 2>/dev/null || { echo "node died"; tail -30 "$S/node.log"; exit 1; }
  sleep 2
done

# 3. dump operand corpora against the inflated store
( cd "$S" && curl -s "http://127.0.0.1:$PORT/wallet/generateAddress" \
            && curl -s "http://127.0.0.1:$PORT/wallet/generateContract" \
            && { [ "$OPCFG" = "op_storage" ] && curl -s "http://127.0.0.1:$PORT/wallet/generateStorageKey" || true; } )

# 4. warm up, then measure
curl -s "http://127.0.0.1:$PORT/wallet/preOp?op_config=$OPCFG" >/dev/null
curl -s "http://127.0.0.1:$PORT/wallet/runOp?op_config=$OPCFG" >/dev/null

# 5. collect; annotate size; stop node; reclaim disk
OUT=$(ls "$S"/benchmark/*.txt | head -1)
{ echo "# cf=$CF rate=$RATE size=$SIZE host_ram=$(free -g 2>/dev/null | awk '/Mem:/{print $2"G"}')"; cat "$OUT"; } \
  > "$RESULTS/$(basename "${OUT%.txt}")_${CF}_${RATE}.txt"
kill "$(cat "$S/node.pid")" 2>/dev/null || true
sleep 5
rm -rf "$S"
echo "[*] done -> $RESULTS/$(basename "${OUT%.txt}")_${CF}_${RATE}.txt"
