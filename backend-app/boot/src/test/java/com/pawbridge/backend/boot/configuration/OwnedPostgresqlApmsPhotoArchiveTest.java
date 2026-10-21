package com.pawbridge.backend.boot.configuration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.gallery.infrastructure.LostGalleryFeed;
import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

/** New P19 only. Root prepares the immutable seeded fixture; the runtime never runs DDL/reset/seed. */
@Tag("owned-postgresql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(90)
class OwnedPostgresqlApmsPhotoArchiveTest {
    private static final String FIXTURE_SHA = "6930ff2d0f3bcd0bd48bad24c16410ae3348ed1480c9284037b0318f15c3083e";
    private static final String BUCKET = "pawbridge-animal-originals";
    private static final Map<String,String> KEYS = Map.of("animals","id","shelters","id",
            "apms_photo_archive","animal_id,slot","apms_photo_scan","id");
    private static final Set<String> SOURCE = Set.of("animals","shelters");
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String,byte[]> objects = new ConcurrentHashMap<>();
    private final AtomicInteger puts = new AtomicInteger(), gets = new AtomicInteger(), externalChecks = new AtomicInteger();
    private final AtomicInteger downloads = new AtomicInteger();
    private final AtomicBoolean closedStorage = new AtomicBoolean(), badRecipe = new AtomicBoolean(), source404 = new AtomicBoolean();
    private final Map<ch.qos.logback.classic.Logger,ch.qos.logback.classic.Level> logLevels = new LinkedHashMap<>();
    private Settings settings;
    private HikariDataSource pool;
    private DriverManagerDataSource direct;
    private JdbcTemplate jdbc;
    private LocalContainerEntityManagerFactoryBean entityManagerFactory;
    private JpaTransactionManager manager;
    private PhotoArchiveStore store;
    private PhotoArchiveStore.Claim seededStale;
    private PhotoArchiveHttpClient http;
    private PhotoArchiveObjectStorage storage;
    private S3Client s3;
    private String sourcesBefore;
    private boolean nextAllowed, completedBody, unsettled;
    private int nextOrder = 1;
    private volatile CountDownLatch downloadEntered, downloadRelease;
    private final AtomicBoolean blockOnce = new AtomicBoolean();

    @BeforeAll void givenFreshExactP19__whenPreparing__thenGuardBeforeConstructingRealStoreAndWorker() {
        observed("P19 preparation", () -> {
            quiet(); settings = Settings.load();
            try (InputStream resource = getClass().getResourceAsStream("/owned-postgresql/apms-photo-archive-owned-schema.sql")) {
                check(resource != null && sha(resource.readAllBytes()).equals(FIXTURE_SHA), "Exact frozen P19 SQL is required");
            }
            direct = new DriverManagerDataSource(settings.url(),settings.username(),settings.password());
            var timeout = new Properties(); timeout.setProperty("connectTimeout","3"); timeout.setProperty("socketTimeout","5");
            direct.setConnectionProperties(timeout);
            var config = new HikariConfig(); config.setJdbcUrl(settings.url()); config.setUsername(settings.username()); config.setPassword(settings.password());
            config.setPoolName("owned-p19-photo-archive"); config.setMaximumPoolSize(2); config.setMinimumIdle(0);
            config.setInitializationFailTimeout(-1); config.setConnectionTimeout(2000); config.setValidationTimeout(1000);
            config.addDataSourceProperty("connectTimeout","3"); config.addDataSourceProperty("socketTimeout","5");
            pool = new HikariDataSource(config); jdbc = new JdbcTemplate(pool); jdbc.setQueryTimeout(5);
            verifyTarget();
            check(count("animals")==9 && count("shelters")==1 && count("apms_photo_archive")==1 && count("apms_photo_scan")==0,
                    "A new P19 requires exact synthetic source, one old archive observation and an empty cursor");
            sourcesBefore = sourceFingerprint(); released();
            // Reuse the existing shared writer definition, without an app/context copy or another production EMF.
            var writer = new AnimalJpaWriterConfiguration();
            entityManagerFactory = writer.animalCommandEntityManagerFactory(pool); entityManagerFactory.afterPropertiesSet();
            manager = writer.animalCommandJpaTransactionManager(Objects.requireNonNull(entityManagerFactory.getObject()),pool);
            manager.afterPropertiesSet();
            check(manager.getDataSource()==pool && manager.getDefaultTimeout()==15, "Use the unchanged existing Animal writer definition");
            store = new PhotoArchiveStore(jdbc,manager);
            http = mock(PhotoArchiveHttpClient.class);
            when(http.download(anyString())).thenAnswer(call -> {
                externalBoundary(); downloads.incrementAndGet();
                if (blockOnce.compareAndSet(true,false)) {
                    downloadEntered.countDown(); downloadRelease.await();
                    check(!Thread.currentThread().isInterrupted(), "Normal cooperative stop must not interrupt the admitted photo");
                }
                if (source404.get()) throw new PhotoArchiveFailure("SOURCE_HTTP_404",true);
                return ((String)call.getArgument(0)).getBytes(StandardCharsets.UTF_8);
            });
            when(http.optimize(any(byte[].class))).thenAnswer(call -> {
                externalBoundary(); byte[] bytes=call.getArgument(0);
                return new ArchivedPhoto(bytes,sha(bytes),sha(bytes),"image/png",1,1,badRecipe.get()?"x".repeat(65):"original-v1");
            });
            s3 = mock(S3Client.class);
            doAnswer(call -> {
                externalBoundary(); GetObjectRequest request=call.getArgument(0); check(BUCKET.equals(request.bucket()),"GET must use the existing Gallery bucket");
                gets.incrementAndGet(); byte[] bytes=objects.get(request.key());
                if (bytes==null) throw S3Exception.builder().statusCode(404).message("SYNTHETIC_MISSING").build();
                ResponseTransformer<GetObjectResponse,Object> transform=call.getArgument(1);
                try (var stream=AbortableInputStream.create(new ByteArrayInputStream(bytes))) {
                    return transform.transform(GetObjectResponse.builder().contentLength((long)bytes.length).contentType("image/png").build(),stream);
                }
            }).when(s3).getObject(any(GetObjectRequest.class),org.mockito.ArgumentMatchers.<ResponseTransformer<GetObjectResponse,Object>>any());
            when(s3.putObject(any(PutObjectRequest.class),any(RequestBody.class))).thenAnswer(call -> {
                externalBoundary(); PutObjectRequest request=call.getArgument(0); RequestBody body=call.getArgument(1);
                check(BUCKET.equals(request.bucket()) && "image/png".equals(request.contentType()), "PUT must retain Gallery bucket and MIME");
                try (InputStream input=body.contentStreamProvider().newStream()) {
                    byte[] bytes=input.readAllBytes(); check(request.key().equals("apms/photos/"+sha(bytes)+".png"),"Stored key must identify actual stored bytes");
                    check(objects.putIfAbsent(request.key(),bytes)==null,"Existing content objects must not be overwritten"); puts.incrementAndGet();
                }
                return PutObjectResponse.builder().build();
            });
            doAnswer(call -> { closedStorage.set(true); return null; }).when(s3).close();
            storage = new PhotoArchiveObjectStorage(s3,BUCKET);
            released(); nextAllowed=true;
        });
    }

    @BeforeEach void givenPriorSuccess__whenNextCaseStarts__thenFencePartialSelectionAndFailure(TestInfo info) {
        boolean prior=nextAllowed; nextAllowed=false; completedBody=false;
        observed("P19 prior contract", () -> {
            check(prior && !unsettled && info.getTestMethod().orElseThrow().getAnnotation(Order.class).value()==nextOrder,
                    "Partial selection or previous failure forbids later P19 writes"); released();
        });
    }
    @AfterEach void givenCaseEnded__whenChecking__thenSourceAndResourcesRemainOwned() {
        nextAllowed=false;
        observed("P19 case cleanup", () -> {
            released(); check(sourceFingerprint().equals(sourcesBefore),"Archive operations must not change source URLs, status or any source column");
            check(settings.marker().equals(jdbc.queryForObject("SELECT marker FROM public.pb_apms_photo_archive_fixture",String.class)),"P19 marker must remain unchanged");
            released(); check(completedBody,"A failed P19 body fences later writes"); nextOrder++; nextAllowed=true;
        });
    }
    @AfterAll void givenSettledOwnedWork__whenClosing__thenCloseOnlyOwnedResourcesAndRetainHistory() {
        try {
            observed("P19 final cleanup", () -> {
                check(!unsettled,"Unsettled work preserves owned resources and fails cleanup");
                if (pool!=null) released();
                if (http!=null) http.close();
                if (storage!=null) { storage.close(); check(closedStorage.get(),"Owned storage must close its inprocess client"); }
                if (entityManagerFactory!=null) { var factory=entityManagerFactory.getObject(); entityManagerFactory.destroy(); check(factory==null || !factory.isOpen(),"Owned writer factory must close"); }
                if (pool!=null) { released(); pool.close(); check(pool.isClosed(),"The owned P19 pool must close"); }
                System.out.println("APMS_PHOTO_ARCHIVE_OWNED completedContracts="+(nextOrder-1)+" callbacks="+externalChecks.get()+" puts="+puts.get()+" gets="+gets.get()+" allSourceChecksPassed="+(nextOrder==7 && nextAllowed)+" historyRetained=true");
            });
        } finally { if (!unsettled) logLevels.forEach(ch.qos.logback.classic.Logger::setLevel); }
    }

    @Test @Order(1)
    void givenTwoSlotsAndExcludedSources__whenDiscoveredRepeatedly__thenArchiveOnlyApmsSlotsWithoutChangingSource() {
        contract("two-slot discovery", () -> {
            seededStale=store.claim(300).orElseThrow();
            check(seededStale.animalId()==7 && seededStale.sourceUrl().equals("https://openapi.animal.go.kr/p19/old.png")
                    && !store.complete(seededStale,photo("direct"),3600),
                    "Seeded old observation must fail the actual current-URL predicate before any discovery catches up");
            store.discover(50); store.discover(50);
            check(count("apms_photo_archive")==7 && count("apms_photo_scan")==1,"Exact two-slot APMS fixture must be discovered once");
            check(jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal.apms_photo_archive WHERE animal_id IN(3,4,5,6)",Long.class)==0,
                    "Manual, other-source, missing APMS id and absent photos must not be archived");
            check(jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal.apms_photo_archive WHERE animal_id=1",Long.class)==2,
                    "Both photo slots are independently discovered");
            check("https://openapi.animal.go.kr/p19/cat.png".equals(jdbc.queryForObject("SELECT source_url FROM pawbridge_animal.apms_photo_archive WHERE animal_id=2 AND slot=1",String.class))
                    && jdbc.queryForObject("SELECT generation FROM pawbridge_animal.apms_photo_archive WHERE animal_id=7 AND slot=1",Long.class)==2
                    && jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal.apms_photo_archive WHERE animal_id<>7 AND generation<>1",Long.class)==0,
                    "Trim observations and advance only the stale observed generation without rewriting source URLs");
            deferAll();
        });
    }

    @Test @Order(2)
    void givenRealWorkerAndInprocessObjects__whenTwoSlotsComplete__thenReadySlotOneFeedsExistingGalleryOutsideDatabaseWaits() {
        contract("READY to existing Gallery", () -> {
            due(1,1); due(1,2); due(2,1); int before=puts.get();
            var result=worker(3).runOnce();
            check(result.completed()==3 && result.failed()==0 && puts.get()==before+3,"Real worker must publish all three photos, including the whitespace-normalized second Animal");
            check("READY".equals(state(1,1)) && "READY".equals(state(1,2)) && "READY".equals(state(2,1)) && externalChecks.get()>=15,
                    "Download, optimize and object callbacks must all observe no database transaction or active borrow");
            var reads=new TransactionTemplate(manager); reads.setReadOnly(true); reads.setTimeout(15);
            var feed=new LostGalleryFeed(jdbc,mapper,key -> "https://synthetic.gallery.invalid/"+BUCKET+"/"+key,reads,reads);
            var snapshot=feed.snapshot(null); var body=mapper.readTree(snapshot.body());
            var snapshotIds=new ArrayList<Long>();
            body.get("records").forEach(record -> snapshotIds.add(record.get("id").asLong()));
            check(body.get("complete").asBoolean() && body.get("count").asInt()==2
                    && snapshotIds.equals(List.of(1L,2L)) && body.get("photos").size()==2,
                    "V1 Gallery must consume both READY slot1 Animals including the unchanged whitespace source, without slot2 duplication");
            var streamIds=new ArrayList<Long>();
            feed.streamEntries(entry -> streamIds.add(((Number)entry.record().get("id")).longValue()));
            check(streamIds.equals(List.of(1L,2L)),"V2 streaming must consume the same exact ordered Animal IDs as V1");
            check(feed.snapshot(snapshot.etag()).body()==null,"Unchanged existing Gallery ETag must remain stable");
        });
    }

    @Test @Order(3)
    void givenStaleObservedSourceAndExpiredLease__whenClaimsAreReplaced__thenCurrentSourceGenerationAndTokenRejectStaleWork() {
        contract("source and lease fences", () -> {
            // Case1 checked the seeded old observation against the unchanged current Animal.
            // This verifies current-URL mismatch defense, not a concurrent Animal UPDATE actor.
            var old=seededStale; due(7,1);
            var current=store.claim(300).orElseThrow();
            check(current.animalId()==7 && current.generation()>old.generation() && !current.token().equals(old.token()),"Real discovery must advance generation and revoke the old lease");
            check(!store.complete(old,photo("direct"),3600) && !store.retry(old,"STALE",60),"Old generation/token cannot complete or retry");
            jdbc.update("UPDATE pawbridge_animal.apms_photo_archive SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE animal_id=7 AND slot=1");
            var replacement=store.claim(300).orElseThrow();
            check(!replacement.token().equals(current.token()) && !store.complete(current,photo("direct"),3600)
                    && !store.retry(current,"EXPIRED",60),"Expired worker must lose complete and retry rights");
            check(store.complete(replacement,photo("direct"),3600),"Current replacement alone may publish READY");
        });
    }

    @Test @Order(4)
    void givenLockedPhotoAndTwoClaimers__whenClaimed__thenSkipLockedAndOnlyOneTokenOwnsThePhoto() {
        contract("physical claim ownership", () -> {
            deferAll(); due(8,1);
            try (Connection blocker=direct.getConnection()) {
                check(pid(blocker)!=jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class),"The lock owner and writer pool must use distinct physical PostgreSQL sessions");
                blocker.setAutoCommit(false);
                try (PreparedStatement lock=blocker.prepareStatement("SELECT animal_id FROM pawbridge_animal.apms_photo_archive WHERE animal_id=8 AND slot=1 FOR UPDATE")) {
                    lock.setQueryTimeout(3); try(var row=lock.executeQuery()) { check(row.next(),"The exact owned row must be locked"); }
                    check(store.claim(300).isEmpty(),"Actual PostgreSQL claim must skip a row locked by another physical session");
                } finally { blocker.rollback(); }
            }
            var start=new CountDownLatch(1); var executor=Executors.newFixedThreadPool(2,task -> {var thread=new Thread(task,"p19-claim");thread.setDaemon(true);return thread;});
            try {
                Callable<Optional<PhotoArchiveStore.Claim>> task=() -> {start.await();return store.claim(300);};
                var first=executor.submit(task); var second=executor.submit(task); start.countDown();
                var winners=List.of(first.get(8,TimeUnit.SECONDS),second.get(8,TimeUnit.SECONDS)).stream().flatMap(Optional::stream).toList();
                check(winners.size()==1 && winners.get(0).animalId()==8,"Two real concurrent claim calls must yield one owner token");
                check(store.complete(winners.get(0),photo("claim"),3600),"Winner token may complete");
            } finally { start.countDown(); executor.shutdown(); if(!executor.awaitTermination(8,TimeUnit.SECONDS)) {unsettled=true;throw new AssertionError("Owned claim tasks remain unsettled");} }
        });
    }

    @Test @Order(5)
    void givenDiscoveryRollbackAndObjectSavedBeforeCompletionFailure__whenRetried__thenCursorRollbackAndSameKeyRecoveryPreserveObjects() {
        contract("rollback and archive retry", () -> {
            jdbc.update("UPDATE pawbridge_animal.apms_photo_scan SET sweep_cursor=9 WHERE id=1");
            jdbc.update("UPDATE pawbridge_animal.apms_photo_archive SET source_url=? WHERE animal_id=9 AND slot=1","https://openapi.animal.go.kr/p19/stale-before-rollback.png");
            long generation=jdbc.queryForObject("SELECT generation FROM pawbridge_animal.apms_photo_archive WHERE animal_id=9 AND slot=1",Long.class);
            String before=archiveFingerprint(); RuntimeException exact=new RuntimeException("P19_ROLLBACK");
            var writes=new TransactionTemplate(manager); writes.setTimeout(15);
            try { writes.executeWithoutResult(status -> {
                store.discover(1);
                check(jdbc.queryForObject("SELECT generation FROM pawbridge_animal.apms_photo_archive WHERE animal_id=9 AND slot=1",Long.class)==generation+1
                        && jdbc.queryForObject("SELECT sweep_cursor FROM pawbridge_animal.apms_photo_scan WHERE id=1",Long.class)==8,
                        "The real archive generation and discovery cursor must both change before forced rollback");
                throw exact;
            });throw new AssertionError("Owned rollback probe must throw"); }
            catch(RuntimeException failure) {check(failure==exact,"Only the exact rollback sentinel is accepted");}
            check(before.equals(archiveFingerprint()),"Discovery archive and cursor writes must roll back together under the existing JPA manager");
            store.discover(50);
            deferAll(); due(8,1); badRecipe.set(true); int putsBefore=puts.get();
            // Deliberately invalid internal optimizer metadata exercises a real PostgreSQL varchar failure after object save.
            // This is a synthetic DB failure boundary, not a provider response accepted by the real optimizer adapter.
            var worker=worker(1); var failed=worker.runOnce(); badRecipe.set(false);
            check(failed.failed()==1 && "RETRY".equals(state(8,1)) && puts.get()==putsBefore+1,"Object save must survive a real DB completion failure and leave RETRY");
            String key="apms/photos/"+sha("https://openapi.animal.go.kr/p19/retry.png".getBytes(StandardCharsets.UTF_8))+".png";
            check(objects.containsKey(key) && jdbc.queryForObject("SELECT attempts FROM pawbridge_animal.apms_photo_archive WHERE animal_id=8 AND slot=1",Integer.class)==1,
                    "Failed completion must keep the uploaded object and increment retry attempts");
            due(8,1); var success=worker.runOnce();
            check(success.completed()==1 && "READY".equals(state(8,1)) && puts.get()==putsBefore+1,"Same content retry must reuse the existing object via readback without PUT overwrite");
            due(8,1); source404.set(true); var slow=worker.runOnce(); source404.set(false);
            check(slow.failed()==1 && "RETRY".equals(state(8,1)) && objects.containsKey(key)
                    && key.equals(jdbc.queryForObject("SELECT object_key FROM pawbridge_animal.apms_photo_archive WHERE animal_id=8 AND slot=1",String.class))
                    && Boolean.TRUE.equals(jdbc.queryForObject("SELECT next_attempt_at>clock_timestamp()+INTERVAL '23 hours' FROM pawbridge_animal.apms_photo_archive WHERE animal_id=8 AND slot=1",Boolean.class)),
                    "Slow source failure must retain successful metadata/object and use the original long retry class");
            badRecipe.set(false); source404.set(false);
        });
    }

    @Test @Order(6)
    void givenInflightScheduledPhoto__whenCooperativeStopRequested__thenFinishCurrentPhotoWithoutClaimingTheSecondSlot() {
        contract("actual cooperative schedule stop", () -> {
            deferAll(); due(9,1); due(9,2); var properties=properties(2); properties.setInitialDelayMs(0); properties.setIntervalMs(1000);
            var worker=new PhotoArchiveWorker(store,http,storage,properties); var schedule=new PhotoArchiveSchedule(worker,properties,8);
            downloadEntered=new CountDownLatch(1); downloadRelease=new CountDownLatch(1); blockOnce.set(true);
            var stopped=new CountDownLatch(1); var failure=new AtomicReference<Throwable>();
            var closer=new Thread(() -> {try {schedule.stop();}catch(Throwable problem){failure.set(problem);}finally{stopped.countDown();}},"p19-normal-stop"); closer.setDaemon(true);
            try {
                schedule.start(); check(downloadEntered.await(5,TimeUnit.SECONDS),"Actual scheduler must reach the inprocess photo boundary"); released();
                int downloadsBefore=downloads.get(); closer.start();
                var executor=(ScheduledThreadPoolExecutor)ReflectionTestUtils.getField(schedule,"executor");
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while(!executor.isShutdown() && System.nanoTime()<until) java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
                check(executor.isShutdown() && !stopped.await(100,TimeUnit.MILLISECONDS),"Stop must block while the admitted photo is in flight");
                check(!closedStorage.get(),"Dependent storage must remain open during the admitted photo");
                downloadRelease.countDown(); check(stopped.await(8,TimeUnit.SECONDS) && failure.get()==null && executor.isTerminated(),"Actual scheduler must terminate cooperatively within this observation");
                check(!schedule.isRunning() && downloads.get()==downloadsBefore && "READY".equals(state(9,1)) && "PENDING".equals(state(9,2)),
                        "The admitted photo completes but stop forbids another claim and download");
            } finally {
                downloadRelease.countDown();
                if(closer.getState()==Thread.State.NEW) closer.start();
                if(closer.getState()!=Thread.State.NEW && !awaitCloser(closer,9)) unsettled=true;
                var executor=(ScheduledThreadPoolExecutor)ReflectionTestUtils.getField(schedule,"executor");
                if(executor!=null && !executor.isTerminated()) unsettled=true;
                check(!unsettled,"Unsettled scheduler forbids later database access and resource shutdown");
            }
        });
    }

    private PhotoArchiveWorker worker(int limit) { return new PhotoArchiveWorker(store,http,storage,properties(limit)); }
    private PhotoArchiveProperties properties(int limit) {
        var properties=new PhotoArchiveProperties(); properties.setBucket(BUCKET);properties.setOptimizerUrl(URI.create("https://synthetic.optimizer.invalid/internal/photos/optimize"));
        properties.setInternalApiKey("p19-synthetic-only"); properties.setMaxPhotos(limit); properties.setScanSize(50);properties.setMaxRunSeconds(30); properties.validate();return properties;
    }
    private static ArchivedPhoto photo(String text) {byte[] bytes=text.getBytes(StandardCharsets.UTF_8);return new ArchivedPhoto(bytes,sha(bytes),sha(bytes),"image/png",1,1,"original-v1");}
    private void deferAll() {jdbc.update("UPDATE pawbridge_animal.apms_photo_archive SET next_attempt_at=clock_timestamp()+INTERVAL '2 days'");}
    private void due(long animal,int slot) {jdbc.update("UPDATE pawbridge_animal.apms_photo_archive SET next_attempt_at=clock_timestamp()-INTERVAL '1 second' WHERE animal_id=? AND slot=?",animal,slot);}
    private String state(long animal,int slot) {return jdbc.queryForObject("SELECT state FROM pawbridge_animal.apms_photo_archive WHERE animal_id=? AND slot=?",String.class,animal,slot);}
    private long count(String table) {check(KEYS.containsKey(table),"Only fixed P19 tables may be counted");return jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal."+table,Long.class);}
    private String fingerprint(String table) {check(KEYS.containsKey(table),"Only fixed P19 tables may be fingerprinted");return jdbc.queryForObject("SELECT count(*)::text||':'||md5(COALESCE(string_agg(row_to_json(t)::text,',' ORDER BY "+KEYS.get(table)+"),'')) FROM pawbridge_animal."+table+" t",String.class);}
    private String sourceFingerprint() {return fingerprint("animals")+"|"+fingerprint("shelters");}
    private String archiveFingerprint() {return fingerprint("apms_photo_archive")+"|"+fingerprint("apms_photo_scan");}
    private void externalBoundary() {released(); externalChecks.incrementAndGet();check(!closedStorage.get(),"No callback may run after owned storage closure");}
    private void released() {
        check(!unsettled,"Unsettled P19 work fences resource access");
        boolean returned=!TransactionSynchronizationManager.isActualTransactionActive() && !TransactionSynchronizationManager.hasResource(pool)
                && (pool==null || (pool.getHikariPoolMXBean().getActiveConnections()==0 && pool.getHikariPoolMXBean().getThreadsAwaitingConnection()==0));
        if(!returned) unsettled=true; check(returned,"No writer transaction or borrowed connection may survive an external wait or contract boundary");
    }
    private static boolean awaitCloser(Thread thread,int seconds) {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);boolean interrupted=false;try{while(thread.isAlive()){long left=until-System.nanoTime();if(left<=0)return false;try{TimeUnit.NANOSECONDS.timedJoin(thread,left);}catch(InterruptedException ignored){interrupted=true;}}return true;}finally{if(interrupted)Thread.currentThread().interrupt();}}
    private void contract(String phase,Checked body) {observed(phase,()->{body.run();completedBody=true;});}
    private static void observed(String phase,Checked body) {try{body.run();}catch(AssertionError failure){throw failure;}catch(Throwable failure){throw new AssertionError("Owned P19 observation failed: "+phase+"; type="+failure.getClass().getName());}}
    private static void check(boolean condition,String fixed) {if(!condition)throw new AssertionError(fixed);}
    private interface Checked {void run() throws Exception;}
    private static String sha(byte[] bytes) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception impossible){throw new AssertionError("SHA-256 unavailable");}}
    private static int pid(Connection connection) throws Exception {try(var statement=connection.createStatement()){statement.setQueryTimeout(3);try(var row=statement.executeQuery("SELECT pg_backend_pid()")){check(row.next(),"Physical PostgreSQL PID is required");return row.getInt(1);}}}
    private void quiet() {for(String name:List.of("com.zaxxer.hikari","org.springframework.jdbc","org.springframework.orm.jpa","org.hibernate","com.pawbridge.backend.animal.internal.photoarchive")){var logger=(ch.qos.logback.classic.Logger)LoggerFactory.getLogger(name);logLevels.put(logger,logger.getLevel());logger.setLevel(ch.qos.logback.classic.Level.OFF);}}

    // P18 target/settings guard below is reused with only P19 names, marker, schema counts and fixed port.

    private void verifyTarget() {
        Map<String,Object> id=jdbc.queryForMap("SELECT current_database() db,current_user actor,session_user session,current_setting('server_version_num') version,(SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()) owner");
        check(settings.database().equals(id.get("db")) && settings.username().equals(id.get("actor")) && settings.username().equals(id.get("session"))
                && settings.owner().equals(id.get("owner")) && "170011".equals(id.get("version")),"Only the exact new P19 PostgreSQL17.11 target is accepted");
        check(settings.marker().equals(jdbc.queryForObject("SELECT marker FROM public.pb_apms_photo_archive_fixture",String.class)),"Exact P19 ownership marker is mandatory");
        check(Boolean.TRUE.equals(jdbc.queryForObject("SELECT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit AND NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=pg_roles.oid) FROM pg_roles WHERE rolname=current_user",Boolean.class)),"P19 writer cannot inherit owner or admin capabilities");
        check(Boolean.TRUE.equals(jdbc.queryForObject("SELECT NOT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit FROM pg_roles WHERE rolname=?",Boolean.class,settings.owner()))
                && Boolean.FALSE.equals(jdbc.queryForObject("SELECT pg_has_role(current_user,?,'MEMBER')",Boolean.class,settings.owner())),"Separate owner remains NOLOGIN and inaccessible");
        check(Boolean.FALSE.equals(jdbc.queryForObject("SELECT has_database_privilege(current_user,current_database(),'CREATE,TEMP')",Boolean.class)),"Runtime database and TEMP DDL are forbidden");
        for(String schema:List.of("public","pawbridge_animal")) check(Boolean.TRUE.equals(jdbc.queryForObject("SELECT has_schema_privilege(current_user,?,'USAGE') AND NOT has_schema_privilege(current_user,?,'CREATE')",Boolean.class,schema,schema)),"Runtime schema creation is forbidden");
        check(jdbc.query("SELECT tablename FROM pg_tables WHERE schemaname='pawbridge_animal' ORDER BY tablename",(r,n)->r.getString(1)).equals(KEYS.keySet().stream().sorted().toList()),"P19 accepts exactly four application tables");
        check(jdbc.queryForObject("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname !~ '^pg_' AND n.nspname<>'information_schema' AND c.relkind IN('r','p','v','m','f')",Long.class)==5,"Only four application tables and one marker may exist");
        for(String table:KEYS.keySet()) for(String right:List.of("SELECT","INSERT","UPDATE","DELETE","TRUNCATE","REFERENCES","TRIGGER")) {
            boolean allowed=right.equals("SELECT") || (!SOURCE.contains(table) && Set.of("INSERT","UPDATE").contains(right));
            check(Boolean.valueOf(allowed).equals(jdbc.queryForObject("SELECT has_table_privilege(current_user,?,?)",Boolean.class,"pawbridge_animal."+table,right)),"Exact source-read/archive-write ACL is required");
            if(!allowed && Set.of("INSERT","UPDATE","REFERENCES").contains(right)) check(Boolean.FALSE.equals(jdbc.queryForObject("SELECT has_any_column_privilege(current_user,?,?)",Boolean.class,"pawbridge_animal."+table,right)),"Column grants cannot bypass denied writes");
        }
        check(Boolean.TRUE.equals(jdbc.queryForObject("SELECT has_table_privilege(current_user,'public.pb_apms_photo_archive_fixture','SELECT') AND NOT has_table_privilege(current_user,'public.pb_apms_photo_archive_fixture','INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')",Boolean.class)),"Marker remains SELECT-only");
        check(jdbc.query("SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='pawbridge_animal' AND c.relkind='S' ORDER BY c.relname",(r,n)->r.getString(1)).equals(List.of("animals_id_seq","shelters_id_seq")),"Only original source identity sequences exist");
        for(String sequence:List.of("animals_id_seq","shelters_id_seq")) check(Boolean.FALSE.equals(jdbc.queryForObject("SELECT has_sequence_privilege(current_user,?,'USAGE,SELECT,UPDATE')",Boolean.class,"pawbridge_animal."+sequence)),"Runtime archive role cannot advance source identities");
        check(jdbc.queryForObject("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN('public','pawbridge_animal') AND c.relkind IN('r','S') AND pg_get_userbyid(c.relowner)<>?",Long.class,settings.owner())==0,"Every fixture object retains its separate owner");
    }

    private record Settings(String url,String database,String username,String password,String marker) {
        String owner() { return database.replace("pb_apms_photo_archive_owned_", "pb_apms_photo_archive_owner_"); }
        static Settings load() throws Exception {
            check("P19_PG_SETTINGS".equals(System.getenv("PAWBRIDGE_OWNED_SETTINGS_ENV")), "Only the explicit P19 selector is accepted");
            String path = System.getenv("P19_PG_SETTINGS"); check(path != null && !path.isBlank(), "P19_PG_SETTINGS is required without fallback or skip");
            Path file = Path.of(path), expected = Path.of("/private/tmp/pawbridge-owned-pg-night-20261020/apms-photo-archive-trim-green/apms-photo-archive.properties");
            check(file.isAbsolute() && file.normalize().equals(file) && file.equals(expected) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file), "Only the exact new P19 regular settings file is accepted");
            for (Path parent=file.getParent();parent!=null;parent=parent.getParent()) check(!Files.isSymbolicLink(parent), "Settings ancestors may not be symlinks");
            check(Files.getPosixFilePermissions(file,LinkOption.NOFOLLOW_LINKS).equals(Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE))
                    && ((Number)Files.getAttribute(file,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).intValue()==1 && System.getProperty("user.name").equals(Files.getOwner(file).getName())
                    && Files.getOwner(file).equals(Files.getOwner(file.getParent())), "P19 settings must be current-owner single-link0600");
            check(Files.getPosixFilePermissions(file.getParent()).equals(Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,PosixFilePermission.OWNER_EXECUTE)), "The new P19 private directory must remain0700");
            Properties v = new Properties(); try(InputStream in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) { v.load(in); }
            check(v.stringPropertyNames().equals(Set.of("jdbcUrl","database","username","password","marker")), "Exactly five private P19 settings are accepted");
            String db=required(v,"database"),url=required(v,"jdbcUrl"),user=required(v,"username"),marker=required(v,"marker");
            check(db.matches("pb_apms_photo_archive_owned_[0-9a-f]{12}") && url.equals("jdbc:postgresql://127.0.0.1:60441/"+db)
                    && user.equals(db.replace("pb_apms_photo_archive_owned_","pb_apms_photo_archive_writer_")) && marker.matches("[0-9a-f]{32}"), "P19 target/role/marker must identify only its new loopback fixture");
            Path state=file.getParent().resolve("state.json");
            check(Files.isRegularFile(state,LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(state)
                    && Files.getPosixFilePermissions(state,LinkOption.NOFOLLOW_LINKS).equals(Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE))
                    && ((Number)Files.getAttribute(state,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).intValue()==1
                    && Files.getOwner(state).equals(Files.getOwner(file)),"New P19 state must be private and owned");
            var claim=new ObjectMapper().readTree(Files.readAllBytes(state));
            check(db.equals(claim.path("database").asText()) && user.equals(claim.path("writer").asText())
                    && marker.equals(claim.path("marker").asText()) && "apms-photo-archive-v1".equals(claim.path("purpose").asText())
                    && FIXTURE_SHA.equals(claim.path("schema_sha256").asText()) && claim.path("schemaInitialized").asBoolean()
                    && claim.path("port").asInt()==60441
                    && "b1719e47b8c3ff8c334e9df96c86b4f36478eb72022b8e19278d75c9f8a7187c".equals(claim.path("containerId").asText())
                    && "sha256:cf134a767f474095eeba57e0117be8e568e011a63f33fbf252f14c9b760f8e6f".equals(claim.path("image").asText())
                    && "08b97b84-e0b5-4c6f-a5f8-3d51ec4a2f89".equals(claim.path("owner").asText()),
                    "P19 claim must bind the new target, frozen SQL and the approved container identity; no historical fixture fallback");
            return new Settings(url,db,user,required(v,"password"),marker);
        }
        private static String required(Properties v,String key) { String value=v.getProperty(key);check(value!=null&&!value.isBlank(),"Every P19 setting is mandatory");return value; }
        @Override public String toString() { return "OwnedApmsSettings[redacted]"; }
    }
}
