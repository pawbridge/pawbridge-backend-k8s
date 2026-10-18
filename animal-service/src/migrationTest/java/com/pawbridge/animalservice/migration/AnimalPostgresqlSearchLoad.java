package com.pawbridge.animalservice.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.animalservice.dto.request.AnimalSearchRequest;
import com.pawbridge.animalservice.exception.SearchProjectionPendingException;
import com.pawbridge.animalservice.mapper.AnimalMapper;
import com.pawbridge.animalservice.search.KoreanSearchAnalyzer;
import com.pawbridge.animalservice.search.PostgresqlSearchProjector;
import com.pawbridge.animalservice.service.AnimalQueryService;
import com.pawbridge.animalservice.service.PostgresqlAnimalQueryService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/** Guarded opt-in load rehearsal. Public text fixture only; no source DB/network client. */
public final class AnimalPostgresqlSearchLoad {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final int MAX_ROWS=100000;
    private static final int PAGE=100;
    private static final PageRequest RELEVANCE=PageRequest.of(0,20,Sort.by("relevance"));

    public static void main(String[] args) throws Exception {
        if(args.length!=2) throw new IllegalArgumentException("NDJSON fixture and report required");
        Path fixture=Path.of(args[0]);
        if(Files.size(fixture)>128L*1024*1024) throw new IllegalArgumentException("Fixture exceeds 128MiB");
        String port=System.getenv("ANIMAL_PG_MIGRATION_TEST_PORT");
        if(port==null || !port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Disposable port required");
        String url="jdbc:postgresql://127.0.0.1:"+port+"/pawbridge";
        HikariConfig config=new HikariConfig();config.setJdbcUrl(url);config.setUsername("postgres");config.setPassword("local_pg_test_only");
        config.setMaximumPoolSize(5);config.setMinimumIdle(0);config.setConnectionTimeout(3000);
        config.setConnectionInitSql("SET search_path TO pawbridge_animal,public");
        ConnectionMetrics connections=new ConnectionMetrics();
        config.setMetricsTrackerFactory((name,stats)->connections);
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("complete",false);
        try(HikariDataSource source=new HikariDataSource(config);KoreanSearchAnalyzer analyzer=new KoreanSearchAnalyzer()) {
            JdbcTemplate jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(10);
            if(!jdbc.queryForList("SELECT marker FROM migration_test_guard.guard",String.class).equals(List.of("animal-pg-disposable")))
                throw new IllegalStateException("Disposable guard required");
            jdbc.execute("DROP SCHEMA IF EXISTS pawbridge_animal CASCADE");jdbc.execute("CREATE SCHEMA pawbridge_animal");
            Map<String,String> env=AnimalPostgresqlMigrationTest.environment(url);env.put("ANIMAL_PG_MIGRATION_CONFIRM_TARGET",url);
            AnimalPostgresqlMigration.execute("migrate",AnimalPostgresqlMigration.Settings.from(env));
            report.put("fixture",importFixture(jdbc,source,fixture));
            report.put("sourceRows",jdbc.queryForObject("SELECT count(*) FROM animals",Long.class));
            report.put("sourceStatuses",jdbc.queryForList("SELECT status,count(*) FROM animals GROUP BY status ORDER BY status"));
            save(report,args[1]);
            PostgresqlSearchProjector projector=new PostgresqlSearchProjector(source,analyzer);
            long start=System.nanoTime();List<Long> pageTimes=new ArrayList<>();int processed=0;
            for(int attempt=0;attempt<=MAX_ROWS/PAGE;attempt++) if(projector.refreshShelters(PAGE)==0) break;
            for(int attempt=0;attempt<=MAX_ROWS/PAGE;attempt++) {
                long tick=System.nanoTime();int rows=projector.refreshAnimals(PAGE);if(rows==0) break;
                pageTimes.add(elapsed(tick));processed+=rows;
                if(processed%5000==0) System.out.printf("PROJECTION rows=%d elapsedMs=%d%n",processed,elapsed(start));
            }
            long refreshMs=elapsed(start);
            report.put("initialProjection",Map.of("rows",processed,"elapsedMs",refreshMs,"pageLatencyMs",distribution(pageTimes)));
            if(jdbc.queryForObject("SELECT count(*) FROM animals WHERE search_dirty",Long.class)!=0) throw new IllegalStateException("Initial projection incomplete");
            save(report,args[1]);
            // Statistics maintenance has a separate bound; actual API queries retain five seconds.
            JdbcTemplate maintenance=new JdbcTemplate(source);maintenance.setQueryTimeout(60);
            Map<String,Long> statisticsTimes=new LinkedHashMap<>();
            for(String table:List.of("animals","shelters","animal_search_documents","shelter_search_documents")) {
                start=System.nanoTime();maintenance.execute("ANALYZE "+table);statisticsTimes.put(table,elapsed(start));
            }
            report.put("statisticsMaintenanceMs",statisticsTimes);save(report,args[1]);
            AnimalQueryService query=transactionalQuery(source,analyzer);
            List<Map<String,Object>> sequential=new ArrayList<>();
            for(String term:List.of("흰색","검정","목걸이","사람을 좋아","귀 접힘","겁이 많음")) {
                connections.reset();
                AnimalSearchRequest request=AnimalSearchRequest.builder().keyword(term).build();List<Long> times=new ArrayList<>();long total=0;
                for(int sample=0;sample<20;sample++) { start=System.nanoTime();total=query.searchAnimals(request,RELEVANCE).getTotalElements();times.add(elapsed(start)); }
                sequential.add(Map.of("term",term,"count",total,"latencyMs",distribution(times),"connections",connections.snapshot()));
                System.out.printf("QUERY term=%s count=%d timings=%s%n",term,total,distribution(times));
            }
            report.put("sequential",sequential);
            save(report,args[1]);
            connections.reset();
            Map<String,Object> readers=new LinkedHashMap<>(concurrentReads(query));
            readers.put("connections",connections.snapshot());report.put("concurrentReaders",readers);
            save(report,args[1]);
            report.put("plans",plans(source,analyzer));
            report.put("transactionalWrites",transactionalWrites(jdbc,source,analyzer,query,connections));
            save(report,args[1]);
            report.put("updateAvailability",updateAvailability(jdbc,projector,query));
            report.put("sizes",jdbc.queryForList("SELECT relname,pg_total_relation_size(oid) bytes FROM pg_class WHERE relnamespace='pawbridge_animal'::regnamespace AND relname IN ('animals','shelters','animal_search_documents','shelter_search_documents') ORDER BY relname"));
            report.put("hikari",Map.of("poolMax",5,"activeAtEnd",source.getHikariPoolMXBean().getActiveConnections(),"waitingAtEnd",source.getHikariPoolMXBean().getThreadsAwaitingConnection()));
            report.put("environment",Map.of("databaseVersion",jdbc.queryForObject("SELECT version()",String.class),"analyzerVersion",KoreanSearchAnalyzer.VERSION,"productionChanged",false,"queryMeasurement","service + real JDBC transactions, not HTTP/TLS/browser"));
            report.put("complete",true);save(report,args[1]);
            System.out.println("LOAD_REHEARSAL_COMPLETE");
        }
    }

    private static Map<String,Object> importFixture(JdbcTemplate jdbc,HikariDataSource source,Path file) throws Exception {
        long start=System.nanoTime();int rows=0;long lastId=0;JsonNode metadata;
        TransactionTemplate transaction=new TransactionTemplate(new JdbcTransactionManager(source));transaction.setTimeout(10);
        try(BufferedReader reader=Files.newBufferedReader(file)) {
            metadata=JSON.readTree(reader.readLine()).path("_meta");
            if(metadata.path("rows").asInt()<1 || metadata.path("rows").asInt()>MAX_ROWS) throw new IllegalArgumentException("Fixture row bound required");
            List<JsonNode> page=new ArrayList<>();String line;
            while((line=reader.readLine())!=null) {
                if(line.length()>32768) throw new IllegalArgumentException("Oversized text row");
                JsonNode row=JSON.readTree(line);long id=row.path("id").asLong();
                if(id<=lastId || ++rows>MAX_ROWS) throw new IllegalArgumentException("Sorted unique bounded fixture required");
                lastId=id;page.add(row);
                if(page.size()==PAGE) { writePage(jdbc,transaction,page);page.clear(); }
            }
            if(!page.isEmpty()) writePage(jdbc,transaction,page);
            if(rows!=metadata.path("rows").asInt()) throw new IllegalArgumentException("Fixture count mismatch");
        }
        System.out.printf("IMPORTED rows=%d elapsedMs=%d%n",rows,elapsed(start));
        return Map.of("metadata",metadata,"rows",rows,"elapsedMs",elapsed(start),"maxRowsInMemory",PAGE);
    }
    private static void writePage(JdbcTemplate jdbc,TransactionTemplate transaction,List<JsonNode> page) {
        transaction.executeWithoutResult(status -> {
            List<Object[]> shelters=new ArrayList<>(),animals=new ArrayList<>();
            for(JsonNode r:page) {
                shelters.add(new Object[]{r.path("shelter_id").asLong(),"load-"+r.path("shelter_id").asLong(),value(r,"shelter_name"),value(r,"shelter_address")});
                animals.add(new Object[]{r.path("id").asLong(),value(r,"created_at"),value(r,"apms_notice_no"),value(r,"species"),value(r,"breed"),value(r,"color"),value(r,"special_mark"),value(r,"description"),value(r,"happen_place"),value(r,"gender"),value(r,"neuter_status"),r.path("birth_year").isNull()?null:r.path("birth_year").asInt(),value(r,"status"),value(r,"notice_start_date"),value(r,"notice_end_date"),r.path("shelter_id").asLong()});
            }
            jdbc.batchUpdate("INSERT INTO shelters(id,created_at,care_reg_no,name,address) VALUES (?,now(),?,?,?) ON CONFLICT(id) DO NOTHING",shelters);
            jdbc.batchUpdate("INSERT INTO animals(id,created_at,api_source,apms_notice_no,species,breed,color,special_mark,description,happen_place,gender,neuter_status,birth_year,status,notice_start_date,notice_end_date,favorite_count,shelter_id) VALUES (?,?::timestamp,'APMS_ANIMAL',?,?,?,?,?,?,?,?,?,?,?,?::date,?::date,0,?)",animals);
        });
    }
    private static AnimalQueryService transactionalQuery(HikariDataSource source,KoreanSearchAnalyzer analyzer) {
        ProxyFactory proxy=new ProxyFactory(new PostgresqlAnimalQueryService(source,null,new AnimalMapper(),Optional.of(analyzer)));
        TransactionInterceptor interceptor=new TransactionInterceptor();interceptor.setTransactionManager(new JdbcTransactionManager(source));
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());proxy.addAdvice(interceptor);
        return (AnimalQueryService)proxy.getProxy();
    }
    private static Map<String,Object> concurrentReads(AnimalQueryService query) throws Exception {
        java.util.concurrent.ExecutorService threads=Executors.newFixedThreadPool(4);CountDownLatch start=new CountDownLatch(1);
        List<Future<List<Long>>> futures=new ArrayList<>();long begin=System.nanoTime();
        try {
            for(int worker=0;worker<4;worker++) futures.add(threads.submit(()->{
                start.await();List<Long> times=new ArrayList<>();
                for(int i=0;i<12;i++) { long now=System.nanoTime();query.searchAnimals(AnimalSearchRequest.builder().keyword(i%2==0?"흰색":"사람을 좋아").build(),RELEVANCE);times.add(elapsed(now)); }
                return times;
            }));
            start.countDown();List<Long> all=new ArrayList<>();for(Future<List<Long>> future:futures) all.addAll(future.get(90,TimeUnit.SECONDS));
            return Map.of("concurrency",4,"queries",all.size(),"elapsedMs",elapsed(begin),"latencyMs",distribution(all));
        } finally { threads.shutdownNow();threads.awaitTermination(10,TimeUnit.SECONDS); }
    }
    private static Map<String,Object> transactionalWrites(JdbcTemplate jdbc,HikariDataSource source,
            KoreanSearchAnalyzer analyzer,AnimalQueryService query,ConnectionMetrics connections) throws Exception {
        com.pawbridge.animalservice.search.SearchDocumentWriter documents=
                new com.pawbridge.animalservice.search.SearchDocumentWriter(source,Optional.of(analyzer));
        List<Long> ids=jdbc.queryForList("SELECT id FROM animals WHERE status IN ('NOTICE','PROTECT') ORDER BY id LIMIT 1000",Long.class);
        TransactionTemplate transaction=new TransactionTemplate(new JdbcTransactionManager(source));transaction.setTimeout(5);
        List<Map<String,Object>> phases=new ArrayList<>();
        for(int batchSize:List.of(1,10,50,1000)) {
            connections.reset();
            List<Long> transactionTimes=new ArrayList<>(),sourceTimes=new ArrayList<>(),documentTimes=new ArrayList<>();
            List<Long> phaseIds=batchSize==1000?ids:ids.subList(0,Math.min(500,ids.size()));
            int repetitions=batchSize==1000?5:1;
            long start=System.nanoTime();
            for(int repeat=0;repeat<repetitions;repeat++) {
            String description="동기검증 단계 "+batchSize+" 반복 "+repeat;
            for(int offset=0;offset<phaseIds.size();offset+=batchSize) {
                List<Long> batch=phaseIds.subList(offset,Math.min(offset+batchSize,phaseIds.size()));
                long begin=System.nanoTime();
                transaction.executeWithoutResult(status -> {
                    long sourceNanos=0,documentNanos=0;
                    for(Long id:batch) {
                        long tick=System.nanoTime();
                        jdbc.update("UPDATE animals SET description=? WHERE id=?",description,id);
                        sourceNanos+=System.nanoTime()-tick;
                        tick=System.nanoTime();documents.animal(id);documentNanos+=System.nanoTime()-tick;
                    }
                    sourceTimes.add(sourceNanos/1000);documentTimes.add(documentNanos/1000);
                });
                transactionTimes.add(elapsed(begin));
            }
            }
            long duration=elapsed(start);
            Map<String,Object> writeConnections=connections.snapshot();
            long matching=query.searchAnimals(AnimalSearchRequest.builder().keyword("동기검증").build(),RELEVANCE).getTotalElements();
            if(matching!=phaseIds.size()) throw new IllegalStateException("Committed writes must be searchable immediately");
            phases.add(Map.of("batchSize",batchSize,"rows",phaseIds.size()*repetitions,"elapsedMs",duration,
                    "rowsPerSecond",phaseIds.size()*repetitions*1000.0/Math.max(1,duration),"transactionWallMs",distribution(transactionTimes),
                    "sourceUpdateMicrosPerTransaction",distribution(sourceTimes),"analysisAndDocumentMicrosPerTransaction",distribution(documentTimes),
                    "immediatelySearchable",matching,"connections",writeConnections));
        }
        java.util.concurrent.ExecutorService threads=Executors.newFixedThreadPool(3);
        CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<List<Long>>> reads=new ArrayList<>();
            for(int reader=0;reader<2;reader++) reads.add(threads.submit(()->{
                start.await();List<Long> times=new ArrayList<>();
                for(int i=0;i<30;i++) {
                    long tick=System.nanoTime();
                    long count=query.searchAnimals(AnimalSearchRequest.builder().keyword("동기검증").build(),RELEVANCE).getTotalElements();
                    if(count!=ids.size()) throw new IllegalStateException("Concurrent search lost committed rows");
                    times.add(elapsed(tick));
                }
                return times;
            }));
            Future<Integer> writes=threads.submit(()->{
                start.await();int total=0;
                for(int offset=0;offset<ids.size();offset+=50) {
                    List<Long> batch=ids.subList(offset,Math.min(offset+50,ids.size()));
                    transaction.executeWithoutResult(status -> {
                        for(Long id:batch) {
                            jdbc.update("UPDATE animals SET description='동기검증 동시 갱신' WHERE id=?",id);documents.animal(id);
                        }
                    });total+=batch.size();
                }
                return total;
            });
            connections.reset();long begin=System.nanoTime();start.countDown();
            int written=writes.get(90,TimeUnit.SECONDS);List<Long> times=new ArrayList<>();
            for(Future<List<Long>> read:reads) times.addAll(read.get(90,TimeUnit.SECONDS));
            return Map.of("sequential",phases,"concurrent",Map.of("writerRows",written,"readers",2,
                    "successfulReads",times.size(),"elapsedMs",elapsed(begin),"readLatencyMs",distribution(times),"connections",connections.snapshot()),
                    "boundary","Real JDBC source + document transactions, not full HTTP/JPA/Outbox processing; transaction wall time includes connection acquisition and commit");
        } finally {threads.shutdownNow();threads.awaitTermination(10,TimeUnit.SECONDS);}
    }

    private static Map<String,Object> updateAvailability(JdbcTemplate jdbc,PostgresqlSearchProjector projector,AnimalQueryService query) throws Exception {
        // Keep the repair marker disjoint from the preceding synchronous-write vocabulary.
        int changed=jdbc.update("UPDATE animals SET description=left(coalesce(description,''),1950)||' pawbridgerepairprobe' WHERE id IN (SELECT id FROM animals WHERE status IN ('NOTICE','PROTECT') ORDER BY id LIMIT 500)");
        long currentTextStart=System.nanoTime();
        // Deliberately bypass normal transactional writers to exercise direct-SQL repair.
        boolean pendingRejected=false;
        try { query.searchAnimals(AnimalSearchRequest.builder().keyword("pawbridgerepairprobe").build(),RELEVANCE); }
        catch(SearchProjectionPendingException expected) { pendingRejected=true; }
        if(!pendingRejected) throw new IllegalStateException("Unprepared source must not produce partial results");
        long currentTextMs=elapsed(currentTextStart);
        AtomicBoolean finished=new AtomicBoolean();java.util.concurrent.ExecutorService thread=Executors.newSingleThreadExecutor();
        long start=System.nanoTime();int failures=0,normalReads=0;long lastPendingMs=-1,firstReadyMs=-1;List<Long> normalTimes=new ArrayList<>(),textTimes=new ArrayList<>();
        try {
            Future<Integer> worker=thread.submit(()->{ int total=0;
                try { for(int page=0;page<11;page++) { int rows=projector.refreshAnimals(50);total+=rows;if(rows==0)break;Thread.sleep(2000); }return total; }
                finally {finished.set(true);}
            });
            do {
                if(elapsed(start)>90000) throw new IllegalStateException("Availability probe timed out");
                try {
                    long textStart=System.nanoTime();
                    query.searchAnimals(AnimalSearchRequest.builder().keyword("흰색").build(),RELEVANCE);
                    textTimes.add(elapsed(textStart));
                    if(firstReadyMs<0) firstReadyMs=elapsed(start);
                } catch(SearchProjectionPendingException pending) { failures++;lastPendingMs=elapsed(start); }
                long normal=System.nanoTime();query.searchAnimals(new AnimalSearchRequest(),PageRequest.of(0,20));normalTimes.add(elapsed(normal));normalReads++;
                Thread.sleep(200);
            } while(!finished.get());
            int refreshed=worker.get(10,TimeUnit.SECONDS);
            long currentTextCount=query.searchAnimals(AnimalSearchRequest.builder().keyword("pawbridgerepairprobe").build(),RELEVANCE).getTotalElements();
            if(currentTextCount!=changed) throw new IllegalStateException("Repaired source text missing from search");
            Map<String,Object> result=new LinkedHashMap<>();
            result.put("changedRows",changed);result.put("refreshedRows",refreshed);
            result.put("observationElapsedMs",elapsed(start));result.put("pendingWindowMs",List.of(lastPendingMs,firstReadyMs));
            result.put("pendingResponses",failures);result.put("successfulTextReads",textTimes.size());
            result.put("textReadLatencyMs",distribution(textTimes));result.put("normalReads",normalReads);
            result.put("normalReadLatencyMs",distribution(normalTimes));result.put("pageSize",50);result.put("delayMs",2000);
            result.put("scenario","direct-sql-repair-not-normal-application-write");
            result.put("beforeWorkerRejected",pendingRejected);result.put("afterWorkerLatestTextCount",currentTextCount);result.put("beforeWorkerReadinessMs",currentTextMs);
            return result;
        } finally { thread.shutdownNow();thread.awaitTermination(10,TimeUnit.SECONDS); }
    }
    /** Explain the actual SQL/bindings emitted by the service, not a manually copied query. */
    private static Map<String,Object> plans(HikariDataSource source,KoreanSearchAnalyzer analyzer) throws Exception {
        List<BoundQuery> captured=new ArrayList<>();
        javax.sql.DataSource recording=new org.springframework.jdbc.datasource.AbstractDataSource() {
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                java.sql.Connection connection=source.getConnection();
                return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{
                            Object result=invoke(connection,method,args);
                            if(!method.getName().equals("prepareStatement")) return result;
                            String sql=(String)args[0];List<Binding> bindings=new ArrayList<>();
                            return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                                    new Class<?>[]{java.sql.PreparedStatement.class},(statement,operation,values)->{
                                        if(operation.getName().startsWith("set") && values!=null && values.length>=2
                                                && values[0] instanceof Integer) bindings.add(new Binding(operation,values.clone()));
                                        if(operation.getName().equals("executeQuery")) captured.add(new BoundQuery(sql,List.copyOf(bindings)));
                                        return invoke(result,operation,values);
                                    });
                        });
            }
            @Override public java.sql.Connection getConnection(String user,String password) throws java.sql.SQLException { return getConnection(); }
        };
        ProxyFactory proxy=new ProxyFactory(new PostgresqlAnimalQueryService(recording,null,new AnimalMapper(),Optional.of(analyzer)));
        TransactionInterceptor interceptor=new TransactionInterceptor();interceptor.setTransactionManager(new JdbcTransactionManager(recording));
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());proxy.addAdvice(interceptor);
        AnimalQueryService query=(AnimalQueryService)proxy.getProxy();
        Map<String,Object> result=new LinkedHashMap<>();
        for(String term:List.of("흰색","목걸이","귀 접힘")) {
            captured.clear();query.searchAnimals(AnimalSearchRequest.builder().keyword(term).build(),RELEVANCE);
            List<Map<String,Object>> statements=new ArrayList<>();
            for(BoundQuery bound:captured) {
                try(java.sql.Connection connection=source.getConnection();java.sql.PreparedStatement explain=
                        connection.prepareStatement("EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) "+bound.sql())) {
                    explain.setQueryTimeout(5);
                    for(Binding binding:bound.bindings()) {
                        try {invoke(explain,binding.method(),binding.values());}
                        catch(Throwable failure) {throw new IllegalStateException("Cannot replay query binding",failure);}
                    }
                    try(java.sql.ResultSet rows=explain.executeQuery()) {
                        rows.next();statements.add(Map.of("sql",bound.sql(),"plan",JSON.readTree(rows.getString(1))));
                    }
                }
            }
            result.put(term,statements);
        }
        return result;
    }
    private static Object invoke(Object target,java.lang.reflect.Method method,Object[] arguments) throws Throwable {
        try {return method.invoke(target,arguments);}
        catch(java.lang.reflect.InvocationTargetException failure) {throw failure.getCause();}
    }
    private record Binding(java.lang.reflect.Method method,Object[] values) {}
    private record BoundQuery(String sql,List<Binding> bindings) {}

    /** Bounded benchmark-only samples from Hikari's actual acquire/return events. */
    private static final class ConnectionMetrics implements com.zaxxer.hikari.metrics.IMetricsTracker {
        private static final int MAX_SAMPLES=10000;
        private final List<Long> usageMillis=new ArrayList<>(),acquireMicros=new ArrayList<>();
        private long timeouts,dropped;
        @Override public synchronized void recordConnectionUsageMillis(long value) {
            if(usageMillis.size()<MAX_SAMPLES) usageMillis.add(value);else dropped++;
        }
        @Override public synchronized void recordConnectionAcquiredNanos(long value) {
            if(acquireMicros.size()<MAX_SAMPLES) acquireMicros.add(value/1000);else dropped++;
        }
        @Override public synchronized void recordConnectionTimeout() {timeouts++;}
        synchronized void reset() {usageMillis.clear();acquireMicros.clear();timeouts=0;dropped=0;}
        synchronized Map<String,Object> snapshot() {
            return Map.of("borrowedMs",distribution(usageMillis),"acquireMicros",distribution(acquireMicros),
                    "timeouts",timeouts,"droppedSamples",dropped);
        }
    }

    private static Map<String,Object> distribution(List<Long> input) {
        if(input.isEmpty())return Map.of("samples",0);
        List<Long> values=new ArrayList<>(input);Collections.sort(values);
        return Map.of("samples",values.size(),"min",values.get(0),"p50",values.get((values.size()-1)/2),"p95",values.get((int)Math.ceil(values.size()*0.95)-1),"max",values.get(values.size()-1));
    }
    private static String value(JsonNode row,String field) { return row.path(field).isNull()||row.path(field).isMissingNode()?null:row.path(field).asText(); }
    private static void save(Map<String,Object> report,String path) throws java.io.IOException {
        JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of(path).toFile(),report);
    }
    private static long elapsed(long start) { return (System.nanoTime()-start)/1_000_000; }
}
