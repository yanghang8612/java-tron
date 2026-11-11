# Header-only storage for fast-sync-stats

This branch persists the most recent 65,536 block headers and their height indexes.
It does not persist transaction bodies, transaction indexes, or receipts. Account,
contract, resource, and statistics state continue to advance normally. Complete
blocks needed to execute transactions or switch an unfinalized fork remain in the
bounded in-memory Khaos database; they are not written to the block database.

At height `H >= 65,535`, the retained header interval is `[H - 65,535, H]`.
Each block's header insertion and expired-header deletion share the normal
revoking session. The genesis identity is stored as `HEADER_ONLY_GENESIS_ID` in
`tracker`, so startup does not require keeping an extra genesis header forever.
This metadata must match the genesis block constructed from the node configuration.

## Converting existing data

Build `FullNode.jar` and `Toolkit.jar` from this branch for the target architecture.
Stop the statistics node with `SIGTERM` and wait for the Java process to exit.
Do not use a start script that immediately restarts or kills the process with
`SIGKILL` while doing this conversion.

```sh
java -Xmx2g -jar Toolkit.jar db headers-only \
  --database /data/fastnode/output-directory/database --execute
```

The command acquires the source database locks and refuses incomplete migrations
or missing recent headers. It resolves the persisted head and recent headers
through the active v1/v2 checkpoints, validates header IDs and parent links, and
builds fresh databases. It replaces these stores only:

- `block`, `block-index`
- `trans`, `transactionRetStore`, `transactionHistoryStore`
- `trans-cache`, `recent-transaction`

Transaction and historical block entries are removed from **all** v1/v2
checkpoints, while checkpoint entries for account, contract, dynamic properties,
and other state remain available for normal startup recovery. The new databases
are checked again before the migration marker is removed. A missing recent header
or inconsistent head aborts before replacing the source stores.

Original stores, `tracker`, and checkpoint files are retained under
`database/.header-only-backup` for the initial startup check. A failure during the
replacement leaves `database/header-only-migration.pending`; `FullNode` refuses
to start while this file exists. Inspect the failure before moving any files.

Initially start the new binary with `--p2p-disable true`. Check that it opens the
database, recovers the expected persisted head, serves recent headers, and returns
empty transaction/receipt queries. Then restart with the normal P2P settings and
verify that the head advances. Delete `.header-only-backup` only after those checks
to physically reclaim the old transaction data; renaming the stores alone does
not release their disk space.

## Restoring an interrupted conversion

Keep the node stopped. Before allowing the converted node to import new blocks,
restore each store found in `.header-only-backup` to its original name, moving
any replacement directory aside first. Restore the saved `tracker`, `tmp`, and
`checkpoint` directories as well. Remove the migration marker last, then use the
previous binary. If new blocks have already advanced the business state, restoring
only the old header directories is insufficient: they no longer cover that new
head. Preserve the current state and diagnose that case separately.

The migration does not remove other node data, MySQL tables, go-tron databases,
or unrelated maintenance directories.
