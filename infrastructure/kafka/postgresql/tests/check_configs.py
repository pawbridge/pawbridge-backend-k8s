"""Offline safeguards against wrong schemas, accidental replay and overlapping slots."""
import json
from pathlib import Path
import re
import unittest

BASE = Path(__file__).resolve().parents[1]
ROOT = BASE.parents[2]

class ConnectorContracts(unittest.TestCase):
    def test_targets_and_fields_exist_in_real_migration_schema(self):
        slots = set()
        for path in sorted(BASE.glob('*-connector.json')):
            with self.subTest(connector=path.name):
                document = json.loads(path.read_text())
                config = document['config']
                service = path.name.split('-')[0]
                schema = 'pawbridge_' + service
                table = 'outbox' if service in ('payment', 'store') else 'outbox_events'
                ddl = next((ROOT / (service + '-service/src/migration/resources/db/postgresql')).glob('V1*')).read_text()
                columns = re.search(r'CREATE TABLE ' + table + r' \((.*?)\n\);', ddl, re.S).group(1)
                for key in ('id', 'key', 'payload'):
                    field = config['transforms.outbox.table.field.event.' + key]
                    self.assertRegex(columns, r'\b' + field + r'\s')
                self.assertIn(config['transforms.outbox.table.fields.additional.placement'].split(':')[0], columns)
                self.assertEqual(config['database.dbname'], 'pawbridge')
                self.assertEqual(config['schema.include.list'], schema)
                self.assertEqual(config['table.include.list'], schema + r'\.' + table)
                self.assertNotIn(config['slot.name'], slots)
                slots.add(config['slot.name'])
        self.assertEqual(len(slots), 5)

    def test_cutover_does_not_replay_history_or_discard_errors(self):
        for path in BASE.glob('*-connector.json'):
            with self.subTest(connector=path.name):
                c = json.loads(path.read_text())['config']
                self.assertEqual(c['connector.class'], 'io.debezium.connector.postgresql.PostgresConnector')
                self.assertEqual(c['snapshot.mode'], 'no_data')
                self.assertEqual(c['publication.autocreate.mode'], 'disabled')
                self.assertEqual(c['slot.drop.on.stop'], 'false')
                self.assertEqual(c['errors.tolerance'], 'none')
                self.assertEqual(c['transforms.outbox.table.op.invalid.behavior'], 'fatal')
                self.assertGreater(int(c['max.queue.size']), int(c['max.batch.size']))
                self.assertLessEqual(int(c['max.queue.size.in.bytes']), 16 * 1024 * 1024)
                self.assertNotIn('schema.history.internal.kafka.topic', c)
                self.assertEqual(c['database.password'], '<CDC_PASSWORD>')
                self.assertEqual(c['key.converter'], 'org.apache.kafka.connect.json.JsonConverter')
                self.assertEqual(str(c['key.converter.schemas.enable']).lower(), 'false')
                self.assertNotIn('transforms.outbox.table.field.event.timestamp', c)
                if path.name.split('-')[0] in ('animal', 'user'):
                    self.assertEqual(c['transforms.outbox.route.by.field'], 'topic')
                    self.assertEqual(c['transforms.outbox.route.topic.replacement'], '${routedByValue}')

if __name__ == '__main__':
    unittest.main()
