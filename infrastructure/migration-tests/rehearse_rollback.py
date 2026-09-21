#!/usr/bin/env python3
"""Disposable relational rollback test, not a production migration tool.

No connection options, mounts, host ports, downloads or production credentials.
Only random containers created by this process are used. All rows are synthetic.
Kafka drain/offsets, external effects, derived search/vector data are NOT tested.
"""
import argparse
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import uuid

BASE = Path(__file__).resolve().parent
ROOT = BASE.parents[1]
FIXTURES = json.loads((BASE / 'rollback-fixtures.json').read_text())
PASSWORD = 'isolated_rollback_test_only'


def command(*args, stdin=None, check=True, timeout=60):
    result = subprocess.run(args, input=stdin, text=True, capture_output=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(' '.join(args[:4]) + ': ' + result.stderr[-2000:])
    return result


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def wait_for(check, description, seconds=60):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            if check():
                return
        except RuntimeError:
            pass
        time.sleep(.25)
    raise RuntimeError('Timed out: ' + description)


def identifier(value):
    require(re.fullmatch('[a-z_][a-z_0-9]*', value), 'Invalid test identifier')
    return value


def literal(value):
    return 'NULL' if value is None else "'" + str(value).replace("'", "''") + "'"


def table_name(service, table):
    return identifier('pawbridge_' + service) + '.' + identifier(table)


def insert(table, row):
    return 'INSERT INTO ' + table + ' (' + ','.join(map(identifier, row)) + ') VALUES (' + ','.join(map(literal, row.values())) + ');'


def changed_row(row, number):
    return {key: str(value).replace('100', str(number)) if isinstance(value, str)
            else number if value == 100 else value for key, value in row.items()}


class Rehearsal:
    def __init__(self, args):
        self.args = args
        self.prefix = 'pawbridge-rollback-test-' + uuid.uuid4().hex[:10]
        self.names = {db: self.prefix + '-' + db for db in ('pg', 'mysql')}
        self.created = []
        self.network = False
        self.processes = []
        self.columns = {}
        self.evidence = {'scope': 'isolated selected-table relational round trip',
                         'checks': [], 'services': list(FIXTURES), 'tables': {},
                         'not_tested': ['Kafka offsets and consumer drain', 'external effects',
                                       'all-table/live-data migration', 'ES/search/vector reverse restoration',
                                       'production fences and role topology'],
                         'cleanup': False}

    def checked(self, label):
        self.evidence['checks'].append(label)
        print(label, flush=True)

    def sql_args(self, db, user=None):
        if db == 'pg':
            return ['docker', 'exec', '-i', self.names[db], 'psql', '-XqAt', '-v', 'ON_ERROR_STOP=1',
                    '-U', user or 'postgres', '-d', 'pawbridge']
        return ['docker', 'exec', '-i', '-e', 'MYSQL_PWD=' + PASSWORD, self.names[db],
                'mysql', '--protocol=socket', '--default-character-set=utf8mb4', '--batch', '--raw',
                '--skip-column-names', '-u', user or 'root']

    def sql(self, db, query, user=None, check=True):
        prefix = "SET SESSION sql_mode='STRICT_ALL_TABLES,NO_BACKSLASH_ESCAPES,NO_ENGINE_SUBSTITUTION';\n" if db == 'mysql' else ''
        return command(*self.sql_args(db, user), stdin=prefix + query, check=check)

    def network_options(self):
        return ["--internal"]

    def database_options(self, db):
        return [], []

    def setup(self):
        for image in (self.args.postgres_image, self.args.mysql_image):
            command('docker', 'image', 'inspect', image)
        command('docker', 'network', 'create', *self.network_options(), '--label', 'pawbridge.test=rollback', self.prefix)
        self.network = True
        for db, image, env, flags in (
            ('pg', self.args.postgres_image, ['POSTGRES_PASSWORD=' + PASSWORD, 'POSTGRES_DB=pawbridge'],
             ['-c', 'shared_buffers=128MB']),
            ('mysql', self.args.mysql_image, ['MYSQL_ROOT_PASSWORD=' + PASSWORD],
             ['--innodb-buffer-pool-size=128M', '--performance-schema=OFF', '--max-connections=20',
              '--server-id=71', '--log-bin=mysql-bin', '--binlog-format=ROW'])):
            args = ['docker', 'run', '-d', '--pull=never', '--network', self.prefix,
                    '--name', self.names[db], '--label', 'pawbridge.test=rollback',
                    '--memory=512m', '--memory-swap=512m', '--cpus=1', '--pids-limit=128']
            run_options, database_flags = self.database_options(db)
            args.extend(run_options)
            flags += database_flags
            for value in env:
                args.extend(['-e', value])
            # Record exact owned name before creation, so even a partial run is cleaned up.
            self.created.append(self.names[db])
            command(*args, image, *flags)
            health = (['pg_isready', '-h', '127.0.0.1', '-U', 'postgres'] if db == 'pg'
                      else ['mysqladmin', '--protocol=tcp', '-h', '127.0.0.1', '-u', 'root', 'ping'])
            wait_for(lambda: command('docker', 'exec', '-e', 'MYSQL_PWD=' + PASSWORD,
                                     self.names[db], *health, check=False).returncode == 0
                     and self.sql(db, 'SELECT 1').stdout.strip() == '1', db + ' readiness')
        ddl = {'mysql': [], 'pg': []}
        for service in FIXTURES:
            schema = 'pawbridge_' + service
            directory = ROOT / (service + '-service/src/migration/resources/db')
            source = next((directory / 'migration').glob('V1__*.sql'))
            target = next((directory / 'postgresql').glob('V1__*.sql'))
            self.evidence.setdefault('ddl_sha256', {})[service] = {
                str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
                for path in (source, target)}
            mysql_ddl = source.read_text()
            if service == 'user':
                extra = directory / 'migration/V2__shelter_applications.sql'
                mysql_ddl += '\n' + extra.read_text()
                self.evidence['ddl_sha256'][service][str(extra.relative_to(ROOT))] = hashlib.sha256(extra.read_bytes()).hexdigest()
            source_database = next(row for row in json.loads((BASE / 'rollback-database-charsets.json').read_text()) if row['schema'] == schema)
            charset = identifier(source_database['charset'])
            collation = identifier(source_database['collation'])
            ddl['mysql'].append(f'CREATE DATABASE {schema} CHARACTER SET {charset} COLLATE {collation}; USE {schema};\n' + mysql_ddl)
            ddl['pg'].append(f'CREATE SCHEMA {schema}; SET search_path={schema};\n' + target.read_text())
        for db in ('mysql', 'pg'):
            self.sql(db, '\n'.join(ddl[db]))
        pg_columns = self.sql('pg', "SELECT table_schema||'.'||table_name,column_name,data_type FROM information_schema.columns WHERE table_schema LIKE 'pawbridge_%' ORDER BY table_schema,table_name,ordinal_position").stdout
        mysql_columns = self.sql('mysql', "SELECT CONCAT(table_schema,'.',table_name),column_name FROM information_schema.columns WHERE table_schema LIKE 'pawbridge_%' AND extra NOT LIKE '%GENERATED%'").stdout
        source_columns = {}
        for line in mysql_columns.splitlines():
            name,col = line.split('\t'); source_columns.setdefault(name,set()).add(col)
        all_columns = {}
        for line in pg_columns.splitlines():
            name,col,datatype = line.split('|'); all_columns.setdefault(name,[]).append((col,datatype))
        seeds = []; grants = []
        for service,tables in FIXTURES.items():
            schema = 'pawbridge_' + service
            for table,fixture in tables.items():
                name = table_name(service,table)
                columns = all_columns[name]
                require(source_columns[name] == {col for col,_ in columns},'Column mismatch '+name)
                self.columns[name] = columns
                seeds.extend(insert(name,changed_row(fixture['row'],n)) for n in (100,200))
                if fixture['identity']: seeds.append(f'ALTER TABLE {name} AUTO_INCREMENT=500;')
            seeds.append(f"CREATE USER 'app_{service}'@'localhost' IDENTIFIED BY '{PASSWORD}'; GRANT SELECT,INSERT,UPDATE,DELETE ON {schema}.* TO 'app_{service}'@'localhost';")
            grants.append(f'CREATE ROLE app_{service} LOGIN; GRANT USAGE ON SCHEMA {schema} TO app_{service}; GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA {schema} TO app_{service}; GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA {schema} TO app_{service};')
        self.sql('mysql','\n'.join(seeds))
        self.sql('pg','\n'.join(grants))
        self.checked('actual DDL loaded; synthetic rows in five service schemas')

    def snapshot(self, db):
        result = {}; statements = []
        for service,tables in FIXTURES.items():
            for table,fixture in tables.items():
                name = table_name(service,table); result[name] = []
                columns = self.columns[name]
                expressions = [f'CAST({col} AS CHAR)' if db == 'mysql' else col+'::text' for col,_ in columns]
                function = 'JSON_ARRAY' if db == 'mysql' else 'json_build_array'
                statements.append(f"SELECT {function}('{name}',{function}({','.join(expressions)})) FROM {name} ORDER BY {fixture['pk']};")
        # One client process per snapshot; JSON retains table identity and embedded newlines.
        for line in self.sql(db,'\n'.join(statements)).stdout.splitlines():
            name,row = json.loads(line); columns = self.columns[name]
            for index,(_,datatype) in enumerate(columns):
                if row[index] is not None and datatype.startswith('timestamp'):
                    # PostgreSQL omits trailing fractional zeros; Python <3.11
                    # fromisoformat accepts only three or six fractional digits.
                    timestamp = re.sub(r'\.(\d{1,6})(?=$|[+-])',
                                       lambda match: '.' + match.group(1).ljust(6, '0'), row[index])
                    row[index] = datetime.fromisoformat(timestamp).isoformat(sep=' ',timespec='microseconds')
                if row[index] is not None and datatype in ('json','jsonb'):
                    row[index] = json.dumps(json.loads(row[index]),ensure_ascii=False,sort_keys=True,separators=(',',':'))
            result[name].append(dict(zip((col for col,_ in columns),row)))
        return result

    def hold_write(self, db):
        # A real uncommitted write must be drained; disabling login does not kill it.
        query = "BEGIN; UPDATE pawbridge_user.users SET name='미커밋' WHERE user_id=100; "
        query += 'SELECT pg_sleep(45); COMMIT;' if db == 'pg' else 'SELECT SLEEP(45); COMMIT;'
        process = subprocess.Popen(self.sql_args(db, 'app_user'), stdin=subprocess.PIPE,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, text=True)
        self.processes.append(process)
        process.stdin.write(query + '\n')
        process.stdin.close()
        query = ("SELECT pid FROM pg_stat_activity WHERE usename='app_user' AND wait_event='PgSleep'"
                 if db == 'pg' else "SELECT id FROM information_schema.processlist WHERE user='app_user' AND state='User sleep'")
        wait_for(lambda: bool(self.sql(db, query).stdout.strip()), db + ' open transaction')
        return int(self.sql(db, query).stdout.strip()), process

    def fence(self, db):
        connection, process = self.hold_write(db)
        for service in FIXTURES:
            query = (f'ALTER ROLE app_{service} NOLOGIN;' if db == 'pg'
                     else f"ALTER USER 'app_{service}'@'localhost' ACCOUNT LOCK;")
            self.sql(db, query)
            require(self.sql(db, 'SELECT 1', 'app_' + service, check=False).returncode != 0,
                    'Fresh writer was not denied: ' + db + service)
        active = (f'SELECT count(*) FROM pg_stat_activity WHERE pid={connection}' if db == 'pg'
                  else f'SELECT count(*) FROM information_schema.processlist WHERE id={connection}')
        require(self.sql(db, active).stdout.strip() == '1', 'Expected retained old session')
        self.sql(db, f'SELECT pg_terminate_backend({connection});' if db == 'pg' else f'KILL CONNECTION {connection};')
        process.wait(timeout=10)
        wait_for(lambda: self.sql(db, active).stdout.strip() == '0', db + ' session drained')
        require(self.sql(db, 'SELECT name FROM pawbridge_user.users WHERE user_id=100').stdout.strip() != '미커밋',
                'Open transaction leaked through drain')
        if db == 'mysql':
            self.sql(db, 'SET GLOBAL read_only=ON;')
        self.checked(db + ': five writer logins denied; existing transaction terminated and rolled back')

    def reconcile(self, before, after):
        dml = []
        for service, tables in reversed(list(FIXTURES.items())):
            for table, fixture in reversed(list(tables.items())):
                name = table_name(service, table)
                expected_ids = {row[fixture['pk']] for row in after[name]}
                for row in before[name]:
                    if row[fixture['pk']] not in expected_ids:
                        dml.append(f"DELETE FROM {name} WHERE {fixture['pk']}={literal(row[fixture['pk']])};")
        for service, tables in FIXTURES.items():
            for table, fixture in tables.items():
                name = table_name(service, table)
                old_ids = {row[fixture['pk']] for row in before[name]}
                for row in after[name]:
                    if row[fixture['pk']] not in old_ids:
                        dml.append(insert(name, row))
                    else:
                        assignments = ','.join(col + '=' + literal(value) for col, value in row.items() if col != fixture['pk'])
                        dml.append(f"UPDATE {name} SET {assignments} WHERE {fixture['pk']}={literal(row[fixture['pk']])};")
        return dml

    def run(self):
        self.setup()
        initial = self.snapshot('mysql')
        self.fence('mysql')
        source_position = self.sql('mysql', 'SHOW BINARY LOG STATUS').stdout
        initial_rows = []
        for service, tables in FIXTURES.items():
            for table, fixture in tables.items():
                name = table_name(service, table)
                initial_rows.extend(insert(name, row) for row in initial[name])
                if fixture['identity']:
                    next_id = self.sql('mysql', f"SELECT auto_increment FROM information_schema.tables WHERE table_schema='pawbridge_{service}' AND table_name='{table}'").stdout.strip()
                    initial_rows.append(f"SELECT setval(pg_get_serial_sequence('{name}','{fixture['pk']}'), {next_id}, false);")
        self.sql('pg', 'BEGIN;\n' + '\n'.join(initial_rows) + '\nCOMMIT;')
        require(self.snapshot('pg') == initial, 'Initial forward copy differs')
        self.checked('forward copy: all selected columns match including Korean/JSON/null/microsecond timestamps')
        for service, tables in FIXTURES.items():
            query = ['BEGIN;']
            for table, fixture in reversed(list(tables.items())):
                if fixture['update']:
                    name = table_name(service, table)
                    query.append(f"DELETE FROM {name} WHERE {fixture['pk']}=200;")
            for table, fixture in tables.items():
                name = table_name(service, table)
                if fixture['update']:
                    assignments = ','.join(col + '=' + literal(value) for col, value in fixture['update'].items())
                    query.append(f"UPDATE {name} SET {assignments} WHERE {fixture['pk']}=100;")
                row = changed_row(fixture['row'], 500)
                if fixture['identity']:
                    row.pop(fixture['pk'])
                query.append(insert(name, row))
            query.append('COMMIT;')
            self.sql('pg', '\n'.join(query), 'app_' + service)
            for table, fixture in tables.items():
                if fixture['identity']:
                    name = table_name(service, table)
                    consumed = self.sql('pg', f"BEGIN; SELECT nextval(pg_get_serial_sequence('{name}','{fixture['pk']}')); ROLLBACK;").stdout.strip()
                    require(consumed == '501', 'Expected source high-watermark preservation ' + name)
        expected = self.snapshot('pg')
        self.fence('pg')
        require(expected == self.snapshot('pg'), 'Fence did not preserve committed PG data')
        self.checked('PG new inserts/updates/deletes committed; Outbox and processed-event history retained; consumed ID=501')

        # Test-only reconciliation for these fixtures: missing PKs are deleted child-first,
        # existing PKs updated, new PKs inserted parent-first. FK/unique checks stay enabled.
        dml = self.reconcile(initial, expected)
        prefix = 'SET SESSION sql_log_bin=0; START TRANSACTION;\n'
        # Real FK error midway must roll back earlier service DML when the client closes.
        midway = len(dml) // 2
        broken = dml[:midway] + ["INSERT INTO pawbridge_user.favorites (animal_id,created_at,user_id) VALUES (1,'2026-09-21',999999);"] + dml[midway:]
        failure = self.sql('mysql', prefix + '\n'.join(broken) + '\nCOMMIT;', check=False)
        require(failure.returncode != 0 and '1452' in failure.stderr, 'Expected injected real FK violation')
        require(self.snapshot('mysql') == initial, 'Partial reverse copy leaked from failed transaction')
        require(self.sql('mysql', 'SHOW BINARY LOG STATUS').stdout == source_position, 'Failed import changed source binlog')
        self.checked('injected FK failure: cross-schema reverse transaction fully rolled back; source binlog unchanged')
        # A separate negative fixture demonstrates why rollback compatibility must be fenced.
        # These are synthetic data edits; never silently strip unsupported production text.
        original = self.sql('pg', 'SELECT title FROM pawbridge_community.posts WHERE post_id=100').stdout.strip()
        self.sql('pg', "UPDATE pawbridge_community.posts SET title='검증 🐕' WHERE post_id=100;")
        incompatible = self.snapshot('pg')
        denied = self.sql('mysql', prefix + '\n'.join(self.reconcile(initial, incompatible)) + '\nCOMMIT;', check=False)
        require(denied.returncode != 0 and '1366' in denied.stderr, 'Expected utf8mb3 incompatibility')
        require(self.snapshot('mysql') == initial, 'Incompatible data caused partial rollback')
        require(self.snapshot('pg') == incompatible, 'Unsupported text was silently modified')
        for db in ('mysql', 'pg'):
            require(self.sql(db, 'SELECT 1', 'app_user', check=False).returncode != 0, 'Failure reopened writes')
        require(self.sql('mysql', 'SHOW BINARY LOG STATUS').stdout == source_position, 'Failed unicode import altered binlog')
        self.evidence['rollback_blockers'] = ['PG accepts supplementary Unicode but existing MySQL utf8mb3 columns reject it; production compatibility policy required']
        self.checked('supplementary Unicode: real MySQL 1366 failure leaves source unchanged and both writer fences closed')
        # Restore only this explicit negative TEST fixture to exercise the successful path next.
        self.sql('pg', 'UPDATE pawbridge_community.posts SET title=' + literal(original) + ' WHERE post_id=100;')
        require(self.snapshot('pg') == expected, 'Negative fixture reset mismatch')
        self.sql('mysql', prefix + '\n'.join(dml) + '\nCOMMIT;')
        require(self.snapshot('mysql') == expected, 'Reverse reconciliation mismatch')
        # Re-running the same now-successful reconciliation computes updates rather than re-inserts.
        retry = self.reconcile(self.snapshot('mysql'), expected)
        self.sql('mysql', prefix + '\n'.join(retry) + '\nCOMMIT;')
        require(self.snapshot('mysql') == expected, 'Repeated reconciliation changed rows/events')
        self.checked('reverse copy and repeat: all selected rows/IDs/event payloads match; no duplicates')
        counters = []
        for service, tables in FIXTURES.items():
            for table, fixture in tables.items():
                if fixture['identity']:
                    name = table_name(service, table)
                    # MySQL AUTO_INCREMENT reset is DDL (implicit commit), so it is a separate fenced phase.
                    high = int(self.sql('pg', f"SELECT last_value FROM {name}_{fixture['pk']}_seq").stdout.strip())
                    require(high == 501, 'Unexpected sequence after rollback ' + name)
                    current_next = int(self.sql('mysql', f"SELECT auto_increment FROM information_schema.tables WHERE table_schema='pawbridge_{service}' AND table_name='{table}'").stdout.strip())
                    counters.append(f'ALTER TABLE {name} AUTO_INCREMENT={max(high + 1, current_next)};')
        self.sql('mysql', 'SET SESSION sql_log_bin=0;\n' + '\n'.join(counters))
        require(self.sql('mysql', 'SHOW BINARY LOG STATUS').stdout == source_position,
                'Administrative reconciliation would appear in old CDC binlog')
        self.checked('reverse DML and counter DDL excluded from MySQL binlog; not a Kafka delivery guarantee')
        # Only after all DML/counters are verified do the synthetic source writers resume.
        self.sql('mysql', 'SET GLOBAL read_only=OFF;')
        for service, tables in FIXTURES.items():
            self.sql('mysql', f"ALTER USER 'app_{service}'@'localhost' ACCOUNT UNLOCK;")
            for table, fixture in tables.items():
                if fixture['identity']:
                    name = table_name(service, table)
                    row = changed_row(fixture['row'], 502)
                    row.pop(fixture['pk'])
                    output = self.sql('mysql', insert(name, row) + ' SELECT LAST_INSERT_ID();', 'app_' + service).stdout.strip()
                    require(int(output) >= 502, 'Consumed ID reused after resume ' + name)
        self.checked('MySQL writes resumed after reconciliation; all 18 identity columns allocate >=502, not a consumed ID')
        for name, rows in expected.items():
            self.evidence['tables'][name] = {'rows_after_pg_writes': len(rows),
                'normalized_sha256': hashlib.sha256(json.dumps(rows, ensure_ascii=False, sort_keys=True).encode()).hexdigest()}
        self.evidence['success'] = True
        self.evidence['production_rollback_ready'] = False

    def cleanup(self):
        errors = []
        for name in reversed(self.created):
            result = command('docker', 'rm', '-fv', name, check=False)
            if result.returncode and 'No such container' not in result.stderr:
                errors.append(result.stderr[-500:])
        for process in self.processes:
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=10)
        if self.network:
            result = command('docker', 'network', 'rm', self.prefix, check=False)
            if result.returncode:
                errors.append(result.stderr[-500:])
        self.evidence['cleanup'] = not errors
        self.evidence['cleanup_errors'] = errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--postgres-image', default='pgvector/pgvector:0.8.6-pg17')
    parser.add_argument('--mysql-image', default='mysql:8.4')
    parser.add_argument('--evidence', required=True, type=Path)
    args = parser.parse_args()
    # Reserve a fresh evidence file before any Docker operations.
    with args.evidence.open('x') as evidence_file:
        rehearsal = Rehearsal(args)
        started = time.monotonic()
        try:
            rehearsal.run()
        except Exception as error:
            rehearsal.evidence.update(success=False, error=str(error))
            raise
        finally:
            rehearsal.cleanup()
            rehearsal.evidence['elapsed_seconds'] = round(time.monotonic() - started, 3)
            json.dump(rehearsal.evidence, evidence_file, ensure_ascii=False, indent=2)
            evidence_file.write('\n')
        require(rehearsal.evidence['cleanup'], 'Temporary resource cleanup incomplete')


if __name__ == '__main__':
    main()
