package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.gallery.infrastructure.LostGalleryFeed
import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.ArchivedPhoto
import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveFailure
import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveHttpClient
import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveObjectStorage
import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveProperties
import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveStore
import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveWorker
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import jakarta.persistence.EntityManagerFactory
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.core.sync.ResponseTransformer
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.s3.model.S3Exception
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.sql.Connection
import java.util.HexFormat
import java.util.Objects
import java.util.Properties
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/** New P19 only. Root prepares the immutable seeded fixture; the runtime never runs DDL/reset/seed. */
@Tag("owned-postgresql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(90)
class OwnedPostgresqlApmsPhotoArchiveTest {
    private val mapper = ObjectMapper()
    private val objects: MutableMap<String, ByteArray> = ConcurrentHashMap()
    private val puts = AtomicInteger()
    private val gets = AtomicInteger()
    private val externalChecks = AtomicInteger()
    private val downloads = AtomicInteger()
    private val closedStorage = AtomicBoolean()
    private val badRecipe = AtomicBoolean()
    private val source404 = AtomicBoolean()
    private val logLevels = LinkedHashMap<ch.qos.logback.classic.Logger, ch.qos.logback.classic.Level?>()
    private lateinit var settings: Settings
    private var pool: HikariDataSource? = null
    private lateinit var direct: DriverManagerDataSource
    private lateinit var jdbc: JdbcTemplate
    private var entityManagerFactory: LocalContainerEntityManagerFactoryBean? = null
    private lateinit var manager: JpaTransactionManager
    private lateinit var store: PhotoArchiveStore
    private lateinit var seededStale: PhotoArchiveStore.Claim
    private var http: PhotoArchiveHttpClient? = null
    private var storage: PhotoArchiveObjectStorage? = null
    private lateinit var s3: S3Client
    private lateinit var sourcesBefore: String
    private var nextAllowed = false
    private var completedBody = false
    private var unsettled = false
    private var nextOrder = 1

    @Volatile
    private var downloadEntered: CountDownLatch? = null

    @Volatile
    private var downloadRelease: CountDownLatch? = null
    private val blockOnce = AtomicBoolean()

    @BeforeAll
    fun givenFreshExactP19__whenPreparing__thenGuardBeforeConstructingRealStoreAndWorker() {
        observed("P19 preparation") {
            quiet()
            settings = Settings.load()
            javaClass.getResourceAsStream("/owned-postgresql/apms-photo-archive-owned-schema.sql").use { resource ->
                check(resource != null && sha(resource.readAllBytes()) == FIXTURE_SHA, "Exact frozen P19 SQL is required")
            }
            direct = DriverManagerDataSource(settings.url, settings.username, settings.password)
            val timeout = Properties()
            timeout.setProperty("connectTimeout", "3")
            timeout.setProperty("socketTimeout", "5")
            direct.setConnectionProperties(timeout)
            val config = HikariConfig()
            config.jdbcUrl = settings.url
            config.username = settings.username
            config.password = settings.password
            config.poolName = "owned-p19-photo-archive"
            config.maximumPoolSize = 2
            config.minimumIdle = 0
            config.initializationFailTimeout = -1
            config.connectionTimeout = 2000
            config.validationTimeout = 1000
            config.addDataSourceProperty("connectTimeout", "3")
            config.addDataSourceProperty("socketTimeout", "5")
            val source = HikariDataSource(config)
            pool = source
            jdbc = JdbcTemplate(source)
            jdbc.queryTimeout = 5
            verifyTarget()
            check(
                count("animals") == 9L && count("shelters") == 1L && count("apms_photo_archive") == 1L &&
                    count("apms_photo_scan") == 0L,
                "A new P19 requires exact synthetic source, one old archive observation and an empty cursor",
            )
            sourcesBefore = sourceFingerprint()
            released()
            // Reuse the existing shared writer definition, without an app/context copy or another production EMF.
            val writer = AnimalJpaWriterConfiguration()
            val factoryBean = writer.animalCommandEntityManagerFactory(source, "false")
            entityManagerFactory = factoryBean
            factoryBean.afterPropertiesSet()
            manager = writer.animalCommandJpaTransactionManager(Objects.requireNonNull<EntityManagerFactory>(factoryBean.getObject()), source)
            manager.afterPropertiesSet()
            check(manager.dataSource === source && manager.defaultTimeout == 15, "Use the unchanged existing Animal writer definition")
            store = PhotoArchiveStore(jdbc, manager)
            val client = mockk<PhotoArchiveHttpClient>()
            http = client
            every { client.download(any()) } answers {
                val url = firstArg<String>()
                externalBoundary()
                downloads.incrementAndGet()
                if (blockOnce.compareAndSet(true, false)) {
                    downloadEntered!!.countDown()
                    downloadRelease!!.await()
                    check(!Thread.currentThread().isInterrupted, "Normal cooperative stop must not interrupt the admitted photo")
                }
                if (source404.get()) {
                    throw PhotoArchiveFailure("SOURCE_HTTP_404", true)
                }
                url.toByteArray(StandardCharsets.UTF_8)
            }
            every { client.optimize(any()) } answers {
                externalBoundary()
                val bytes = firstArg<ByteArray>()
                ArchivedPhoto(bytes, sha(bytes), sha(bytes), "image/png", 1, 1, if (badRecipe.get()) "x".repeat(65) else "original-v1")
            }
            justRun { client.close() }
            s3 = mockk<S3Client>()
            every {
                s3.getObject(
                    any<GetObjectRequest>(),
                    any<ResponseTransformer<GetObjectResponse, Any>>(),
                )
            } answers {
                externalBoundary()
                val request = firstArg<GetObjectRequest>()
                check(BUCKET == request.bucket(), "GET must use the existing Gallery bucket")
                gets.incrementAndGet()
                val bytes = objects[request.key()]
                if (bytes == null) {
                    throw S3Exception.builder().statusCode(404).message("SYNTHETIC_MISSING").build()
                }
                val transform = secondArg<ResponseTransformer<GetObjectResponse, Any>>()
                AbortableInputStream.create(ByteArrayInputStream(bytes)).use { stream ->
                    transform.transform(
                        GetObjectResponse.builder().contentLength(bytes.size.toLong()).contentType("image/png").build(),
                        stream,
                    )
                }
            }
            every { s3.putObject(any<PutObjectRequest>(), any<RequestBody>()) } answers {
                externalBoundary()
                val request = firstArg<PutObjectRequest>()
                val body = secondArg<RequestBody>()
                check(BUCKET == request.bucket() && "image/png" == request.contentType(), "PUT must retain Gallery bucket and MIME")
                body.contentStreamProvider().newStream().use { input: InputStream ->
                    val bytes = input.readAllBytes()
                    check(request.key() == "apms/photos/" + sha(bytes) + ".png", "Stored key must identify actual stored bytes")
                    check(objects.putIfAbsent(request.key(), bytes) == null, "Existing content objects must not be overwritten")
                    puts.incrementAndGet()
                }
                PutObjectResponse.builder().build()
            }
            every { s3.close() } answers {
                closedStorage.set(true)
            }
            storage = PhotoArchiveObjectStorage(s3, BUCKET)
            released()
            nextAllowed = true
        }
    }

    @BeforeEach
    fun givenPriorSuccess__whenNextCaseStarts__thenFencePartialSelectionAndFailure(info: TestInfo) {
        val prior = nextAllowed
        nextAllowed = false
        completedBody = false
        observed("P19 prior contract") {
            check(
                prior && !unsettled && info.testMethod.orElseThrow().getAnnotation(Order::class.java).value == nextOrder,
                "Partial selection or previous failure forbids later P19 writes",
            )
            released()
        }
    }

    @AfterEach
    fun givenCaseEnded__whenChecking__thenSourceAndResourcesRemainOwned() {
        nextAllowed = false
        observed("P19 case cleanup") {
            released()
            check(sourceFingerprint() == sourcesBefore, "Archive operations must not change source URLs, status or any source column")
            check(
                settings.marker == jdbc.queryForObject("SELECT marker FROM public.pb_apms_photo_archive_fixture", String::class.java),
                "P19 marker must remain unchanged",
            )
            released()
            check(completedBody, "A failed P19 body fences later writes")
            nextOrder++
            nextAllowed = true
        }
    }

    @AfterAll
    fun givenSettledOwnedWork__whenClosing__thenCloseOnlyOwnedResourcesAndRetainHistory() {
        try {
            observed("P19 final cleanup") {
                check(!unsettled, "Unsettled work preserves owned resources and fails cleanup")
                if (pool != null) {
                    released()
                }
                http?.close()
                val ownedStorage = storage
                if (ownedStorage != null) {
                    ownedStorage.close()
                    check(closedStorage.get(), "Owned storage must close its inprocess client")
                }
                val factoryBean = entityManagerFactory
                if (factoryBean != null) {
                    val factory = factoryBean.getObject()
                    factoryBean.destroy()
                    check(factory == null || !factory.isOpen, "Owned writer factory must close")
                }
                val source = pool
                if (source != null) {
                    released()
                    source.close()
                    check(source.isClosed, "The owned P19 pool must close")
                }
                println(
                    "APMS_PHOTO_ARCHIVE_OWNED completedContracts=" + (nextOrder - 1) + " callbacks=" + externalChecks.get() +
                        " puts=" + puts.get() + " gets=" + gets.get() + " allSourceChecksPassed=" +
                        (nextOrder == 7 && nextAllowed) + " historyRetained=true",
                )
            }
        } finally {
            if (!unsettled) {
                logLevels.forEach { (logger, level) -> logger.level = level }
            }
        }
    }

    @Test
    @Order(1)
    fun `슬롯 둘과 제외된 출처가 주어졌을 때__반복 탐색하면__APMS 슬롯만 보관하고 출처를 바꾸지 않는다`() {
        contract("two-slot discovery") {
            seededStale = store.claim(300).orElseThrow()
            check(
                seededStale.animalId == 7L && seededStale.sourceUrl == "https://openapi.animal.go.kr/p19/old.png" &&
                    !store.complete(seededStale, photo("direct"), 3600),
                "Seeded old observation must fail the actual current-URL predicate before any discovery catches up",
            )
            store.discover(50)
            store.discover(50)
            check(count("apms_photo_archive") == 7L && count("apms_photo_scan") == 1L, "Exact two-slot APMS fixture must be discovered once")
            check(
                jdbc.queryForObject(
                    "SELECT count(*) FROM pawbridge_animal.apms_photo_archive WHERE animal_id IN(3,4,5,6)",
                    Long::class.javaObjectType,
                ) == 0L,
                "Manual, other-source, missing APMS id and absent photos must not be archived",
            )
            check(
                jdbc.queryForObject(
                    "SELECT count(*) FROM pawbridge_animal.apms_photo_archive WHERE animal_id=1",
                    Long::class.javaObjectType,
                ) == 2L,
                "Both photo slots are independently discovered",
            )
            check(
                "https://openapi.animal.go.kr/p19/cat.png" == jdbc.queryForObject(
                    "SELECT source_url FROM pawbridge_animal.apms_photo_archive WHERE animal_id=2 AND slot=1",
                    String::class.java,
                ) &&
                    jdbc.queryForObject(
                        "SELECT generation FROM pawbridge_animal.apms_photo_archive WHERE animal_id=7 AND slot=1",
                        Long::class.javaObjectType,
                    ) == 2L &&
                    jdbc.queryForObject(
                        "SELECT count(*) FROM pawbridge_animal.apms_photo_archive WHERE animal_id<>7 AND generation<>1",
                        Long::class.javaObjectType,
                    ) == 0L,
                "Trim observations and advance only the stale observed generation without rewriting source URLs",
            )
            deferAll()
        }
    }

    @Test
    @Order(2)
    fun `실제 워커와 인프로세스 객체가 주어졌을 때__슬롯 둘이 끝나면__준비된 슬롯 1이 기존 갤러리를 채운다`() {
        contract("READY to existing Gallery") {
            due(1, 1)
            due(1, 2)
            due(2, 1)
            val before = puts.get()
            val result = worker(3).runOnce()
            check(
                result.completed == 3 && result.failed == 0 && puts.get() == before + 3,
                "Real worker must publish all three photos, including the whitespace-normalized second Animal",
            )
            check(
                "READY" == state(1, 1) && "READY" == state(1, 2) && "READY" == state(2, 1) && externalChecks.get() >= 15,
                "Download, optimize and object callbacks must all observe no database transaction or active borrow",
            )
            val reads = TransactionTemplate(manager)
            reads.isReadOnly = true
            reads.timeout = 15
            val feed = LostGalleryFeed(
                jdbc,
                mapper,
                java.util.function.Function { key -> "https://synthetic.gallery.invalid/" + BUCKET + "/" + key },
                reads,
                reads,
            )
            val snapshot = feed.snapshot(null)
            val body = mapper.readTree(snapshot.body)
            val snapshotIds = ArrayList<Long>()
            body.get("records").forEach { record -> snapshotIds.add(record.get("id").asLong()) }
            check(
                body.get("complete").asBoolean() && body.get("count").asInt() == 2 && snapshotIds == listOf(1L, 2L) &&
                    body.get("photos").size() == 2,
                "V1 Gallery must consume both READY slot1 Animals including the unchanged whitespace source, without slot2 duplication",
            )
            val streamIds = ArrayList<Long>()
            feed.streamEntries(java.util.function.Consumer { entry -> streamIds.add((entry.record["id"] as Number).toLong()) })
            check(streamIds == listOf(1L, 2L), "V2 streaming must consume the same exact ordered Animal IDs as V1")
            check(feed.snapshot(snapshot.etag).body == null, "Unchanged existing Gallery ETag must remain stable")
        }
    }

    @Test
    @Order(3)
    fun `오래 관찰된 출처와 만료된 리스가 주어졌을 때__클레임을 교체하면__현재 세대와 토큰만 인정하고 오래된 작업을 거절한다`() {
        contract("source and lease fences") {
            // Case1 checked the seeded old observation against the unchanged current Animal.
            // This verifies current-URL mismatch defense, not a concurrent Animal UPDATE actor.
            val old = seededStale
            due(7, 1)
            val current = store.claim(300).orElseThrow()
            check(
                current.animalId == 7L && current.generation > old.generation && current.token != old.token,
                "Real discovery must advance generation and revoke the old lease",
            )
            check(
                !store.complete(old, photo("direct"), 3600) && !store.retry(old, "STALE", 60),
                "Old generation/token cannot complete or retry",
            )
            jdbc.update(
                "UPDATE pawbridge_animal.apms_photo_archive SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE animal_id=7 AND slot=1",
            )
            val replacement = store.claim(300).orElseThrow()
            check(
                replacement.token != current.token && !store.complete(current, photo("direct"), 3600) &&
                    !store.retry(current, "EXPIRED", 60),
                "Expired worker must lose complete and retry rights",
            )
            check(store.complete(replacement, photo("direct"), 3600), "Current replacement alone may publish READY")
        }
    }

    @Test
    @Order(4)
    fun `잠긴 사진과 클레이머 둘이 주어졌을 때__클레임하면__SKIP LOCKED로 토큰 하나만 사진을 소유한다`() {
        contract("physical claim ownership") {
            deferAll()
            due(8, 1)
            direct.connection.use { blocker ->
                check(
                    pid(blocker) != jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.javaObjectType),
                    "The lock owner and writer pool must use distinct physical PostgreSQL sessions",
                )
                blocker.autoCommit = false
                try {
                    blocker.prepareStatement(
                        "SELECT animal_id FROM pawbridge_animal.apms_photo_archive WHERE animal_id=8 AND slot=1 FOR UPDATE",
                    ).use { lock ->
                        lock.queryTimeout = 3
                        lock.executeQuery().use { row -> check(row.next(), "The exact owned row must be locked") }
                        check(store.claim(300).isEmpty, "Actual PostgreSQL claim must skip a row locked by another physical session")
                    }
                } finally {
                    blocker.rollback()
                }
            }
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(
                2,
                ThreadFactory { task ->
                    val thread = Thread(task, "p19-claim")
                    thread.isDaemon = true
                    thread
                },
            )
            try {
                val task = Callable {
                    start.await()
                    store.claim(300)
                }
                val first = executor.submit(task)
                val second = executor.submit(task)
                start.countDown()
                val winners = listOfNotNull(
                    first.get(8, TimeUnit.SECONDS).orElse(null),
                    second.get(8, TimeUnit.SECONDS).orElse(null),
                )
                check(winners.size == 1 && winners[0].animalId == 8L, "Two real concurrent claim calls must yield one owner token")
                check(store.complete(winners[0], photo("claim"), 3600), "Winner token may complete")
            } finally {
                start.countDown()
                executor.shutdown()
                if (!executor.awaitTermination(8, TimeUnit.SECONDS)) {
                    unsettled = true
                    throw AssertionError("Owned claim tasks remain unsettled")
                }
            }
        }
    }

    @Test
    @Order(5)
    fun `탐색 롤백과 완료 전 객체 저장이 주어졌을 때__재시도하면__커서 롤백과 같은 키 복구로 객체를 보존한다`() {
        contract("rollback and archive retry") {
            jdbc.update("UPDATE pawbridge_animal.apms_photo_scan SET sweep_cursor=9 WHERE id=1")
            jdbc.update(
                "UPDATE pawbridge_animal.apms_photo_archive SET source_url=? WHERE animal_id=9 AND slot=1",
                "https://openapi.animal.go.kr/p19/stale-before-rollback.png",
            )
            val generation: Long = jdbc.queryForObject(
                "SELECT generation FROM pawbridge_animal.apms_photo_archive WHERE animal_id=9 AND slot=1",
                Long::class.javaObjectType,
            )!!
            val before = archiveFingerprint()
            val exact = RuntimeException("P19_ROLLBACK")
            val writes = TransactionTemplate(manager)
            writes.timeout = 15
            try {
                writes.executeWithoutResult {
                    store.discover(1)
                    check(
                        jdbc.queryForObject(
                            "SELECT generation FROM pawbridge_animal.apms_photo_archive WHERE animal_id=9 AND slot=1",
                            Long::class.javaObjectType,
                        ) == generation + 1 &&
                            jdbc.queryForObject(
                                "SELECT sweep_cursor FROM pawbridge_animal.apms_photo_scan WHERE id=1",
                                Long::class.javaObjectType,
                            ) == 8L,
                        "The real archive generation and discovery cursor must both change before forced rollback",
                    )
                    throw exact
                }
                throw AssertionError("Owned rollback probe must throw")
            } catch (failure: RuntimeException) {
                check(failure === exact, "Only the exact rollback sentinel is accepted")
            }
            check(before == archiveFingerprint(), "Discovery archive and cursor writes must roll back together under the existing JPA manager")
            store.discover(50)
            deferAll()
            due(8, 1)
            badRecipe.set(true)
            val putsBefore = puts.get()
            // Deliberately invalid internal optimizer metadata exercises a real PostgreSQL varchar failure after object save.
            // This is a synthetic DB failure boundary, not a provider response accepted by the real optimizer adapter.
            val worker = worker(1)
            val failed = worker.runOnce()
            badRecipe.set(false)
            check(
                failed.failed == 1 && "RETRY" == state(8, 1) && puts.get() == putsBefore + 1,
                "Object save must survive a real DB completion failure and leave RETRY",
            )
            val key = "apms/photos/" + sha("https://openapi.animal.go.kr/p19/retry.png".toByteArray(StandardCharsets.UTF_8)) + ".png"
            check(
                objects.containsKey(key) &&
                    jdbc.queryForObject(
                        "SELECT attempts FROM pawbridge_animal.apms_photo_archive WHERE animal_id=8 AND slot=1",
                        Int::class.javaObjectType,
                    ) == 1,
                "Failed completion must keep the uploaded object and increment retry attempts",
            )
            due(8, 1)
            val success = worker.runOnce()
            check(
                success.completed == 1 && "READY" == state(8, 1) && puts.get() == putsBefore + 1,
                "Same content retry must reuse the existing object via readback without PUT overwrite",
            )
            due(8, 1)
            source404.set(true)
            val slow = worker.runOnce()
            source404.set(false)
            check(
                slow.failed == 1 && "RETRY" == state(8, 1) && objects.containsKey(key) &&
                    key == jdbc.queryForObject(
                        "SELECT object_key FROM pawbridge_animal.apms_photo_archive WHERE animal_id=8 AND slot=1",
                        String::class.java,
                    ) &&
                    true == jdbc.queryForObject(
                        "SELECT next_attempt_at>clock_timestamp()+INTERVAL '23 hours' FROM pawbridge_animal.apms_photo_archive WHERE animal_id=8 AND slot=1",
                        Boolean::class.javaObjectType,
                    ),
                "Slow source failure must retain successful metadata/object and use the original long retry class",
            )
            badRecipe.set(false)
            source404.set(false)
        }
    }

    @Test
    @Order(6)
    fun `처리 중 예약 사진이 주어졌을 때__협조적 중지를 요청하면__현재 사진을 끝내고 둘째 슬롯은 잡지 않는다`() {
        contract("actual cooperative schedule stop") {
            deferAll()
            due(9, 1)
            due(9, 2)
            val properties = properties(2)
            properties.initialDelayMs = 0
            properties.intervalMs = 1000
            val worker = PhotoArchiveWorker(store, http!!, storage!!, properties)
            val schedule = PhotoArchiveSchedule(worker, properties, 8)
            downloadEntered = CountDownLatch(1)
            downloadRelease = CountDownLatch(1)
            blockOnce.set(true)
            val stopped = CountDownLatch(1)
            val failure = AtomicReference<Throwable>()
            val closer = Thread(
                {
                    try {
                        schedule.stop()
                    } catch (problem: Throwable) {
                        failure.set(problem)
                    } finally {
                        stopped.countDown()
                    }
                },
                "p19-normal-stop",
            )
            closer.isDaemon = true
            try {
                schedule.start()
                check(downloadEntered!!.await(5, TimeUnit.SECONDS), "Actual scheduler must reach the inprocess photo boundary")
                released()
                val downloadsBefore = downloads.get()
                closer.start()
                val executor = ReflectionTestUtils.getField(schedule, "executor") as ScheduledThreadPoolExecutor
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (!executor.isShutdown && System.nanoTime() < until) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5))
                }
                check(
                    executor.isShutdown && !stopped.await(100, TimeUnit.MILLISECONDS),
                    "Stop must block while the admitted photo is in flight",
                )
                check(!closedStorage.get(), "Dependent storage must remain open during the admitted photo")
                downloadRelease!!.countDown()
                check(
                    stopped.await(8, TimeUnit.SECONDS) && failure.get() == null && executor.isTerminated,
                    "Actual scheduler must terminate cooperatively within this observation",
                )
                check(
                    !schedule.isRunning && downloads.get() == downloadsBefore && "READY" == state(9, 1) &&
                        "PENDING" == state(9, 2),
                    "The admitted photo completes but stop forbids another claim and download",
                )
            } finally {
                downloadRelease!!.countDown()
                if (closer.state == Thread.State.NEW) {
                    closer.start()
                }
                if (closer.state != Thread.State.NEW && !awaitCloser(closer, 9)) {
                    unsettled = true
                }
                val executor = ReflectionTestUtils.getField(schedule, "executor") as ScheduledThreadPoolExecutor?
                if (executor != null && !executor.isTerminated) {
                    unsettled = true
                }
                check(!unsettled, "Unsettled scheduler forbids later database access and resource shutdown")
            }
        }
    }

    private fun worker(limit: Int): PhotoArchiveWorker = PhotoArchiveWorker(store, http!!, storage!!, properties(limit))

    private fun properties(limit: Int): PhotoArchiveProperties {
        val properties = PhotoArchiveProperties()
        properties.bucket = BUCKET
        properties.optimizerUrl = URI.create("https://synthetic.optimizer.invalid/internal/photos/optimize")
        properties.internalApiKey = "p19-synthetic-only"
        properties.maxPhotos = limit
        properties.scanSize = 50
        properties.maxRunSeconds = 30
        properties.validate()
        return properties
    }

    private fun deferAll() {
        jdbc.update("UPDATE pawbridge_animal.apms_photo_archive SET next_attempt_at=clock_timestamp()+INTERVAL '2 days'")
    }

    private fun due(animal: Long, slot: Int) {
        jdbc.update(
            "UPDATE pawbridge_animal.apms_photo_archive SET next_attempt_at=clock_timestamp()-INTERVAL '1 second' WHERE animal_id=? AND slot=?",
            animal,
            slot,
        )
    }

    private fun state(animal: Long, slot: Int): String {
        return jdbc.queryForObject(
            "SELECT state FROM pawbridge_animal.apms_photo_archive WHERE animal_id=? AND slot=?",
            String::class.java,
            animal,
            slot,
        )!!
    }

    private fun count(table: String): Long {
        check(KEYS.containsKey(table), "Only fixed P19 tables may be counted")
        return jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal." + table, Long::class.javaObjectType)!!
    }

    private fun fingerprint(table: String): String {
        check(KEYS.containsKey(table), "Only fixed P19 tables may be fingerprinted")
        return jdbc.queryForObject(
            "SELECT count(*)::text||':'||md5(COALESCE(string_agg(row_to_json(t)::text,',' ORDER BY " + KEYS[table] + "),'')) FROM pawbridge_animal." + table + " t",
            String::class.java,
        )!!
    }

    private fun sourceFingerprint(): String = fingerprint("animals") + "|" + fingerprint("shelters")

    private fun archiveFingerprint(): String = fingerprint("apms_photo_archive") + "|" + fingerprint("apms_photo_scan")

    private fun externalBoundary() {
        released()
        externalChecks.incrementAndGet()
        check(!closedStorage.get(), "No callback may run after owned storage closure")
    }

    private fun released() {
        check(!unsettled, "Unsettled P19 work fences resource access")
        val source = pool
        val returned = !TransactionSynchronizationManager.isActualTransactionActive() &&
            !TransactionSynchronizationManager.hasResource(source!!) &&
            (source.hikariPoolMXBean.activeConnections == 0 && source.hikariPoolMXBean.threadsAwaitingConnection == 0)
        if (!returned) {
            unsettled = true
        }
        check(returned, "No writer transaction or borrowed connection may survive an external wait or contract boundary")
    }

    private fun contract(phase: String, body: Checked) {
        observed(phase) {
            body.run()
            completedBody = true
        }
    }

    private fun quiet() {
        for (name in listOf(
            "com.zaxxer.hikari",
            "org.springframework.jdbc",
            "org.springframework.orm.jpa",
            "org.hibernate",
            "com.pawbridge.backend.animal.internal.photoarchive",
        )) {
            val logger = LoggerFactory.getLogger(name) as ch.qos.logback.classic.Logger
            logLevels[logger] = logger.level
            logger.level = ch.qos.logback.classic.Level.OFF
        }
    }

    // P18 target/settings guard below is reused with only P19 names, marker, schema counts and fixed port.

    private fun verifyTarget() {
        val id: Map<String, Any?> = jdbc.queryForMap(
            "SELECT current_database() db,current_user actor,session_user session,current_setting('server_version_num') version,(SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()) owner",
        )
        check(
            settings.database == id["db"] && settings.username == id["actor"] && settings.username == id["session"] &&
                settings.owner() == id["owner"] && "170011" == id["version"],
            "Only the exact new P19 PostgreSQL17.11 target is accepted",
        )
        check(
            settings.marker == jdbc.queryForObject("SELECT marker FROM public.pb_apms_photo_archive_fixture", String::class.java),
            "Exact P19 ownership marker is mandatory",
        )
        check(
            true == jdbc.queryForObject(
                "SELECT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit AND NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=pg_roles.oid) FROM pg_roles WHERE rolname=current_user",
                Boolean::class.javaObjectType,
            ),
            "P19 writer cannot inherit owner or admin capabilities",
        )
        check(
            true == jdbc.queryForObject(
                "SELECT NOT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit FROM pg_roles WHERE rolname=?",
                Boolean::class.javaObjectType,
                settings.owner(),
            ) &&
                false == jdbc.queryForObject(
                    "SELECT pg_has_role(current_user,?,'MEMBER')",
                    Boolean::class.javaObjectType,
                    settings.owner(),
                ),
            "Separate owner remains NOLOGIN and inaccessible",
        )
        check(
            false == jdbc.queryForObject(
                "SELECT has_database_privilege(current_user,current_database(),'CREATE,TEMP')",
                Boolean::class.javaObjectType,
            ),
            "Runtime database and TEMP DDL are forbidden",
        )
        for (schema in listOf("public", "pawbridge_animal")) {
            check(
                true == jdbc.queryForObject(
                    "SELECT has_schema_privilege(current_user,?,'USAGE') AND NOT has_schema_privilege(current_user,?,'CREATE')",
                    Boolean::class.javaObjectType,
                    schema,
                    schema,
                ),
                "Runtime schema creation is forbidden",
            )
        }
        check(
            jdbc.query(
                "SELECT tablename FROM pg_tables WHERE schemaname='pawbridge_animal' ORDER BY tablename",
                RowMapper { r, _ -> r.getString(1) },
            ) == KEYS.keys.sorted(),
            "P19 accepts exactly four application tables",
        )
        check(
            jdbc.queryForObject(
                "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname !~ '^pg_' AND n.nspname<>'information_schema' AND c.relkind IN('r','p','v','m','f')",
                Long::class.javaObjectType,
            ) == 5L,
            "Only four application tables and one marker may exist",
        )
        for (table in KEYS.keys) {
            for (right in listOf("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER")) {
                val allowed = right == "SELECT" || (!SOURCE.contains(table) && setOf("INSERT", "UPDATE").contains(right))
                check(
                    allowed == jdbc.queryForObject(
                        "SELECT has_table_privilege(current_user,?,?)",
                        Boolean::class.javaObjectType,
                        "pawbridge_animal." + table,
                        right,
                    ),
                    "Exact source-read/archive-write ACL is required",
                )
                if (!allowed && setOf("INSERT", "UPDATE", "REFERENCES").contains(right)) {
                    check(
                        false == jdbc.queryForObject(
                            "SELECT has_any_column_privilege(current_user,?,?)",
                            Boolean::class.javaObjectType,
                            "pawbridge_animal." + table,
                            right,
                        ),
                        "Column grants cannot bypass denied writes",
                    )
                }
            }
        }
        check(
            true == jdbc.queryForObject(
                "SELECT has_table_privilege(current_user,'public.pb_apms_photo_archive_fixture','SELECT') AND NOT has_table_privilege(current_user,'public.pb_apms_photo_archive_fixture','INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')",
                Boolean::class.javaObjectType,
            ),
            "Marker remains SELECT-only",
        )
        check(
            jdbc.query(
                "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='pawbridge_animal' AND c.relkind='S' ORDER BY c.relname",
                RowMapper { r, _ -> r.getString(1) },
            ) == listOf("animals_id_seq", "shelters_id_seq"),
            "Only original source identity sequences exist",
        )
        for (sequence in listOf("animals_id_seq", "shelters_id_seq")) {
            check(
                false == jdbc.queryForObject(
                    "SELECT has_sequence_privilege(current_user,?,'USAGE,SELECT,UPDATE')",
                    Boolean::class.javaObjectType,
                    "pawbridge_animal." + sequence,
                ),
                "Runtime archive role cannot advance source identities",
            )
        }
        check(
            jdbc.queryForObject(
                "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN('public','pawbridge_animal') AND c.relkind IN('r','S') AND pg_get_userbyid(c.relowner)<>?",
                Long::class.javaObjectType,
                settings.owner(),
            ) == 0L,
            "Every fixture object retains its separate owner",
        )
    }

    private fun interface Checked {
        fun run()
    }

    private class Settings(
        val url: String,
        val database: String,
        val username: String,
        val password: String,
        val marker: String,
    ) {
        fun owner(): String = database.replace("pb_apms_photo_archive_owned_", "pb_apms_photo_archive_owner_")

        override fun toString(): String = "OwnedApmsSettings[redacted]"

        companion object {
            fun load(): Settings {
                check("P19_PG_SETTINGS" == System.getenv("PAWBRIDGE_OWNED_SETTINGS_ENV"), "Only the explicit P19 selector is accepted")
                val path = System.getenv("P19_PG_SETTINGS")
                check(path != null && path.isNotBlank(), "P19_PG_SETTINGS is required without fallback or skip")
                val file = Path.of(path)
                val expected = Path.of("/private/tmp/pawbridge-owned-pg-night-20261020/apms-photo-archive-trim-green/apms-photo-archive.properties")
                check(
                    file.isAbsolute && file.normalize() == file && file == expected &&
                        Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file),
                    "Only the exact new P19 regular settings file is accepted",
                )
                var parent: Path? = file.parent
                while (parent != null) {
                    check(!Files.isSymbolicLink(parent), "Settings ancestors may not be symlinks")
                    parent = parent.parent
                }
                check(
                    Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS) ==
                        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE) &&
                        (Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toInt() == 1 &&
                        System.getProperty("user.name") == Files.getOwner(file).name &&
                        Files.getOwner(file) == Files.getOwner(file.parent),
                    "P19 settings must be current-owner single-link0600",
                )
                check(
                    Files.getPosixFilePermissions(file.parent) ==
                        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
                    "The new P19 private directory must remain0700",
                )
                val v = Properties()
                Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input: InputStream -> v.load(input) }
                check(
                    v.stringPropertyNames() == setOf("jdbcUrl", "database", "username", "password", "marker"),
                    "Exactly five private P19 settings are accepted",
                )
                val db = required(v, "database")
                val url = required(v, "jdbcUrl")
                val user = required(v, "username")
                val marker = required(v, "marker")
                check(
                    db.matches(Regex("pb_apms_photo_archive_owned_[0-9a-f]{12}")) &&
                        url == "jdbc:postgresql://127.0.0.1:60441/" + db &&
                        user == db.replace("pb_apms_photo_archive_owned_", "pb_apms_photo_archive_writer_") &&
                        marker.matches(Regex("[0-9a-f]{32}")),
                    "P19 target/role/marker must identify only its new loopback fixture",
                )
                val state = file.parent.resolve("state.json")
                check(
                    Files.isRegularFile(state, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(state) &&
                        Files.getPosixFilePermissions(state, LinkOption.NOFOLLOW_LINKS) ==
                        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE) &&
                        (Files.getAttribute(state, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toInt() == 1 &&
                        Files.getOwner(state) == Files.getOwner(file),
                    "New P19 state must be private and owned",
                )
                val claim = ObjectMapper().readTree(Files.readAllBytes(state))
                check(
                    db == claim.path("database").asText() && user == claim.path("writer").asText() &&
                        marker == claim.path("marker").asText() && "apms-photo-archive-v1" == claim.path("purpose").asText() &&
                        FIXTURE_SHA == claim.path("schema_sha256").asText() && claim.path("schemaInitialized").asBoolean() &&
                        claim.path("port").asInt() == 60441 &&
                        "b1719e47b8c3ff8c334e9df96c86b4f36478eb72022b8e19278d75c9f8a7187c" == claim.path("containerId").asText() &&
                        "sha256:cf134a767f474095eeba57e0117be8e568e011a63f33fbf252f14c9b760f8e6f" == claim.path("image").asText() &&
                        "08b97b84-e0b5-4c6f-a5f8-3d51ec4a2f89" == claim.path("owner").asText(),
                    "P19 claim must bind the new target, frozen SQL and the approved container identity; no historical fixture fallback",
                )
                return Settings(url, db, user, required(v, "password"), marker)
            }

            private fun required(v: Properties, key: String): String {
                val value = v.getProperty(key)
                check(value != null && value.isNotBlank(), "Every P19 setting is mandatory")
                return value
            }
        }
    }

    companion object {
        private const val FIXTURE_SHA = "6930ff2d0f3bcd0bd48bad24c16410ae3348ed1480c9284037b0318f15c3083e"
        private const val BUCKET = "pawbridge-animal-originals"
        private val KEYS: Map<String, String> = mapOf(
            "animals" to "id",
            "shelters" to "id",
            "apms_photo_archive" to "animal_id,slot",
            "apms_photo_scan" to "id",
        )
        private val SOURCE: Set<String> = setOf("animals", "shelters")

        private fun photo(text: String): ArchivedPhoto {
            val bytes = text.toByteArray(StandardCharsets.UTF_8)
            return ArchivedPhoto(bytes, sha(bytes), sha(bytes), "image/png", 1, 1, "original-v1")
        }

        private fun awaitCloser(thread: Thread, seconds: Int): Boolean {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds.toLong())
            var interrupted = false
            try {
                while (thread.isAlive) {
                    val left = until - System.nanoTime()
                    if (left <= 0) {
                        return false
                    }
                    try {
                        TimeUnit.NANOSECONDS.timedJoin(thread, left)
                    } catch (ignored: InterruptedException) {
                        interrupted = true
                    }
                }
                return true
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        private fun observed(phase: String, body: Checked) {
            try {
                body.run()
            } catch (failure: AssertionError) {
                throw failure
            } catch (failure: Throwable) {
                throw AssertionError("Owned P19 observation failed: " + phase + "; type=" + failure.javaClass.name)
            }
        }

        private fun check(condition: Boolean, fixed: String) {
            if (!condition) {
                throw AssertionError(fixed)
            }
        }

        private fun sha(bytes: ByteArray): String {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
            } catch (impossible: Exception) {
                throw AssertionError("SHA-256 unavailable")
            }
        }

        private fun pid(connection: Connection): Int {
            connection.createStatement().use { statement ->
                statement.queryTimeout = 3
                statement.executeQuery("SELECT pg_backend_pid()").use { row ->
                    check(row.next(), "Physical PostgreSQL PID is required")
                    return row.getInt(1)
                }
            }
        }
    }
}
