# Isolated relational rollback rehearsal

This is an opt-in **test harness**, not a production copy/repair CLI. It takes no
DB address, credential, kubeconfig or dump. It creates random disposable containers
from already cached images, with an internal network, no mounts or published
ports, and 512 MiB / 1 CPU per DB (1 GiB total memory limit). It never pulls images.
All credentials and rows are synthetic. Owned containers, anonymous volumes and
the network are removed in `finally`; existing resources are not pruned.

## Full relational table coverage

```bash
python3 infrastructure/migration-tests/rehearse_full_rollback.py \
  --evidence /tmp/pawbridge-full-rollback-result.json
```

This opt-in test uses the same owned 512 MiB MySQL and PostgreSQL containers and
all real schema migrations. It adds explicit synthetic fixtures for the remaining
30 tables to the original 21-table fixtures. Source metadata must match exactly
51 covered tables; source columns with expression defaults are included, while
generated `pending_user_id`, MySQL Flyway history and PostgreSQL-only search
tracking fields are deliberately excluded from row copying. The three MySQL
Batch sequence-emulation tables are checked separately against PostgreSQL sequences.

Every table is populated and changed. Composite keys and duplicate rows in keyless
Batch parameters retain their multiplicity. The synthetic comparison computes
insert/update/delete differences in FK dependency order, checks mid-copy FK failure
rolls back the whole transaction, and requires a repeated copy to have no changes.
All source automatic-ID counters and three Batch counters preserve values allocated
by rolled-back PostgreSQL transactions. Administrative reverse commands must not
advance the source binlog. No FK or charset constraint is disabled.

This verifies row/type/key/counter compatibility on synthetic data, not production
volume, every possible collation conflict, live business flows, external effects,
Kafka drain, or ES/vector reverse restoration. The connected-consumer test below
provides separate CDC evidence. `production_rollback_ready` remains false. This
harness accepts no live connection or dump and is not a production repair CLI.

```bash
python3 infrastructure/migration-tests/rehearse_rollback.py \
  --postgres-image pgvector/pgvector:0.8.6-pg17 \
  --mysql-image mysql:8.4 \
  --evidence /tmp/pawbridge-rollback-result.json
```

Use a fresh evidence path. An existing file is refused before Docker is contacted.
Python's standard library and the existing Docker CLI are sufficient. The fixture
file states exactly which tables and business updates are exercised. It is not a
full 51-table import or a throughput benchmark.

## Contract exercised

- Actual five-service MySQL/PostgreSQL V1 relational migration DDL, plus User MySQL
  V2 shelter applications. No rewritten toy table definitions. Later search,
  gallery, vector and Spring Batch migrations are outside this test.
- 21 selected tables, including real FK children, five Outbox tables, and the
  existing Animal/User/Community `processed_events` tables. Store/Payment do not
  gain invented deduplication tables. Every selected column is compared after
  JSON and microsecond timestamp normalization; nulls and escaped Korean text
  are included. MySQL's generated `pending_user_id` is recomputed, not copied.
- Five least-privilege synthetic runtime accounts per DB. Locking account/login
  prevents new sessions but leaves an existing open write alive. Terminating that
  exact session rolls back its uncommitted write. MySQL is also made read-only.
  This verifies the mechanism, not discovery of all real production writers.
- Copy MySQL → PG, then real PG inserts, updates and deletes. Preserve append-only
  Outbox history and processed-event IDs. No gateway/HTTP/provider call is made.
- Copy PG changes → MySQL in one cross-schema DML transaction, with FK and unique
  checks enabled. A real FK error mid-transaction must leave all source rows
  unchanged. Retry uses the same reconciliation method with a fresh source
  snapshot, and may not duplicate rows/events.
- Counter high-watermarks survive explicit ID import and rolled-back sequence
  allocation: source next ID 500 despite MAX(id)=200; PG allocates 500, consumes
  501 without commit; resumed MySQL allocates at least 502. MySQL counter DDL is a
  separate fenced phase because ALTER TABLE implicitly commits. The counter is
  never lowered below MySQL's own next ID after failed import attempts.
- In this synthetic, already-drained scenario, privileged copy/counter commands
  use session `sql_log_bin=0`, and the old binlog position must remain unchanged.
  This prevents this administrative data copy from becoming old-source CDC input.
  It is **not** evidence of Kafka delivery/drain, offset continuity, or safe use
  with production replicas/PITR. Never apply this to a live topology on the
  strength of this fixture test alone.

## Failure that still blocks an unrestricted production rollback

PG can accept supplementary Unicode such as a dog emoji while existing MySQL
`utf8mb3` text columns cannot. The negative fixture stores such text in PG and
requires MySQL error 1366, no partial reverse commit, retained PG data and closed
writer fences. Only that synthetic negative fixture is then restored to its
previous value to exercise the separate compatible-data success path. The harness
never proposes silently stripping real user text.

Before opening PG writes in production, resolve the rollback compatibility policy:
keep the old input character range during the rollback window, or separately
review and upgrade the rollback target's charset. Full Unicode, collation/unique
constraints and all-table dependencies need coverage beyond these fixtures.
A passing harness reports `production_rollback_ready: false` deliberately.

## Remaining release gates

A real cutover still needs all producers/jobs/cleanup stopped, existing transactions
drained, old CDC and all consumers caught up, failed/compensation events accounted
for, and durable source positions captured. Establish target slots before allowing
new writes. Before reverse copy, drain PG CDC/consumers and reconcile external
payment/Redis effects. ES search projections and vector/gallery data require their
own reverse recovery strategy. Do not reopen writes or delete old DBs, offsets or
PVCs based only on this relational test.

Evidence contains checks, normalized table hashes and row counts, DDL hashes,
known blockers and cleanup status; no live data or secret is used.

## Charset-compatible candidate

```bash
python3 infrastructure/migration-tests/rehearse_charset.py \
  --evidence /tmp/pawbridge-charset-result.json
```

This separate rehearsal applies **all PostgreSQL migrations**, including the
rollback charset guards. It provisions vector/pg_trgm only inside the disposable
DB, imports synthetic rows before probing real business writes, and uses the same
512 MiB per DB resource limits and owned-resource cleanup. The earlier
`rehearse_rollback.py` intentionally retains the unguarded V1 baseline to reproduce
why the guard is necessary; it is not verification of the guarded candidate.

`rollback-charset-columns.json` and `rollback-database-charsets.json` contain only
column/schema metadata read from the running MySQL instance on 2026-09-21. Fresh
source fixtures must use those verified DB defaults: DDL without a table charset
inherits the database default, not necessarily the new MySQL image's default.
The ordinary MySQL migrations are not rewritten. Flyway history is excluded from
reverse copying. Batch sequence-emulation tables become PG sequences, so their
text markers are not input data. Existing Batch text fields are included in guards.

The candidate has 42 named CHECK constraints over 159 source-limited columns
(131 utf8mb3, 28 ASCII). JSON and the 19 inventory columns already using utf8mb4
remain unrestricted by this policy. A CHECK's NULL neutrality does not remove the
real column's NOT NULL constraint. Each installed charset CHECK is tested at its
allowed upper boundary and both unsupported boundaries, isolated from business
checks in temporary LIKE tables; real business updates exercise full constraints.
The test reconstructs CHECK definitions through hex-encoded bytes because direct
psql text rendering can omit a noncharacter such as U+10FFFF. It must never use a
visually reconstructed range as proof of the installed expression.

Guards are validated on application/import and apply to API, batch, direct SQL and
consumer writes. They are PostgreSQL-only migration candidates, not feature flags
or a production schema change. ASCII/utf8mb3 restrictions are temporary rollback
compatibility, not an adoption-wide ban on emoji. A later reviewed migration must
drop the `ck_rollback_charset_*` constraints after ending the rollback window or
verifying the old target's expanded charset; existing Flyway versions are immutable
once applied. Review the payment identifier validation against the provider's
contract when ending that window.

Animal/User/Community/Store map only their own schema's structured PostgreSQL
SQLSTATE 23514 + `ck_rollback_charset_` identity to a safe 400 message, including
transaction and JDBC batch wrapping. Other DB failures retain their prior handling.
User signup/nickname logic must not reclassify this as a nickname collision.
Payment validates request identifiers before its service/provider call. A failure
while storing the provider's response is not reclassified as bad client input;
its existing server-error/compensation path remains in effect. Separate MockMvc
and service unit tests cover these response boundaries; the database rehearsal is
not a full authenticated HTTP/payment-provider scenario.

This policy addresses the character repertoire only. Length limits, collations and
unique-key equivalence, external uploads/payments, consumer drain, all-table data,
search reconstruction and CDC offsets remain separate cutover checks. No data is
silently replaced, clipped or discarded to make rollback succeed.

## Connected CDC and application-consumer rehearsal

```bash
python3 infrastructure/migration-tests/rehearse_connected.py \
  --worker-image <ALREADY_BUILT_POSTGRESQL_CONNECT_IMAGE> \
  --java-home <EXISTING_JAVA_17_HOME> \
  --evidence /tmp/pawbridge-connected-result.json
```

This opt-in successor keeps the same **synthetic 21-table fixture**, applies all PG
migrations, then runs the actual Animal, User and Store Spring Kafka listeners
against the copied database and a real disposable Kafka/Connect worker. The shared
`CutoverProbe` validates a per-run DB marker before Spring starts. The Gradle init
script exposes only a separate tagged `cutoverConsumerTest` task. Ordinary tests
do not start this environment; no arbitrary DB URL, production credential or
external dump can be supplied to the orchestrator.

Unlike the container-only tests above, this flow uses a dedicated Docker bridge
and **127.0.0.1-only** ephemeral PG/Kafka ports for host test JVMs. It does not join
existing networks. Other external-service URLs are disabled test endpoints or
mocks. MySQL is stopped while PG512MiB, Kafka512MiB and Connect768MiB run (1.75GiB
combined container limit); service tests run sequentially with384MiB JVM heaps.
Gradle itself has a separate384MiB heap, so1.75GiB is not a whole-host RAM limit.
Nothing is pulled, installed, published or deployed by this harness.

The scenarios cover favorite increments, duplicate delivery, failed compensation
storage, compensation application, failed DB deletes, payment-driven order status,
consumer offset retention during failure and advancement after repair. Copied
historical Outbox rows predate the target slots. Source accounts are fenced before
target processing. Kafka group offsets are captured after actual listeners drain,
retained across broker restart, and the resulting business/Outbox/processed-event
rows are reverse copied with FK checks and a deliberately failed transaction.
Repeated reverse reconciliation must produce identical values. Sequence high
watermarks and the fenced MySQL binlog boundary are checked separately.

This is not a full live-source CDC transition or a51-table new-write rollback.
Community's ES consumer is intentionally absent in the PG profile; its search
projection is maintained in PG. Store ranking Redis is mocked, and neither the
payment provider nor R2 is called. Passing DB/offset tests therefore does not prove
external-effect atomicity or exactly-once delivery. Live fences, old connector
positions, external effects and search/vector reconstruction still need the
operational transition procedure. Evidence deliberately retains
`production_rollback_ready: false` until those boundaries are separately closed.

All owned containers, volumes and the network are removed even on failure. Logs
and JSON evidence remain at the requested evidence path; no full application
image or live data is retained. A MySQL restart resets the global `read_only`
setting and rotates the binlog, so the test re-closes that fence and captures the
new binlog position before administrative reverse copying.
