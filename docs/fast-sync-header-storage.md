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
TVM uses that validated in-memory genesis for `CHAINID` after header zero expires;
no extra genesis header is persisted outside the rolling window.
Cycle end timestamps are retained with the statistics snapshots, so `/list_cycle`
can still provide its 31-day date mapping after older block headers expire.

## Account usage diagnostics

Every `AccountStore.put()` checks usage against the resource totals used by the
node at the time of the write:

| Fields | Upper bound |
| --- | --- |
| `net_usage`, `free_net_usage`, both TRC10 free-asset usage maps | `TotalNetLimit` |
| `account_resource.energy_usage` | `TotalEnergyCurrentLimit` |

The bandwidth threshold is the unified network `TotalNetLimit` returned by account-resource
queries, including for free bandwidth fields; it is not the separate public free-bandwidth pool.

A negative value is always abnormal. A nonnegative value is abnormal only when
it strictly exceeds the corresponding current total; equality is allowed.
Comparisons use the stored usage directly, without multiplying or rescaling it.
Legacy `storage_usage` is checked for negative values only, since it does not use
these active energy/bandwidth totals. An invalid negative global total is not used
as an upper bound.

An abnormal write emits a `WARN` from `account-usage` with the marker
`Abnormal account usage write` in `logs/tron.log` under the default configuration:

```sh
rg 'Abnormal account usage write' logs/tron.log
```

Events include the account key in hex, transaction ID and type, block number and
hash, phase (`apply`, `pack`, or `pending`), and each abnormal field's previous and
new values. `reason=negative` identifies negative values; `reason=exceeds_total`
includes the global total used for the comparison. `introduced` identifies entry
into that abnormal category. Writes outside transactions report `txId=none` and
`phase=outside-transaction`.

These events record write attempts, including writes later revoked by a failed
transaction or block. Monitoring does not reject or correct values. Only anomalous
writes load the previous account. Current resource totals are read on each write,
so updates to network parameters are reflected immediately. Transaction context
covers bandwidth and fee charging and is cleared on both success and failure.

### Slack transaction alerts

Set `ACCOUNT_USAGE_SLACK_WEBHOOK_URL` in the node process environment to enable
Slack delivery. Leave it unset for local diagnostics only. Store the actual URL
in the service environment or a private environment file, outside Git and logs.
Initialization configures notifications only; it does not scan accounts or write
an account-scan completion marker. Invalid webhook configuration closes the
application context and exits with `ACCOUNT_USAGE_INIT(1)`.

Each transaction execution queues at most one Slack notification containing its
ID, type, block, affected accounts and changed abnormal usage. An unchanged
inherited abnormal value produces local diagnostics without another Slack alert.
Changes from one abnormal value to another do alert. Notifications describe write
attempts and may refer to transactions or blocks later revoked.

Delivery uses a named worker with a bounded 256-message queue and three-second
connection/read timeouts. It sends at most one request per second and retries
HTTP 429 (honoring `Retry-After`), 5xx and I/O failures up to three attempts. The
cooldown also applies to the next queued message after a final 429. Other HTTP
errors and redirects are not retried. The request shape and rate-limit handling
follow the [Slack webhook documentation](https://docs.slack.dev/messaging/sending-messages-using-incoming-webhooks/)
and [rate-limit documentation](https://docs.slack.dev/apis/web-api/rate-limits/).
Queue overflow and exhausted retries are logged. Delivery is best-effort; pending
messages are not persisted across exits, and retries after ambiguous I/O failures
can produce duplicates. Network delivery does not block transaction execution.
Messages contain up to 20 accounts, bounded field details and roughly 12,000
characters; full details remain in node logs.

A local Postman stand-in tests the real Java client's JSON POST and 429 retry:

```sh
postman simulation run postman/simulations/account-usage-alerts/account-usage-alerts.sim.yaml \
  --port 4018 --no-history
```

In a second shell (use a fresh simulation for each run):

```sh
ACCOUNT_USAGE_TEST_WEBHOOK_BASE_URL=http://127.0.0.1:4018/slack ./gradlew :framework:test \
  --tests org.tron.core.service.SlackUsageAlertTest.postmanSimulationReceivesRealClientAndRateLimitRetry
```

The simulation does not establish production webhook authorization or channel
routing.

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

The conversion also derives cycle end timestamps from the original headers and
checkpoints before removing them. Maintenance blocks record these timestamps in
`tracker` thereafter, with the same 160-cycle retention as the other statistics.

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

If a previous header-only conversion has already replaced the stores but its
backup is still available, stop the node and backfill the small cycle timestamp
metadata before deleting the backup:

```sh
java -Xmx2g -jar Toolkit.jar db headers-only \
  --database /data/fastnode/output-directory/database --execute --backfill-cycle-times
```

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
