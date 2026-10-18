#!/usr/bin/env python3
"""Opt-in, disposable PG -> Debezium -> Kafka rehearsal. Never contacts live services.
Requires already downloaded images/plugin; does not pull, build, or install anything.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import tempfile
import time
import uuid

BASE = Path(__file__).resolve().parents[1]
ROOT = BASE.parents[2]
SERVICES = ('animal', 'user', 'community', 'payment', 'store')


def command(*args, stdin=None, timeout=90):
    result = subprocess.run(args, input=stdin, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError('Command failed: ' + ' '.join(args[:4]) + '\n' + result.stderr[-3000:])
    return result.stdout


def wait_for(description, check, seconds=90):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            result = check()
            if result:
                return result
        except (OSError, ValueError, RuntimeError):
            pass
        time.sleep(1)
    raise RuntimeError('Timed out: ' + description)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--worker-image', required=True)
    parser.add_argument('--postgres-image', required=True)
    parser.add_argument('--plugin-dir', type=Path,
                        help='Optional external plugin; omit to verify plugins packaged in the worker image')
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    if args.evidence.exists():
        parser.error('Refusing to overwrite existing rehearsal evidence')
    plugin = args.plugin_dir.resolve() if args.plugin_dir else None
    if plugin and not list(plugin.glob('debezium-connector-postgres-3.5.2.Final.jar')):
        raise SystemExit('Expected an already verified Debezium PostgreSQL 3.5.2.Final plugin directory')
    for image in (args.worker_image, args.postgres_image):
        command('docker', 'image', 'inspect', image)
    suffix = uuid.uuid4().hex[:10]
    network = 'pawbridge-cdc-test-' + suffix
    names = {key: network + '-' + key for key in ('pg', 'broker', 'connect')}
    containers = []
    evidence = {'scope': 'isolated CDC rehearsal', 'services': list(SERVICES), 'checks': [], 'cleanup': False}
    evidence['plugin_source'] = 'external-test-mount' if plugin else 'worker-image'
    def sql(statement):
        return command('docker', 'exec', '-i', names['pg'], 'psql', '-X', '-U', 'postgres', '-d', 'pawbridge',
                       '-v', 'ON_ERROR_STOP=1', '-At', stdin=statement)
    try:
        command('docker', 'network', 'create', '--internal', '--label', 'pawbridge.test=cdc-rehearsal', network)
        with tempfile.TemporaryDirectory(prefix='pawbridge-cdc-runtime-') as directory:
            runtime = Path(directory)
            runtime.chmod(0o755)
            (runtime / 'broker.properties').write_text('''process.roles=broker,controller
node.id=1
controller.quorum.voters=1@broker:9093
controller.listener.names=CONTROLLER
listeners=BROKER://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
advertised.listeners=BROKER://broker:9092
listener.security.protocol.map=BROKER:PLAINTEXT,CONTROLLER:PLAINTEXT
inter.broker.listener.name=BROKER
log.dirs=/tmp/kraft-logs
num.partitions=1
offsets.topic.replication.factor=1
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
group.initial.rebalance.delay.ms=0
''')
            (runtime / 'connect.properties').write_text('''bootstrap.servers=broker:9092
group.id=pg-cdc-rehearsal
config.storage.topic=cdc-test-configs
offset.storage.topic=cdc-test-offsets
status.storage.topic=cdc-test-status
config.storage.replication.factor=1
offset.storage.replication.factor=1
status.storage.replication.factor=1
offset.flush.interval.ms=1000
key.converter=org.apache.kafka.connect.storage.StringConverter
value.converter=org.apache.kafka.connect.json.JsonConverter
value.converter.schemas.enable=false
plugin.path=/test-plugins
listeners=http://0.0.0.0:8083
rest.advertised.host.name=connect
''')
            if plugin is None:
                properties = runtime / 'connect.properties'
                properties.write_text(properties.read_text().replace('plugin.path=/test-plugins',
                                                                     'plugin.path=/opt/kafka/plugins'))
            (runtime / 'broker.sh').write_text('''#!/bin/bash
set -e
/opt/kafka/bin/kafka-storage.sh format -t 4L6g3nShT-eMCtK--X86sw -c /work/broker.properties
exec /opt/kafka/bin/kafka-server-start.sh /work/broker.properties
''')
            (runtime / 'connect.sh').write_text('''#!/bin/bash
exec /opt/kafka/bin/connect-distributed.sh /work/connect.properties
''')
            # Test-only password; network is isolated and DB has no published host port.
            command('docker', 'run', '-d', '--pull=never', '--name', names['pg'], '--label', 'pawbridge.test=cdc-rehearsal',
                    '--network', network, '--network-alias', 'pg', '--memory', '512m', '--memory-swap', '512m', '--cpus', '1',
                    '-e', 'POSTGRES_DB=pawbridge', '-e', 'POSTGRES_PASSWORD=local_cdc_test_only', args.postgres_image,
                    '-c', 'wal_level=logical', '-c', 'max_replication_slots=8', '-c', 'max_wal_senders=8',
                    '-c', 'max_slot_wal_keep_size=128MB')
            containers.append(names['pg'])
            wait_for('PostgreSQL readiness', lambda: sql('SELECT 1').strip() == '1')
            for service in SERVICES:
                schema = 'pawbridge_' + service
                table = 'outbox' if service in ('payment', 'store') else 'outbox_events'
                ddl = next((ROOT / (service + '-service/src/migration/resources/db/postgresql')).glob('V1*')).read_text()
                outbox_ddl = re.search(r'CREATE TABLE ' + table + r' \(.*?\n\);', ddl, re.S).group()
                sql(f'CREATE SCHEMA {schema}; SET search_path={schema};\n' + outbox_ddl +
                    f"\nCREATE PUBLICATION {schema}_outbox FOR TABLE {schema}.{table};" +
                    f"CREATE ROLE cdc_{service} LOGIN REPLICATION PASSWORD 'local_cdc_test_only';" +
                    f"GRANT CONNECT ON DATABASE pawbridge TO cdc_{service}; GRANT USAGE ON SCHEMA {schema} TO cdc_{service};" +
                    f"GRANT SELECT ON {schema}.{table} TO cdc_{service};")
            # Historical rows predate slots and must not be replayed during a drained cutover.
            def insert(service, phase, commit=True):
                schema = 'pawbridge_' + service
                table = 'outbox' if service in ('payment', 'store') else 'outbox_events'
                payload = json.dumps({'contractService': service, 'phase': phase, 'eventId': service + '-' + phase,
                                      'animalId': 42, 'title': '보호 중'}, ensure_ascii=False)
                columns = ['aggregate_id', 'aggregate_type', 'created_at', 'payload']
                values = ["'42'", "'product-sku'", "TIMESTAMP '2026-09-20 12:00:00'", "'" + payload + "'"]
                if service not in ('payment', 'store'):
                    columns.append('event_id'); values.append("'" + service + '-' + phase + "'")
                columns.append('type' if service == 'community' else 'event_type'); values.append("'CONTRACT_TEST'")
                if service in ('animal', 'user'):
                    columns.append('topic'); values.append("'animal.events'" if service == 'animal' else "'user.favorite.events'")
                sql('BEGIN; INSERT INTO ' + schema + '.' + table + '(' + ','.join(columns) + ') VALUES (' +
                    ','.join(values) + '); ' + ('COMMIT;' if commit else 'ROLLBACK;'))
            for service in SERVICES:
                insert(service, 'historical')
            for key, memory, heap in [('broker', '512m', '256m'), ('connect', '768m', '384m')]:
                arguments = ['docker', 'run', '-d', '--pull=never', '--name', names[key], '--label', 'pawbridge.test=cdc-rehearsal',
                             '--network', network, '--network-alias', key, '--memory', memory, '--memory-swap', memory,
                             '--cpus', '1', '-e', 'KAFKA_HEAP_OPTS=-Xms64m -Xmx' + heap, '-e', 'LOG_DIR=/tmp/kafka-logs',
                             '-v', str(runtime) + ':/work:ro', '--entrypoint', '/bin/bash']
                if key == 'connect' and plugin is not None:
                    arguments += ['-v', str(plugin) + ':/test-plugins/postgresql:ro']
                command(*arguments, args.worker_image, '/work/' + key + '.sh')
                containers.append(names[key])
            def api(path, value=None, method=None):
                arguments = ['docker', 'exec', '-i', names['connect'], 'curl', '--fail', '--silent', '--show-error',
                             '--max-time', '10', '-H', 'Content-Type: application/json']
                if method:
                    arguments += ['-X', method]
                if value is not None:
                    arguments += ['--data-binary', '@-']
                body = command(*arguments, 'http://127.0.0.1:8083' + path,
                               stdin=None if value is None else json.dumps(value), timeout=15)
                return json.loads(body) if body else None
            wait_for('Connect plugin discovery', lambda: any(item['class'] == 'io.debezium.connector.postgresql.PostgresConnector'
                       for item in api('/connector-plugins')), seconds=120)
            evidence['checks'].append('PostgreSQL plugin discovered')
            connectors = []
            for service in SERVICES:
                template = json.loads((BASE / (service + '-outbox-connector.json')).read_text())
                config = template['config']
                config.update({'database.hostname': 'pg', 'database.user': 'cdc_' + service, 'database.password': 'local_cdc_test_only'})
                validation = api('/connector-plugins/io.debezium.connector.postgresql.PostgresConnector/config/validate', dict(config, name=template['name']), 'PUT')
                if validation['error_count']:
                    raise AssertionError({item['definition']['name']: item['value']['errors'] for item in validation['configs'] if item['value']['errors']})
                api('/connectors', template, 'POST')
                connectors.append(template['name'])
            def running():
                for name in connectors:
                    status = api('/connectors/' + name + '/status')
                    if status['connector']['state'] != 'RUNNING' or not status['tasks'] or any(task['state'] != 'RUNNING' for task in status['tasks']):
                        return False
                return sql('SELECT count(*) FROM pg_replication_slots WHERE active').strip() == '5'
            wait_for('all connector tasks and five slots active', running, seconds=120)
            evidence['checks'].append('all five configs validate and tasks stream')
            for service in SERVICES:
                insert(service, 'committed')
                insert(service, 'rolled-back', commit=False)
            pattern = 'animal.events|user.favorite.events|community.post.events|payment.events|store.product-sku.events'
            def consume():
                # timeout exits nonzero once quiet; records already received remain valid.
                result = subprocess.run(['docker', 'exec', '-e', 'KAFKA_HEAP_OPTS=-Xms32m -Xmx96m', names['connect'],
                    '/opt/kafka/bin/kafka-console-consumer.sh', '--bootstrap-server', 'broker:9092', '--include', pattern,
                    '--from-beginning', '--timeout-ms', '6000', '--property', 'print.key=true', '--property', 'print.headers=true',
                    '--property', 'print.timestamp=true'], capture_output=True, text=True, timeout=35)
                rows = []
                for line in result.stdout.splitlines():
                    pieces = line.split('\t')
                    if pieces and pieces[-1].startswith('{'):
                        rows.append({'wire': pieces[:-1], 'payload': json.loads(pieces[-1])})
                return rows
            first = wait_for('five committed events', lambda: (rows if len(rows) >= 5 else None) if (rows := consume()) is not None else None)
            assert len(first) == 5, first
            assert {row['payload']['contractService'] for row in first} == set(SERVICES)
            assert all(row['payload']['phase'] == 'committed' for row in first), first
            for row in first:
                service = row['payload']['contractService']
                wire = row['wire']
                assert wire[-1] == '"42"', row
                assert 'eventType:CONTRACT_TEST' in wire[-2], row
                assert 'id:' in wire[-2], row
                assert row['payload']['title'] == '보호 중', row
            evidence['checks'] += ['five committed events delivered with wire key/header/JSON contract',
                                   'historical snapshot rows not replayed', 'five rolled-back rows not delivered']
            time.sleep(3)  # offset flush interval is 1 second
            before = sql("SELECT slot_name, confirmed_flush_lsn::text FROM pg_replication_slots ORDER BY slot_name")
            command('docker', 'stop', '--time', '15', names['connect'])
            for service in SERVICES:
                insert(service, 'while-stopped')
            command('docker', 'start', names['connect'])
            wait_for('connectors resume after restart', running, seconds=120)
            second = wait_for('ten events after restart', lambda: rows if len(rows := consume()) >= 10 else None)
            logical = {(row['payload']['contractService'], row['payload']['phase']) for row in second}
            assert logical == {(s, phase) for s in SERVICES for phase in ('committed', 'while-stopped')}, second
            evidence['checks'].append('all five connectors resume stopped-period writes from preserved slots/offsets')
            evidence['record_count_after_restart'] = len(second)
            evidence['unique_event_count_after_restart'] = len(logical)
            evidence['duplicates_after_graceful_restart'] = len(second) - len(logical)
            evidence['slots_before_restart'] = before.splitlines()
            evidence['slots_after_restart'] = sql("SELECT slot_name, confirmed_flush_lsn::text FROM pg_replication_slots ORDER BY slot_name").splitlines()
            evidence['result'] = 'PASS'
    except Exception as failure:
        evidence['result'] = 'FAIL'
        evidence['error'] = str(failure)
        # This isolated environment contains only synthetic credentials/data.
        for key, name in names.items():
            if name in containers:
                args.evidence.parent.mkdir(parents=True, exist_ok=True)
                log = subprocess.run(['docker', 'logs', '--tail', '160', name], capture_output=True, text=True)
                args.evidence.with_suffix('.' + key + '.log').write_text(log.stdout + log.stderr)
        raise
    finally:
        for name in reversed(containers):
            command('docker', 'rm', '-f', '-v', name)
        command('docker', 'network', 'rm', network)
        evidence['cleanup'] = True
        args.evidence.parent.mkdir(parents=True, exist_ok=True)
        args.evidence.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n')
        print(json.dumps(evidence, ensure_ascii=False), flush=True)

if __name__ == '__main__':
    main()
