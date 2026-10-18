#!/usr/bin/env python3
"""Opt-in charset rollback contract using actual schemas and disposable databases."""
import argparse
import hashlib
import json
import re
from pathlib import Path
import time

from rehearse_rollback import BASE, ROOT, FIXTURES, Rehearsal, insert, literal, require

INVENTORY = json.loads((BASE / 'rollback-charset-columns.json').read_text())


class CharsetRehearsal(Rehearsal):
    def setup(self):
        super().setup()
        source_rows = self.snapshot('mysql')
        self.sql('pg', 'BEGIN;\n' + '\n'.join(insert(name, row) for name, rows in source_rows.items() for row in rows) + '\nCOMMIT;')
        require(self.snapshot('pg') == source_rows, 'Forward fixture copy mismatch')
        self.sql('pg', 'CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public; CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;')
        for service in FIXTURES:
            directory = ROOT / (service + '-service/src/migration/resources/db')
            if service == 'animal':
                for path in sorted((directory / 'migration').glob('V[2-9]__*.sql')):
                    self.sql('mysql', 'USE pawbridge_animal;\n' + path.read_text())
            for path in sorted((directory / 'postgresql').glob('V[2-9]__*.sql')):
                self.sql('pg', f'SET search_path=pawbridge_{service},public;\n' + path.read_text())
                self.evidence.setdefault('additional_ddl_sha256', {})[str(path.relative_to(ROOT))] = hashlib.sha256(path.read_bytes()).hexdigest()

    def run(self):
        self.setup()
        # Static reviewed inventory is checked against the real source DDL, and installed
        # PG constraints must reference exactly those columns (not all text/JSON fields).
        expected = {(c['schema'], c['table'], c['column']): c['charset'] for c in INVENTORY}
        source = self.sql('mysql', "SELECT table_schema,lower(table_name),lower(column_name),character_set_name FROM information_schema.columns WHERE table_schema LIKE 'pawbridge_%' AND character_set_name IS NOT NULL").stdout
        actual_source = {tuple(line.split('\t')[:3]): line.split('\t')[3] for line in source.splitlines()}
        compared = 0
        for key, charset in expected.items():
            if key[1].startswith('batch_'):
                continue  # Source Batch metadata came from the separately inspected running DB.
            require(actual_source.get(key) == charset, 'Source charset drift: ' + str(key))
            compared += 1
        definitions = self.sql('pg', "SELECT n.nspname,t.relname,c.conname,encode(convert_to(pg_get_constraintdef(c.oid),'UTF8'),'hex'),string_agg(a.attname,',' ORDER BY a.attname) FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace CROSS JOIN LATERAL unnest(c.conkey) k(attnum) JOIN pg_attribute a ON a.attrelid=t.oid AND a.attnum=k.attnum WHERE c.conname LIKE 'ck_rollback_charset_%' GROUP BY n.nspname,t.relname,c.conname,c.oid ORDER BY 1,2").stdout
        installed = set()
        for line in definitions.splitlines():
            schema, table, name, encoded_definition, columns = line.split('|')
            definition = bytes.fromhex(encoded_definition).decode('utf-8')
            installed.update((schema, table, col) for col in columns.split(','))
        require(installed == {key for key, value in expected.items() if value != 'utf8mb4'}, 'Guarded columns differ from source repertoire')
        self.evidence['source_columns_compared'] = compared
        self.evidence['guarded_columns'] = len(installed)
        self.evidence['guarded_tables'] = len(definitions.splitlines())
        self.checked('source charset inventory and all installed CHECK column sets match')
        # Evaluate the real installed CHECK definition in LIKE-shaped temporary tables.
        # Removing other NOT NULL/business checks isolates charset boundary assertions.
        for line in definitions.splitlines():
            schema, table, name, encoded_definition, columns = line.split('|')
            definition = bytes.fromhex(encoded_definition).decode('utf-8')
            sql = [f'CREATE TEMP TABLE charset_probe (LIKE {schema}.{table} EXCLUDING CONSTRAINTS);']
            names = self.sql('pg', f"SELECT column_name FROM information_schema.columns WHERE table_schema='{schema}' AND table_name='{table}'").stdout.splitlines()
            sql += [f'ALTER TABLE charset_probe ALTER COLUMN {col} DROP NOT NULL;' for col in names]
            sql += [f'ALTER TABLE charset_probe ADD CONSTRAINT {name} {definition};']
            for col in columns.split(','):
                high = 127 if expected[(schema, table, col)] == 'ascii' else 65535
                sql += [f'INSERT INTO charset_probe ({col}) VALUES (NULL),(chr({high}));']
                for bad in (high + 1, 1114111):
                    sql += [f"""DO $test$ BEGIN
                        BEGIN
                            INSERT INTO charset_probe ({col}) VALUES (chr({bad}));
                            RAISE EXCEPTION 'charset check failed to reject {schema}.{table}.{col} code point {bad}';
                        EXCEPTION WHEN check_violation THEN NULL;
                        END;
                    END $test$;"""]
            sql += ['DROP TABLE charset_probe;']
            self.sql('pg', '\n'.join(sql))
        self.checked('159 restricted columns: NULL and upper allowed boundary accepted; both unsupported boundaries rejected')
        original = self.snapshot('pg')
        require(all(len(rows) == 2 for rows in original.values()), 'Expected populated source fixtures before business updates')
        for service, table, column in [('animal','animals','description'),('user','users','name'),
                                       ('community','posts','title'),('store','products','name'),('payment','payments','method')]:
            pk = FIXTURES[service][table]['pk']
            failure = self.sql('pg', "\\set VERBOSITY verbose\n"
                               f"BEGIN; UPDATE pawbridge_{service}.{table} SET {column}='앞선 수정' WHERE {pk}=100; "
                               f"UPDATE pawbridge_{service}.{table} SET {column}='검증 🐕' WHERE {pk}=200; COMMIT;", check=False)
            require(failure.returncode != 0 and '23514' in failure.stderr and 'ck_rollback_charset_' in failure.stderr,
                    'Expected structured CHECK failure ' + service + ': ' + failure.stderr[-1500:])
            require(self.snapshot('pg') == original, 'Rejected input committed business/event changes ' + service)
        self.checked('five real business tables: unsupported UPDATE transaction rejected with SQLSTATE23514; rows/events unchanged')
        for service in ('user','community','store','payment'):
            table='outbox' if service in ('store','payment') else 'outbox_events'
            self.sql('pg', f"UPDATE pawbridge_{service}.{table} SET payload='{{\"text\":\"🐕\"}}';")
        self.sql('pg', "UPDATE pawbridge_user.shelter_applications SET shelter_name='보호소 🐕';")
        self.sql('pg', "INSERT INTO pawbridge_animal.pet_travel_regions(code,name,fetched_at) VALUES ('99','여행 🐕',CURRENT_TIMESTAMP);")
        self.checked('existing JSON/utf8mb4 fields accept emoji; ASCII source keys reject non-ASCII')
        # Copy the now-compatible PG rows back to the real MySQL schema, without
        # altering or dropping any PG charset constraint or source charset.
        after = self.snapshot('pg')
        before = self.snapshot('mysql')
        self.sql('mysql', 'SET SESSION sql_log_bin=0; BEGIN;\n' + '\n'.join(self.reconcile(before, after)) + '\nCOMMIT;')
        require(self.snapshot('mysql') == after, 'Guard-compatible writes cannot be reverse copied')
        self.checked('guard-compatible Korean/JSON/utf8mb4 rows reverse-copy without changing data or disabling constraints')
        # Validated migration on existing incompatible data must fail, not silently grandfather it.
        self.sql('pg', 'ALTER TABLE pawbridge_community.posts DROP CONSTRAINT ck_rollback_charset_posts;')
        self.sql('pg', "UPDATE pawbridge_community.posts SET title='기존 🐕' WHERE post_id=100;")
        guard = next((ROOT/'community-service/src/migration/resources/db/postgresql').glob('*rollback_charset*'))
        failed = self.sql('pg', 'SET search_path=pawbridge_community;\nBEGIN;\n' + re.search(r'ALTER TABLE posts ADD CONSTRAINT.*?;', guard.read_text(), re.S).group(0) + '\nCOMMIT;', check=False)
        require(failed.returncode != 0 and 'ck_rollback_charset_posts' in failed.stderr, 'Existing incompatible row silently accepted by migration')
        require('🐕' in self.sql('pg', 'SELECT title FROM pawbridge_community.posts WHERE post_id=100').stdout, 'Migration altered data')
        self.checked('existing incompatible data blocks validated migration without deleting or replacing it')
        self.evidence.update(success=True, production_rollback_ready=False,
                             remaining=['CDC/consumer drain and offsets', 'external effects', 'all-table live cutover'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--postgres-image', default='pgvector/pgvector:0.8.6-pg17')
    parser.add_argument('--mysql-image', default='mysql:8.4')
    parser.add_argument('--evidence', required=True, type=Path)
    args=parser.parse_args()
    with args.evidence.open('x') as output:
        run=CharsetRehearsal(args)
        started=time.monotonic()
        try:
            run.run()
        except Exception as error:
            run.evidence.update(success=False,error=str(error))
            raise
        finally:
            run.cleanup()
            run.evidence['elapsed_seconds']=round(time.monotonic()-started,3)
            json.dump(run.evidence,output,ensure_ascii=False,indent=2)
            output.write('\n')
        require(run.evidence['cleanup'],'Resource cleanup incomplete')


if __name__=='__main__':
    main()
