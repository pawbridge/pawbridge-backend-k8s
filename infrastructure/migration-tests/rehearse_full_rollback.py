#!/usr/bin/env python3
"""Synthetic 51-table reverse-copy compatibility test; no live connection input."""
import argparse
from collections import Counter
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import time

from rehearse_rollback import BASE, FIXTURES, changed_row, literal, require
from rehearse_charset import CharsetRehearsal

EXTRA = json.loads((BASE / 'full-rollback-extra-fixtures.json').read_text())
SEQUENCES = ('BATCH_JOB_SEQ', 'BATCH_JOB_EXECUTION_SEQ', 'BATCH_STEP_EXECUTION_SEQ')


def quoted(db, name):
    require(re.fullmatch(r'[A-Za-z_][A-Za-z_0-9]*', name), 'Invalid schema identifier')
    quote = '`' if db == 'mysql' else '"'
    return quote + name + quote


class FullRehearsal(CharsetRehearsal):
    def table(self, db, name):
        schema, table = self.layout[name]['source' if db == 'mysql' else 'target'].split('.')
        return quoted(db, schema) + '.' + quoted(db, table)

    def column(self, db, name, col):
        return quoted(db, self.layout[name]['source_columns'][col] if db == 'mysql' else col)

    def discover(self):
        source = self.sql('mysql', "SELECT table_schema,table_name,column_name FROM information_schema.columns WHERE table_schema LIKE 'pawbridge_%' AND extra NOT LIKE '%STORED GENERATED%' AND extra NOT LIKE '%VIRTUAL GENERATED%' ORDER BY table_schema,table_name,ordinal_position").stdout
        self.layout = {}
        for line in source.splitlines():
            schema, table, col = line.split('\t')
            if table.upper() in SEQUENCES or table == 'flyway_schema_history':
                continue
            name = schema + '.' + table.lower()
            row = self.layout.setdefault(name, {'source': schema + '.' + table, 'target': name,
                                                'source_columns': {}, 'columns': [], 'pk': [], 'parents': set()})
            row['source_columns'][col.lower()] = col
        target = self.sql('pg', "SELECT table_schema||'.'||table_name,column_name,data_type FROM information_schema.columns WHERE table_schema LIKE 'pawbridge_%' ORDER BY table_schema,table_name,ordinal_position").stdout
        for line in target.splitlines():
            name, col, datatype = line.split('|')
            if name in self.layout:
                if name in ('pawbridge_animal.animals', 'pawbridge_animal.shelters') and col in ('search_revision', 'search_dirty'):
                    continue  # PG-only derived search tracking, not a MySQL source column.
                self.layout[name]['columns'].append((col, datatype))
        keys = self.sql('mysql', "SELECT table_schema,table_name,column_name,constraint_name,referenced_table_schema,referenced_table_name FROM information_schema.key_column_usage WHERE table_schema LIKE 'pawbridge_%' ORDER BY table_schema,table_name,constraint_name,ordinal_position").stdout
        for line in keys.splitlines():
            schema, table, col, constraint, refschema, reftable = line.split('\t')
            name = schema + '.' + table.lower()
            if name not in self.layout:
                continue
            if constraint == 'PRIMARY':
                self.layout[name]['pk'].append(col.lower())
            if reftable != 'NULL':
                parent = refschema + '.' + reftable.lower()
                require(parent in self.layout, 'Uncovered FK parent: ' + parent)
                if parent != name:
                    self.layout[name]['parents'].add(parent)
        expected = set(EXTRA) | {'pawbridge_' + service + '.' + table
                                 for service, tables in FIXTURES.items() for table in tables}
        require(set(self.layout) == expected and len(expected) == 51, 'Source table coverage changed')
        for name, row in self.layout.items():
            require(set(row['source_columns']) == {col for col, _ in row['columns']}, 'Column mismatch ' + name)
        self.order = []
        while len(self.order) < len(self.layout):
            ready = sorted(name for name, row in self.layout.items()
                           if name not in self.order and row['parents'] <= set(self.order))
            require(ready, 'Unresolved FK cycle')
            self.order.extend(ready)
        require([name for name, row in self.layout.items() if not row['pk']]
                == ['pawbridge_animal.batch_job_execution_params'], 'Unexpected keyless table')

    def insert_row(self, db, name, row):
        return 'INSERT INTO ' + self.table(db, name) + ' (' + ','.join(
            self.column(db, name, col) for col in row) + ') VALUES (' + ','.join(
            literal(value) for value in row.values()) + ');'

    def where(self, db, name, row, cols):
        operator = '<=>' if db == 'mysql' else 'IS NOT DISTINCT FROM'
        return ' AND '.join(self.column(db, name, col) + ' ' + operator + ' ' + literal(row[col]) for col in cols)

    def full_snapshot(self, db):
        statements = []
        result = {name: [] for name in self.order}
        for name in self.order:
            function = 'JSON_ARRAY' if db == 'mysql' else 'json_build_array'
            expressions = []
            for col, datatype in self.layout[name]['columns']:
                value = self.column(db, name, col)
                expressions.append('CAST(' + value + ' AS CHAR)' if db == 'mysql' else value + '::text')
            statements.append('SELECT ' + function + '(' + literal(name) + ',' + function + '('
                              + ','.join(expressions) + ')) FROM ' + self.table(db, name) + ';')
        for line in self.sql(db, '\n'.join(statements)).stdout.splitlines():
            name, values = json.loads(line)
            row = {}
            for (col, datatype), value in zip(self.layout[name]['columns'], values):
                if value is not None and datatype.startswith('timestamp'):
                    value = re.sub(r'\.(\d{1,6})(?=$|[+-])', lambda m: '.' + m.group(1).ljust(6, '0'), value)
                    value = datetime.fromisoformat(value).isoformat(sep=' ', timespec='microseconds')
                elif value is not None and datatype in ('json', 'jsonb'):
                    value = json.dumps(json.loads(value), ensure_ascii=False, sort_keys=True, separators=(',', ':'))
                elif value is not None and datatype == 'boolean':
                    value = '1' if value in ('true', '1') else '0'
                row[col] = value
            result[name].append(row)
        for rows in result.values():
            rows.sort(key=lambda row: json.dumps(row, sort_keys=True, ensure_ascii=False))
        return result

    def difference(self, db, before, after):
        deletes = {name: [] for name in self.order}
        writes = {name: [] for name in self.order}
        for name in self.order:
            keys = self.layout[name]['pk']
            if keys:
                old = {tuple(row[key] for key in keys): row for row in before[name]}
                new = {tuple(row[key] for key in keys): row for row in after[name]}
                for key in old.keys() - new.keys():
                    deletes[name].append('DELETE FROM ' + self.table(db, name) + ' WHERE '
                                         + self.where(db, name, old[key], keys) + ';')
                for key, row in new.items():
                    if key not in old:
                        writes[name].append(self.insert_row(db, name, row))
                    elif row != old[key]:
                        updates = ','.join(self.column(db, name, col) + '=' + literal(value)
                                           for col, value in row.items() if col not in keys)
                        writes[name].append('UPDATE ' + self.table(db, name) + ' SET ' + updates
                                            + ' WHERE ' + self.where(db, name, row, keys) + ';')
            else:
                # Batch parameters have no PK. Preserve duplicate multiplicity; never invent an ID.
                encode = lambda row: json.dumps(row, sort_keys=True, ensure_ascii=False)
                old = Counter(map(encode, before[name])); new = Counter(map(encode, after[name]))
                for serialized, count in (old - new).items():
                    row = json.loads(serialized)
                    predicate = self.where(db, name, row, row)
                    if db == 'mysql':
                        sql = 'DELETE FROM ' + self.table(db, name) + ' WHERE ' + predicate + ' LIMIT 1;'
                    else:
                        sql = 'DELETE FROM ' + self.table(db, name) + ' WHERE ctid IN (SELECT ctid FROM ' + self.table(db, name) + ' WHERE ' + predicate + ' LIMIT 1);'
                    deletes[name].extend([sql] * count)
                for serialized, count in (new - old).items():
                    writes[name].extend([self.insert_row(db, name, json.loads(serialized))] * count)
        return [sql for name in reversed(self.order) for sql in deletes[name]] + [
            sql for name in self.order for sql in writes[name]]

    def prepare_counters(self):
        self.identities = []
        metadata = self.sql('mysql', "SELECT table_schema,table_name,column_name FROM information_schema.columns WHERE table_schema LIKE 'pawbridge_%' AND extra LIKE '%auto_increment%' ORDER BY table_schema,table_name").stdout
        for line in metadata.splitlines():
            schema, table, col = line.split('\t')
            name = schema + '.' + table.lower()
            require(name in self.layout, 'Uncovered identity table')
            sequence = self.sql('pg', 'SELECT pg_get_serial_sequence(' + literal(name) + ',' + literal(col.lower()) + ')').stdout.strip()
            require(re.fullmatch(r'pawbridge_[a-z]+\.[a-z_]+', sequence), 'Missing owned identity sequence')
            self.identities.append((name, col, sequence))
        statements = []
        for name, col, sequence in self.identities:
            self.sql('mysql', 'SET SESSION sql_log_bin=0; ALTER TABLE ' + self.table('mysql', name) + ' AUTO_INCREMENT=500;')
            statements.append('SELECT setval(' + literal(sequence) + ',500,false);')
        for sequence in SEQUENCES:
            self.sql('mysql', 'SET SESSION sql_log_bin=0; UPDATE pawbridge_animal.' + quoted('mysql', sequence) + ' SET ID=500;')
            statements.append('SELECT setval(' + literal('pawbridge_animal.' + sequence.lower()) + ',500,true);')
        self.sql('pg', '\n'.join(statements))
        # Allocation survives transaction rollback; MAX(row ID) is not the next-ID watermark.
        consume = []
        sequences = [sequence for _, _, sequence in self.identities] + ['pawbridge_animal.' + name.lower() for name in SEQUENCES]
        for sequence in sequences:
            consume += ['SELECT nextval(' + literal(sequence) + ');'] * 2
        self.sql('pg', 'BEGIN;\n' + '\n'.join(consume) + '\nROLLBACK;')

    def reverse_counters(self):
        checks = {}
        for name, col, sequence in self.identities:
            last, called = self.sql('pg', 'SELECT last_value,is_called FROM ' + sequence).stdout.strip().split('|')
            next_id = int(last) + (called == 't')
            schema, table = self.layout[name]['source'].split('.')
            query = 'SELECT auto_increment FROM information_schema.tables WHERE table_schema=' + literal(schema) + ' AND table_name=' + literal(table)
            source_next = int(self.sql('mysql', query).stdout.strip())
            expected = max(source_next, next_id)
            self.sql('mysql', 'SET SESSION sql_log_bin=0; ALTER TABLE ' + self.table('mysql', name) + ' AUTO_INCREMENT=' + str(expected) + ';')
            actual = int(self.sql('mysql', query).stdout.strip())
            require(actual == expected and actual >= 502, 'Identity high-watermark lost: ' + name)
            checks[name] = {'next_id': actual, 'target_next': next_id}
        for sequence in SEQUENCES:
            last = int(self.sql('pg', 'SELECT last_value FROM pawbridge_animal.' + sequence.lower()).stdout.strip())
            source = 'pawbridge_animal.' + quoted('mysql', sequence)
            self.sql('mysql', 'SET SESSION sql_log_bin=0; UPDATE ' + source + ' SET ID=GREATEST(ID,' + str(last) + ');')
            actual = int(self.sql('mysql', 'SELECT ID FROM ' + source).stdout.strip())
            require(actual >= last and actual >= 502, 'Batch sequence high-watermark lost')
            checks['pawbridge_animal.' + sequence] = {'last_id': actual, 'next_id': actual + 1}
        self.evidence['counter_checks'] = checks
        self.checked(str(len(self.identities)) + ' identity and three Batch counters preserve allocations consumed by rolled-back transactions')

    def run(self):
        self.setup()
        self.discover()
        # Only owned synthetic tables. Existing migration singleton seeds are replaced with fixtures.
        seeds = []
        for name in self.order:
            if name not in EXTRA:
                continue
            fixture = EXTRA[name]
            singleton = fixture['row'].get('id') == 1
            if singleton:
                seeds.append('DELETE FROM ' + self.table('mysql', name) + ';')
            for number in ([100] if singleton else [100, 200]):
                row = changed_row(fixture['row'], number)
                seeds.append(self.insert_row('mysql', name, row))
                if name.endswith('.batch_job_execution_params') and number == 100:
                    seeds.append(self.insert_row('mysql', name, row))
        self.sql('mysql', 'BEGIN;\n' + '\n'.join(seeds) + '\nCOMMIT;')
        original = self.full_snapshot('mysql')
        require(all(original.values()), 'Every table needs a nonempty fixture')
        self.fence('mysql')
        self.sql('pg', 'BEGIN;\n' + '\n'.join(self.difference('pg', self.full_snapshot('pg'), original)) + '\nCOMMIT;')
        require(self.full_snapshot('pg') == original, 'Full forward copy mismatch')
        self.checked('51 populated source tables, composite keys and duplicate keyless Batch parameters copied exactly')
        self.prepare_counters()

        fixtures = dict(EXTRA)
        fixtures.update({'pawbridge_' + service + '.' + table: fixture
                         for service, tables in FIXTURES.items() for table, fixture in tables.items()})
        desired = json.loads(json.dumps(original))
        for name, rows in desired.items():
            fixture = fixtures[name]
            keys = self.layout[name]['pk'] or ['job_execution_id', 'parameter_name']
            row100 = changed_row(fixture['row'], 100)
            row200 = changed_row(fixture['row'], 200)
            def matches(row, wanted):
                return all(row[key] == str(wanted[key]) for key in keys)
            if fixture['row'].get('id') != 1:
                rows[:] = [row for row in rows if not matches(row, row200)]
            for row in rows:
                if matches(row, row100):
                    row.update({col: None if value is None else str(value) for col, value in fixture['update'].items()})
            if fixture['row'].get('id') != 1:
                new = changed_row(fixture['row'], 300)
                rows.append({col: None if new.get(col) is None else str(new[col]) for col, _ in self.layout[name]['columns']})
        # Let real defaults populate newly inserted rows, rather than converting omitted defaults into NULL.
        changes = self.difference('pg', original, desired)
        for name, fixture in fixtures.items():
            if fixture['row'].get('id') == 1:
                continue
            full = desired[name][-1]
            changes = [self.insert_row('pg', name, changed_row(fixture['row'], 300))
                       if sql == self.insert_row('pg', name, full) else sql for sql in changes]
        self.sql('pg', 'BEGIN;\n' + '\n'.join(changes) + '\nCOMMIT;')
        expected = self.full_snapshot('pg')
        require(all(expected[name] != original[name] for name in self.order), 'A table was not changed')
        params = expected['pawbridge_animal.batch_job_execution_params']
        require(sum(row['job_execution_id'] == '100' for row in params) == 2, 'Duplicate parameters lost')
        self.fence('pg')
        require(self.full_snapshot('pg') == expected, 'Fence changed committed values')

        position = self.sql('mysql', 'SHOW BINARY LOG STATUS').stdout
        changes = self.difference('mysql', original, expected)
        invalid = 'INSERT INTO pawbridge_user.favorites(animal_id,created_at,user_id) VALUES (1,CURRENT_TIMESTAMP,999999);'
        midpoint = len(changes) // 2
        broken = changes[:midpoint] + [invalid] + changes[midpoint:]
        prefix = 'SET SESSION sql_log_bin=0; BEGIN;\n'
        failed = self.sql('mysql', prefix + '\n'.join(broken) + '\nCOMMIT;', check=False)
        require(failed.returncode != 0 and '1452' in failed.stderr, 'Expected mid-copy FK failure')
        require(self.full_snapshot('mysql') == original, 'Partial full reverse copy committed')
        for _ in range(2):
            self.sql('mysql', prefix + '\n'.join(self.difference('mysql', self.full_snapshot('mysql'), expected)) + '\nCOMMIT;')
            require(self.full_snapshot('mysql') == expected, 'Full reverse-copy mismatch')
        require(not self.difference('mysql', self.full_snapshot('mysql'), expected), 'Repeat copy not a no-op')
        self.reverse_counters()
        require(self.sql('mysql', 'SHOW BINARY LOG STATUS').stdout == position, 'Administrative copy leaked into binlog')
        self.checked('51-table insert/update/delete reverse copy: all selected values equal, mid-copy failure atomic, repeat no-op')
        self.evidence.update(success=True, production_rollback_ready=False,
            scope='all 51 relational tables with synthetic rows; no live copy or external effects',
            tables={name: {'rows': len(rows), 'normalized_sha256': hashlib.sha256(json.dumps(rows, sort_keys=True).encode()).hexdigest()}
                    for name, rows in expected.items()},
            not_tested=['live data/fences/roles', 'full-table business HTTP flows',
                        'CDC consumer integration (separate rehearsal)', 'external Redis/R2/payment effects', 'ES/vector reverse restoration'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--postgres-image', default='pgvector/pgvector:0.8.6-pg17')
    parser.add_argument('--mysql-image', default='mysql:8.4')
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    with args.evidence.open('x') as output:
        rehearsal = FullRehearsal(args)
        started = time.monotonic()
        try:
            rehearsal.run()
        except Exception as failure:
            rehearsal.evidence.update(success=False, error=str(failure))
            raise
        finally:
            rehearsal.cleanup()
            rehearsal.evidence['elapsed_seconds'] = round(time.monotonic() - started, 3)
            json.dump(rehearsal.evidence, output, ensure_ascii=False, indent=2)
            output.write('\n')
        require(rehearsal.evidence['cleanup'], 'Owned resource cleanup incomplete')


if __name__ == '__main__':
    main()
