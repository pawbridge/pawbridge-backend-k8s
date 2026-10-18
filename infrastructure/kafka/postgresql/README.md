# PostgreSQL Outbox connector contract

These are **opt-in, unapplied** Debezium 3.5 connector templates. Existing MySQL
connector files and deployment defaults remain unchanged. All five service schemas
live in database `pawbridge`; do not substitute schema names for `database.dbname`.

## Required runtime

- PostgreSQL `wal_level=logical`, at least five available replication slots and WAL
  sender connections, plus capacity for existing replication and maintenance.
- Debezium PostgreSQL plugin compatible with the worker, isolated from the MySQL
  plugin directory. The inspected local worker has Debezium **MySQL 3.5.2.Final**;
  that does not install the PostgreSQL connector.
- A dedicated LOGIN/REPLICATION role per connector, CONNECT on `pawbridge`, USAGE
  on its service schema, and SELECT on its Outbox table. Provision secrets outside
  Git; replace `<...>` values through the deployment's secret provider.
- A pre-created publication named as in each JSON containing **only that service's
  Outbox table**. DBA ownership is retained: `publication.autocreate.mode=disabled`.
  Include INSERT/UPDATE/DELETE so invalid updates fail the SMT instead of silently
  hiding an application contract violation. Cleanup DELETEs are discarded by SMT.
- Reserve both streaming and metadata/heartbeat DB connections for every connector in
  the global connection budget, in addition to Hikari, Python pools and admin headroom.
- Persistent Kafka Connect offsets and stable, unique connector name, `topic.prefix`
  and slot name across restart. `slot.drop.on.stop=false` preserves resumability.
- Monitor each slot's `active`, `restart_lsn`, `confirmed_flush_lsn`, `wal_status`
  and `pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)`. Set a measured WAL disk
  retention budget and alert before it is exhausted. A finite
  `max_slot_wal_keep_size` protects disk but can invalidate an overdue slot; it does
  **not** make that event loss safe. A heartbeat alone does not prove progress when
  publication tables are idle: verify confirmed LSN advancement under other DB writes.

## Wire contract

| Service | Table | Event ID | Event type column | Output topic | Serialized key |
| --- | --- | --- | --- | --- | --- |
| Animal | pawbridge_animal.outbox_events | event_id | event_type | row.topic | JSON string aggregate_id |
| User | pawbridge_user.outbox_events | event_id | event_type | row.topic | JSON string aggregate_id |
| Community | pawbridge_community.outbox_events | event_id | type | community.post.events | JSON string aggregate_id |
| Payment | pawbridge_payment.outbox | id | event_type | payment.events | JSON string aggregate_id |
| Store | pawbridge_store.outbox | id | event_type | store.${routedByValue}.events | JSON string aggregate_id |

The headers are `id` and `eventType`, payload is expanded JSON without a Connect
schema wrapper. The source record timestamp is retained: no `created_at` timestamp
override is configured, matching the live MySQL connectors inspected on 2026-09-20. Source topics and
slots change; application topics and aggregate keys stay stable. The predicate
limits EventRouter to the Outbox table, excluding connector heartbeat messages.
Animal has a text payload; the other schemas use JSON. Both produce the same flat
wire object. Store `aggregate_type=product-sku` routes to `store.product-sku.events`.

User/Community MySQL configs under `infrastructure/prod/connectors` differ from
actual current entity columns. They are not the specification for this transition.
Before deployment collect and compare the **running** Connect configuration; local
files alone do not establish what is live or inherited worker converter defaults.

## Cutover gates: no automatic application

`snapshot.mode=no_data` is a **drained migration** setting, not a catch-up mechanism.
Never register these templates against a database that already accepted new writes
before its replication slots existed. This would skip those earlier events.

1. Freeze writes, batch jobs and Outbox cleanup on the old system. Drain old CDC and
   all business consumers; prove source position and consumer lag, not just RUNNING.
   Account for DLT/compensation failures; preserve offsets and processed-event IDs.
2. Copy and reconcile business data. Preserve processed-event IDs and Outbox IDs
   for audit. Existing copied Outbox rows must already have been delivered; do not
   replay historical payment/favorite events by switching to `initial` snapshots.
3. With target writes still fenced, provision the approved roles/publications and
   fresh slots (`pgoutput`). Verify each slot's database/plugin and publication
   members. Register PG connectors, validate configuration and require all tasks
   RUNNING with offsets established before admitting target writes. Do not run old
   and new writers concurrently. Capture source drain and target start positions.
4. Test representative writes, rollback, connector restart and consumer failure.
   Match exact topic/key/headers/payload; show rolled-back rows never arrive.
5. Resume application writes and cleanup only after the readiness checks succeed.
   PostgreSQL search projections are synchronous; do not re-enable Community's
   ES-only consumer or Store's ES sink as part of the PostgreSQL search path.

Before target writes begin, rollback can restore old services/connectors and their
saved offsets. **After target writes begin, simply pointing back to MySQL loses
those writes.** Freeze again, reconcile/reverse-copy target changes, reconcile
consumer offsets and side effects, then explicitly approve the rollback. Keep old
DB/offsets until that rehearsal passes. Never delete a production slot or reset
Connect offsets merely to make a connector RUNNING.

## Local checks

```bash
python3 infrastructure/kafka/postgresql/tests/check_configs.py
```

`tests/OutboxContract.java` runs against the actual worker's Kafka and Debezium jars
(JDK 21 source-file mode). Pass this directory as its argument. It checks routing,
wire-key converters, event headers, JSON expansion, timestamp, delete filtering and
fatal updates. Example, inside a disposable worker image with a JDK:

```bash
java -Xmx192m -cp '/opt/kafka/libs/*:/opt/kafka/plugins/debezium-mysql/*' \
  /contract/tests/OutboxContract.java /contract
```

This SMT check **does not verify** PostgreSQL connector loading, WAL capture, Kafka
publication, durable offset restart or live consumers. Those require the isolated
PostgreSQL→Connect→Kafka rehearsal before deployment. The current templates are not
production-ready based only on these checks.

References: [PostgreSQL connector 3.5](https://debezium.io/documentation/reference/3.5/connectors/postgresql.html),
[Outbox Event Router 3.5](https://debezium.io/documentation/reference/3.5/transformations/outbox-event-router.html).


The full isolated rehearsal is opt-in and never pulls images. Download/verify the
PostgreSQL 3.5.2.Final plugin separately with approval, then pass its extracted
plugin directory and already cached image references:

```bash
python3 infrastructure/kafka/postgresql/tests/rehearse.py \
  --worker-image '<CACHED_STRIMZI_KAFKA_IMAGE>' \
  --postgres-image pgvector/pgvector:0.8.6-pg17 \
  --plugin-dir '<EXTRACTED_POSTGRES_PLUGIN_DIRECTORY>' \
  --evidence /tmp/pawbridge-cdc-result.json
```

When the candidate worker already packages the PostgreSQL connector, omit
`--plugin-dir`. The harness then uses `/opt/kafka/plugins` from that exact image
without a plugin mount, so the check covers packaging and plugin discovery as well
as the wire contract. Use a new `--evidence` path for every run; existing evidence
is refused before any Docker operation. A local image test is not an image push,
Strimzi CRD validation, Kubernetes Secret-provider test or production deployment.

The harness uses randomly named/labelled containers, an internal Docker network,
no host ports, and caps PostgreSQL/broker/Connect at 512/512/768 MiB. It only creates
synthetic Outbox data from the real migration DDL, uses schema-scoped non-superuser
replication accounts, and removes its containers, anonymous volumes and network on completion or failure.
Runtime properties and credentials are temporary test fixtures. Worker
`plugin.path` must name the **parent of the plugin directory** so dependency JARs
are loaded together; do not point it at a directory of independent JAR entries.
The harness uses `/tmp/kafka-logs`, avoiding non-root writes to `/opt/kafka/logs`.

Consumer failures now propagate in User/Store. After retry exhaustion an unresolved
recovery throws rather than discarding the record: the affected partition can stay
blocked until the cause is repaired. This is deliberate until a durable DLT/replay
contract exists; monitor lag/errors. These checks do not prove exactly-once Redis
ranking updates or implement the existing unfinished FAVORITE_REMOVED compensation.
Those remain separate business failure-recovery limitations, not CDC guarantees.
