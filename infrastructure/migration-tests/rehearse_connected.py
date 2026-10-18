#!/usr/bin/env python3
"""Opt-in isolated copy -> actual CDC/listeners -> relational reverse rehearsal.
Only owned synthetic databases; never connects to Kubernetes or live databases.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET

from rehearse_rollback import ROOT, BASE, FIXTURES, PASSWORD, command, require, wait_for, literal
from rehearse_charset import CharsetRehearsal

CONFIG = ROOT / 'infrastructure/kafka/postgresql'
SERVICES = ('animal', 'user', 'community', 'payment', 'store')


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


class ConnectedRehearsal(CharsetRehearsal):
    def network_options(self):
        # A dedicated bridge permits loopback-only ports for host Gradle test JVMs.
        return []

    def database_options(self, db):
        return (['-p', '127.0.0.1::5432'], ['-c', 'wal_level=logical', '-c', 'max_replication_slots=8',
                '-c', 'max_wal_senders=8', '-c', 'max_slot_wal_keep_size=128MB']) if db == 'pg' else ([], [])

    def api(self, path, value=None, method=None):
        args = ['docker','exec','-i',self.names['connect'],'curl','--fail','--silent','--show-error',
                '--max-time','10','-H','Content-Type: application/json']
        if method: args += ['-X',method]
        if value is not None: args += ['--data-binary','@-']
        result = command(*args, 'http://127.0.0.1:8083'+path,
                         stdin=None if value is None else json.dumps(value),timeout=15).stdout
        return json.loads(result) if result else None

    def running(self):
        for service in SERVICES:
            status = self.api('/connectors/'+service+'-outbox-postgresql-connector/status')
            if status['connector']['state'] != 'RUNNING' or not status['tasks'] or any(t['state']!='RUNNING' for t in status['tasks']):
                return False
        return self.sql('pg','SELECT count(*) FROM pg_replication_slots WHERE active').stdout.strip() == '5'

    def start_cdc(self, runtime):
        command('docker','image','inspect',self.args.worker_image)
        self.kafka_port = free_port()  # Docker bind fails safely if another process claims it first.
        (runtime/'broker.properties').write_text(f'''process.roles=broker,controller
node.id=1
controller.quorum.voters=1@broker:9093
controller.listener.names=CONTROLLER
listeners=BROKER://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093,HOST://0.0.0.0:9094
advertised.listeners=BROKER://broker:9092,HOST://127.0.0.1:{self.kafka_port}
listener.security.protocol.map=BROKER:PLAINTEXT,CONTROLLER:PLAINTEXT,HOST:PLAINTEXT
inter.broker.listener.name=BROKER
log.dirs=/tmp/kraft-logs
num.partitions=1
offsets.topic.replication.factor=1
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
group.initial.rebalance.delay.ms=0
''')
        (runtime/'connect.properties').write_text('''bootstrap.servers=broker:9092
group.id=pg-cutover-rehearsal
config.storage.topic=cutover-configs
offset.storage.topic=cutover-offsets
status.storage.topic=cutover-status
config.storage.replication.factor=1
offset.storage.replication.factor=1
status.storage.replication.factor=1
offset.flush.interval.ms=1000
key.converter=org.apache.kafka.connect.storage.StringConverter
value.converter=org.apache.kafka.connect.json.JsonConverter
value.converter.schemas.enable=false
plugin.path=/opt/kafka/plugins
listeners=http://0.0.0.0:8083
rest.advertised.host.name=connect
''')
        scripts = {
            'broker': '/opt/kafka/bin/kafka-storage.sh format --ignore-formatted -t 4L6g3nShT-eMCtK--X86sw -c /work/broker.properties\nexec /opt/kafka/bin/kafka-server-start.sh /work/broker.properties',
            'connect': 'exec /opt/kafka/bin/connect-distributed.sh /work/connect.properties'}
        for key,memory,heap in [('broker','512m','256m'),('connect','768m','384m')]:
            self.names[key] = self.prefix+'-'+key
            (runtime/(key+'.sh')).write_text('#!/bin/bash\nset -e\n'+scripts[key]+'\n')
            args=['docker','run','-d','--pull=never','--name',self.names[key],'--label','pawbridge.test=connected-cutover',
                  '--network',self.prefix,'--network-alias',key,'--memory',memory,'--memory-swap',memory,'--cpus','1',
                  '-e','KAFKA_HEAP_OPTS=-Xms64m -Xmx'+heap,'-e','LOG_DIR=/tmp/kafka-logs','-v',str(runtime)+':/work:ro',
                  '--entrypoint','/bin/bash']
            if key=='broker': args += ['-p',f'127.0.0.1:{self.kafka_port}:9094']
            self.created.append(self.names[key])
            command(*args,self.args.worker_image,'/work/'+key+'.sh')
        wait_for(lambda:any(p['class']=='io.debezium.connector.postgresql.PostgresConnector' for p in self.api('/connector-plugins')),'packaged PG plugin',120)
        for service in SERVICES:
            schema='pawbridge_'+service; table='outbox' if service in ('payment','store') else 'outbox_events'
            self.sql('pg',f"CREATE PUBLICATION {schema}_outbox FOR TABLE {schema}.{table}; CREATE ROLE cdc_{service} LOGIN REPLICATION PASSWORD '{PASSWORD}'; GRANT CONNECT ON DATABASE pawbridge TO cdc_{service}; GRANT USAGE ON SCHEMA {schema} TO cdc_{service}; GRANT SELECT ON {schema}.{table} TO cdc_{service};")
            template=json.loads((CONFIG/(service+'-outbox-connector.json')).read_text())
            template['config'].update({'database.hostname':self.names['pg'],'database.user':'cdc_'+service,'database.password':PASSWORD})
            self.api('/connectors',template,'POST')
        wait_for(self.running,'five streaming connectors before target writes',120)
        self.checked('five target slots active before new writes; packaged canonical CDC configs running')

    def consumers(self):
        env=os.environ.copy()
        # Test credentials and target are supplied only to child test processes.
        env.update(JAVA_HOME=str(self.args.java_home),PATH=str(self.args.java_home/'bin')+os.pathsep+env['PATH'],
                   PG_CUTOVER_PORT=self.pg_port,PG_CUTOVER_KAFKA_PORT=str(self.kafka_port),PG_CUTOVER_MARKER=self.prefix)
        self.evidence['consumer_tests']={}
        for service in ('animal','user','store'):
            output=self.args.evidence.with_suffix('.'+service+'.log')
            with output.open('w') as log:
                result=subprocess.run(['bash','./gradlew','--offline','--no-daemon','--max-workers=1',
                    '-Dorg.gradle.jvmargs=-Xmx384m','-Porg.gradle.java.installations.paths='+str(self.args.java_home),
                    '--init-script',str(BASE/'cutover-tests.gradle'),'cutoverConsumerTest',
                    '--tests','*PostgresqlCutoverTest'],cwd=ROOT/(service+'-service'),env=env,stdout=log,stderr=subprocess.STDOUT,timeout=420)
            reports=list((ROOT/(service+'-service/build/test-results/cutoverConsumerTest')).glob('TEST-*PostgresqlCutoverTest.xml'))
            require(result.returncode==0 and len(reports)==1,service+' connected consumer failed; see '+str(output))
            xml=ET.parse(reports[0]).getroot()
            counts={key:int(xml.get(key,'0')) for key in ('tests','failures','errors','skipped')}
            require(counts=={'tests':1,'failures':0,'errors':0,'skipped':0},service+' test did not run successfully')
            self.evidence['consumer_tests'][service]=counts
            self.checked(service+' real Spring Kafka listener + PG transaction + failure/retry/duplicate/offset verified')

    def group_positions(self):
        classpath=(ROOT/'animal-service/build/cutover-classpath.txt').read_text()
        env=os.environ.copy(); env['PG_CUTOVER_KAFKA_PORT']=str(self.kafka_port)
        result=subprocess.run([str(self.args.java_home/'bin/java'),'-Xms16m','-Xmx96m','-cp',classpath,
                               'com.pawbridge.migration.CutoverOffsets'],env=env,capture_output=True,text=True,timeout=50)
        if result.returncode:
            raise RuntimeError('Offset probe failed: '+result.stdout[-1500:]+result.stderr[-1500:])
        lines=[line.removeprefix('CUTOVER_OFFSETS=') for line in result.stdout.splitlines() if line.startswith('CUTOVER_OFFSETS=')]
        require(len(lines)==1,'Missing offset report')
        return json.loads(lines[0])

    def run(self):
        self.evidence['scope']='isolated synthetic relational copy, real Spring Kafka consumers, reverse reconciliation'
        self.evidence['not_tested']=['live Kubernetes/Vault/roles/network','live MySQL CDC drain',
            'all-table new-write reverse copy','ES/search/vector reverse restoration','real payment/R2/Redis effects']
        self.setup()
        initial=self.snapshot('mysql')
        self.fence('mysql')
        source_position=self.sql('mysql','SHOW BINARY LOG STATUS').stdout
        self.sql('pg',"CREATE SCHEMA migration_test_guard; CREATE TABLE migration_test_guard.guard(marker text NOT NULL); INSERT INTO migration_test_guard.guard VALUES ("+literal(self.prefix)+");")
        for service,tables in FIXTURES.items():
            for table,fixture in tables.items():
                if fixture['identity']:
                    name='pawbridge_'+service+'.'+table
                    self.sql('pg',f"SELECT setval(pg_get_serial_sequence('{name}','{fixture['pk']}'),500,false);")
        self.pg_port=command('docker','port',self.names['pg'],'5432/tcp').stdout.strip().rsplit(':',1)[1]
        # Only 1.75GiB of DB/broker/Connect containers run during CDC; MySQL is stopped.
        command('docker','stop','--time','15',self.names['mysql'])
        with tempfile.TemporaryDirectory(prefix='pawbridge-cutover-runtime-') as directory:
            runtime=Path(directory); runtime.chmod(0o755)
            self.start_cdc(runtime)
            # Force worker loss with durable slots/internal topics before the live consumer phase.
            before=self.sql('pg','SELECT slot_name,restart_lsn::text FROM pg_replication_slots ORDER BY slot_name').stdout
            command('docker','kill',self.names['connect'])
            self.sql('pg',"UPDATE pawbridge_user.users SET name='중단 중 수정' WHERE user_id=100;")
            command('docker','start',self.names['connect'])
            wait_for(self.running,'CDC worker restart with existing slots',120)
            self.evidence['slots_before_restart']=before.splitlines()
            self.consumers()
            positions=self.group_positions()
            self.evidence['consumer_positions']=positions
            require(self.sql('pg',"SELECT count(*) FROM pg_stat_activity WHERE datname='pawbridge' AND usename='postgres' AND pid<>pg_backend_pid()").stdout.strip()=='0', 'Spring test sessions remain active')
            expected=self.snapshot('pg')
            self.fence('pg')
            require(expected==self.snapshot('pg'),'PG fence altered committed rows')
            require(self.group_positions()==positions,'consumer offsets changed after drain')
            # Preserve group/internal-topic storage across a broker restart.
            command('docker','stop','--time','15',self.names['connect'])
            command('docker','restart','--time','15',self.names['broker'])
            wait_for(lambda:self.group_positions()==positions,'consumer offsets retained after broker restart',90)
            self.checked('all three real consumer groups drained; offsets survive broker restart')
            for key in ('connect','broker'):
                command('docker','stop','--time','15',self.names[key])
        command('docker','start',self.names['mysql'])
        wait_for(lambda:self.sql('mysql','SELECT 1').stdout.strip()=='1','rollback source restarted')
        # MySQL GLOBAL read_only is not durable across restart: close it again before admin copy.
        self.sql('mysql','SET GLOBAL read_only=ON;')
        require(self.sql('mysql','SELECT 1','app_user',check=False).returncode!=0,'Source account fence lost on restart')
        # A restart rotates binlogs. Capture the new fenced position, not the old file name.
        self.evidence['source_position_before_stop']=source_position.strip()
        position=self.sql('mysql','SHOW BINARY LOG STATUS').stdout
        dml=self.reconcile(initial,expected)
        prefix='SET SESSION sql_log_bin=0; BEGIN;\n'
        broken=dml[:len(dml)//2]+["INSERT INTO pawbridge_user.favorites(animal_id,created_at,user_id) VALUES (1,CURRENT_TIMESTAMP,999999);"]+dml[len(dml)//2:]
        failed=self.sql('mysql',prefix+'\n'.join(broken)+'\nCOMMIT;',check=False)
        require(failed.returncode!=0 and '1452' in failed.stderr,'Expected reverse FK failure')
        require(self.snapshot('mysql')==initial,'Failed reverse copy partially committed')
        for _ in range(2):
            self.sql('mysql',prefix+'\n'.join(self.reconcile(self.snapshot('mysql'),expected))+'\nCOMMIT;')
            require(self.snapshot('mysql')==expected,'Reverse-copy rows differ')
        for service,tables in FIXTURES.items():
            for table,fixture in tables.items():
                if fixture['identity']:
                    name='pawbridge_'+service+'.'+table
                    seq=self.sql('pg',f"SELECT pg_get_serial_sequence('{name}','{fixture['pk']}')").stdout.strip()
                    last,called=self.sql('pg',f'SELECT last_value,is_called FROM {seq}').stdout.strip().split('|')
                    next_id=int(last)+(called=='t')
                    source_next=int(self.sql('mysql',f"SELECT auto_increment FROM information_schema.tables WHERE table_schema='pawbridge_{service}' AND table_name='{table}'").stdout.strip())
                    self.sql('mysql',f'SET SESSION sql_log_bin=0; ALTER TABLE {name} AUTO_INCREMENT={max(next_id,source_next)};')
        require(self.sql('mysql','SHOW BINARY LOG STATUS').stdout==position,'Administrative reverse copy changed source binlog')
        self.checked('21-table reverse copy includes real consumer changes/compensation/dedup IDs; FK failure atomic; repeat identical; ID counters preserved')
        self.evidence['tables']={name:{'rows':len(rows),'normalized_sha256':hashlib.sha256(json.dumps(rows,ensure_ascii=False,sort_keys=True).encode()).hexdigest()} for name,rows in expected.items()}
        self.evidence['success']=True
        self.evidence['production_rollback_ready']=False


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--postgres-image',default='pgvector/pgvector:0.8.6-pg17')
    parser.add_argument('--mysql-image',default='mysql:8.4')
    parser.add_argument('--worker-image',required=True)
    parser.add_argument('--java-home',type=Path,required=True)
    parser.add_argument('--evidence',type=Path,required=True)
    args=parser.parse_args()
    require((args.java_home/'bin/javac').is_file(),'Existing JDK required; nothing is installed')
    with args.evidence.open('x') as output:
        rehearsal=ConnectedRehearsal(args); started=time.monotonic()
        try: rehearsal.run()
        except Exception as error:
            rehearsal.evidence.update(success=False,error=str(error)); raise
        finally:
            for key in ('connect','broker'):
                if key in rehearsal.names:
                    logs=command('docker','logs','--tail','150',rehearsal.names[key],check=False)
                    args.evidence.with_suffix('.'+key+'.log').write_text(logs.stdout+logs.stderr)
            rehearsal.cleanup()
            rehearsal.evidence['elapsed_seconds']=round(time.monotonic()-started,3)
            json.dump(rehearsal.evidence,output,ensure_ascii=False,indent=2); output.write('\n')
        require(rehearsal.evidence['cleanup'],'Owned resource cleanup incomplete')

if __name__=='__main__': main()
