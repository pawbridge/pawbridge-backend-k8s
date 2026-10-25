package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.api.AnimalCommandActors
import com.pawbridge.backend.animal.internal.command.infrastructure.service.AnimalCommandService
import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer
import com.pawbridge.backend.boot.security.PostTokenAuthenticator
import com.pawbridge.backend.support.TestFaker
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import jakarta.persistence.EntityManagerFactory
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullAndEmptySource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.sql.SQLException
import java.time.Duration
import java.time.LocalDateTime
import java.util.Calendar
import java.util.Date
import java.util.LinkedHashSet
import java.util.Properties
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier
import javax.crypto.SecretKey
import javax.sql.DataSource

/** Actual MVC/security/JPA contracts against one explicitly owned reduced fixture, never production. */
@Tag("owned-postgresql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(30)
class OwnedPostgresqlAnimalCommandTest {
    private val ownedIds: MutableSet<Long> = LinkedHashSet()
    private val ownedKeys: MutableSet<String> = LinkedHashSet()
    private var pool: HikariDataSource? = null
    private lateinit var probe: DriverManagerDataSource
    private lateinit var jdbc: JdbcTemplate
    private lateinit var baseline: Map<String, Fingerprint>
    private var pendingConcurrentWorkers = false

    private fun pool(): HikariDataSource = pool!!

    @BeforeAll
    fun openOnlyTheVerifiedOwnedWriter() {
        observed("owned writer identity") {
            val settings = Settings.load()
            probe = DriverManagerDataSource(settings.jdbcUrl, settings.username, settings.password)
            val network = Properties()
            network.setProperty("connectTimeout", "3")
            network.setProperty("socketTimeout", "5")
            probe.setConnectionProperties(network)
            verifyServer(JdbcTemplate(probe), settings)
            val config = HikariConfig()
            config.jdbcUrl = settings.jdbcUrl
            config.username = settings.username
            config.password = settings.password
            config.poolName = "owned-animal-command-writer"
            config.maximumPoolSize = 1
            config.minimumIdle = 0
            config.connectionTimeout = 2000
            config.validationTimeout = 1000
            config.initializationFailTimeout = -1
            config.isReadOnly = false
            config.addDataSourceProperty("connectTimeout", "3")
            config.addDataSourceProperty("socketTimeout", "5")
            val source = HikariDataSource(config)
            pool = source
            jdbc = JdbcTemplate(source)
            jdbc.queryTimeout = 5
            check(
                jdbc.queryForObject(
                    "SELECT count(*) FROM pawbridge_animal.animals WHERE special_mark LIKE 'OWNED-P8-%'",
                    Long::class.javaObjectType,
                ) == 0L,
                "A previous trial's owned rows must not be absorbed into a new baseline",
            )
            baseline = fingerprint()
            check(
                count("SELECT count(*) FROM pawbridge_animal.animals WHERE id=?", FOREIGN_ANIMAL) == 1L,
                "The protected foreign Animal must exist before the test",
            )
            check(
                count("SELECT count(*) FROM pawbridge_animal.chatbot_sessions WHERE animal_id=?", FOREIGN_ANIMAL) == 1L,
                "The source FK fixture must exist before the test",
            )
            released()
        }
    }

    @BeforeEach
    fun requireTheUnchangedForeignBaseline() {
        observed("unchanged fixture baseline") {
            check(!pendingConcurrentWorkers, "Preserve the owned fixture while request workers may still be running")
            check(
                ownedIds.isEmpty() && ownedKeys.isEmpty() && fingerprint() == baseline,
                "Every case must start at the owned fixture baseline",
            )
            released()
        }
    }

    @AfterEach
    fun removeOnlyResponseOwnedRowsAndPreserveEveryForeignRow() {
        observed("owned row cleanup") {
            check(!pendingConcurrentWorkers, "Do not clean rows while an owned request worker may still write")
            cleanupOwnedRows()
        }
    }

    @AfterAll
    fun closeOnlyTheOwnedWriterPool() {
        check(!pendingConcurrentWorkers, "Preserve the owned pool and evidence if worker termination remains uncertain")
        val owned = pool
        if (owned != null) {
            owned.close()
            check(owned.isClosed, "The owned writer pool must close")
        }
    }

    @Test
    fun `관리자 DB 권한과 오래된 사용자 JWT가 주어졌을 때__등록하면__수동 감사와 검색을 저장한다`() {
        http { mvc, _ ->
            val created = create(mvc, ADMIN, null, "owned-care-b", "synthetic breed")
            val id = created.path("id").asLong()
            check(
                "MANUAL" == created.path("apiSource").asText() &&
                    created.path("apmsNoticeNo").asText().matches(Regex("MAN-\\d{6}-\\d{6}")),
                "The original default MANUAL source and generated notice format must survive",
            )
            val animal = animal(id)
            check(
                animal["created_at"] != null && animal["updated_at"] != null,
                "Actual JPA auditing must populate both timestamps on create",
            )
            check(
                (animal["shelter_id"] as Number).toLong() == 990011L,
                "The current database administrator may create for another shelter despite a stale User JWT role",
            )
            projection(id, 1)
            released()
        }
    }

    @ParameterizedTest(name = "missingApmsNotice[{index}]")
    @NullAndEmptySource
    @ValueSource(strings = ["  "])
    fun `APMS 출처에 공지가 없을 때__등록하면__400을 돌려주고 변경이 없다`(
        notice: String?,
    ) {
        http { mvc, _ ->
            for (member in listOf(ADMIN, SHELTER)) {
                val before = fingerprint()
                val body = body(null, "owned-care-a", "missing APMS notice breed")
                body["apiSource"] = "APMS_ANIMAL"
                body["apmsNoticeNo"] = notice
                val rejected = send(mvc, "POST", ROOT, token(member), JSON.writeValueAsString(body), 400)
                check(
                    rejected.path("code").asInt() == 400 &&
                        "수동 등록 외 데이터는 공고번호가 필요합니다." == rejected.path("message").asText() &&
                        rejected.has("data") && rejected.path("data").isNull,
                    "A missing APMS notice must return the fixed validation error",
                )
                check(fingerprint() == before, "Rejected APMS input must preserve all seven fixture tables")
                released()
            }
        }
    }

    @Test
    fun `소유 보호소와 APMS 동물이 주어졌을 때__JSON 명령 넷을 쓰면__원래 감사와 검색 판본을 유지한다`() {
        http { mvc, _ ->
            val notice = "OWNED-P8-FOUR-COMMANDS"
            val body = body(notice, "owned-care-a", "synthetic breed")
            body["apiSource"] = "APMS_ANIMAL"
            val created = send(mvc, "POST", ROOT, token(SHELTER), JSON.writeValueAsString(body), 201)
            val id = remember(created)
            val beforeAudit = commandAudit(id)
            check(
                "APMS_ANIMAL" == created.path("apiSource").asText(),
                "An explicit source must not be rewritten to MANUAL",
            )
            val statusResponse = send(
                mvc,
                "PATCH",
                ROOT + "/" + id + "/status",
                token(SHELTER),
                "{\"newStatus\":\"ADOPTED\"}",
                200,
            )
            val status = animal(id)
            val statusAudit = commandAudit(id)
            check(
                "ADOPTED" == status["status"] && beforeAudit["created_at"] == statusAudit["created_at"],
                "A status change must preserve the original creation audit field",
            )
            check(
                statusAudit.getValue("updated_at").isAfter(beforeAudit.getValue("updated_at")),
                "The real update must advance the JPA audit timestamp",
            )
            val responseUpdatedAt = JSON.treeToValue(statusResponse.path("updatedAt"), LocalDateTime::class.java)
            val persistedUpdatedAt = statusAudit.getValue("updated_at")
            check(
                responseUpdatedAt != null &&
                    responseUpdatedAt.isAfter(beforeAudit.getValue("updated_at")),
                "The status response must expose the advanced audit timestamp rather than the previous entity value",
            )
            check(
                Duration.between(responseUpdatedAt, persistedUpdatedAt).abs().compareTo(Duration.ofNanos(1000)) < 0,
                "The status response audit must match persisted audit within PostgreSQL microsecond precision",
            )
            check(
                beforeAudit["created_at"] == JSON.treeToValue(statusResponse.path("createdAt"), LocalDateTime::class.java),
                "The status response must preserve the original creation audit timestamp",
            )
            projection(id, 1)
            send(
                mvc,
                "PATCH",
                ROOT + "/" + id + "/description",
                token(SHELTER),
                "{\"description\":\"새로운 보호 설명\"}",
                200,
            )
            check("새로운 보호 설명" == animal(id)["description"], "The original description command must persist")
            projection(id, 2)
            send(mvc, "DELETE", ROOT + "/" + id, token(SHELTER), null, 204)
            check(
                count("SELECT count(*) FROM pawbridge_animal.animals WHERE id=?", id) == 0L &&
                    count("SELECT count(*) FROM pawbridge_animal.animal_search_documents WHERE animal_id=?", id) == 0L,
                "Delete and the original search-document FK cascade must commit together",
            )
            released()
        }
    }

    @ParameterizedTest
    @ValueSource(longs = [910004, 910005, 910006, 910099])
    fun `대기·누락·미연결 보호소 프로필이 주어졌을 때__JWT가 관리자를 주장하면__변경 없이 거절한다`(
        member: Long,
    ) {
        http { mvc, _ ->
            val before = fingerprint()
            send(
                mvc,
                "POST",
                ROOT,
                token(member, "ROLE_ADMIN"),
                JSON.writeValueAsString(body("OWNED-P8-DENIED", "owned-care-a", "breed")),
                403,
            )
            check(
                fingerprint() == before,
                "Current User role, deletion and shelter state must override privileged JWT and X-User claims",
            )
            released()
        }
    }

    @Test
    fun `다른 보호소가 주어졌을 때__소유자 동물을 등록·수정·삭제하면__네 명령 모두 변경 없이 거절한다`() {
        http { mvc, _ ->
            val id = create(mvc, SHELTER, "OWNED-P8-OWNER", "owned-care-a", "breed").path("id").asLong()
            val before = fingerprint()
            send(
                mvc,
                "POST",
                ROOT,
                token(WRONG_SHELTER),
                JSON.writeValueAsString(body("OWNED-P8-WRONG", "owned-care-a", "breed")),
                403,
            )
            send(
                mvc,
                "PATCH",
                ROOT + "/" + id + "/status",
                token(WRONG_SHELTER),
                "{\"newStatus\":\"ADOPTED\"}",
                403,
            )
            send(
                mvc,
                "PATCH",
                ROOT + "/" + id + "/description",
                token(WRONG_SHELTER),
                "{\"description\":\"forged change\"}",
                403,
            )
            send(mvc, "DELETE", ROOT + "/" + id, token(WRONG_SHELTER), null, 403)
            check(fingerprint() == before, "The actual current shelter owner predicate must protect every command")
            released()
        }
    }

    @Test
    fun `JWT가 없거나 위조 신원이나 잘못된 DTO가 주어졌을 때__JSON 경로를 호출하면__바인딩 전에 인증하고 이웃을 닫아 둔다`() {
        http { mvc, _ ->
            val before = fingerprint()
            send(mvc, "POST", ROOT, null, "not-json", 401)
            send(mvc, "POST", ROOT, "Bearer invalid.synthetic.token", "{}", 401)
            send(mvc, "POST", ROOT, token(ADMIN), "{}", 400)
            send(
                mvc,
                "PATCH",
                ROOT + "/" + FOREIGN_ANIMAL + "/description",
                token(ADMIN),
                "{\"description\":\" \"}",
                400,
            )
            send(mvc, "POST", ROOT + "/extra", token(ADMIN), "{}", 403)
            check(
                fingerprint() == before,
                "Authentication, validation and closed neighboring routes must perform no mutation",
            )
            released()
        }
    }

    @Test
    fun `원래 롤백 문자셋 제약이 주어졌을 때__4바이트 문자를 등록하면__400을 돌려주고 모든 쓰기를 롤백한다`() {
        http { mvc, _ ->
            val before = fingerprint()
            val body = body("OWNED-P8-CHARSET", "owned-care-a", "breed")
            body["description"] = "synthetic 🐶"
            val result = send(mvc, "POST", ROOT, token(SHELTER), JSON.writeValueAsString(body), 400)
            check(
                result.path("code").asInt() == 400 && fingerprint() == before,
                "The exact original V7 charset refusal must remain 400 and atomic",
            )
            released()
        }
    }

    @Test
    fun `원래 챗봇 외래키가 주어졌을 때__관리자가 동물을 삭제하면__500을 돌려주고 외래키 기준을 보존한다`() {
        http { mvc, _ ->
            val before = fingerprint()
            send(mvc, "DELETE", ROOT + "/" + FOREIGN_ANIMAL, token(ADMIN), null, 500)
            check(fingerprint() == before, "The source FK delete failure must roll back Animal and search changes")
            released()
        }
    }

    @Test
    fun `실제 서비스 쓰기 템플릿이 주어졌을 때__현재 사용자를 잠그면__쓰기 READ_COMMITTED로 연결 하나를 유지한다`() {
        http { _, context ->
            val writes = ReflectionTestUtils.getField(context.getBean(AnimalCommandService::class.java), "writes")
                as TransactionTemplate?
            check(writes != null, "The actual service writer template must be selected")
            writes!!.executeWithoutResult {
                check(
                    "off" == jdbc.queryForObject("SHOW transaction_read_only", String::class.java) &&
                        "read committed" == jdbc.queryForObject("SHOW transaction_isolation", String::class.java),
                    "PostgreSQL must report the actual writable READ COMMITTED service transaction",
                )
                check(
                    TransactionSynchronizationManager.hasResource(pool()) &&
                        pool().hikariPoolMXBean.activeConnections == 1,
                    "JpaTM must bind the sole pooled writer connection for JDBC",
                )
                val actor = context.getBean(AnimalCommandActors::class.java).find(SHELTER)!!
                check(
                    actor.active && !actor.deleted && "ROLE_SHELTER" == actor.role,
                    "The real User JDBC lookup must participate in the Animal writer transaction",
                )
                // A separate physical connection is a bounded lock probe, not a second application pool.
                try {
                    probe.connection.use { connection ->
                        connection.prepareStatement(
                            "SELECT user_id FROM pawbridge_user.users WHERE user_id=? FOR UPDATE NOWAIT",
                        ).use { statement ->
                            statement.setLong(1, SHELTER)
                            statement.queryTimeout = 3
                            statement.executeQuery().use {
                                throw AssertionError("The current User row lock must exclude another connection")
                            }
                        }
                    }
                } catch (failure: SQLException) {
                    check("55P03" == failure.sqlState, "The independent connection must observe the actual User row lock")
                }
            }
            released()
        }
    }

    @Test
    fun `커밋된 등록과 응답 단언 실패가 주어졌을 때__정리하면__응답 ID 없이 미리 등록한 키를 찾는다`() {
        http { mvc, _ ->
            val body = body(null, "owned-care-a", "response failure cleanup breed")
            val key = body["specialMark"] as String
            var refusal: AssertionError? = null
            try {
                // Deliberately reject the real 201 before any response JSON or ID is consumed.
                send(mvc, "POST", ROOT, token(SHELTER), JSON.writeValueAsString(body), 500)
            } catch (expected: AssertionError) {
                refusal = expected
            }
            check(
                refusal != null && ownedIds.isEmpty() &&
                    count("SELECT count(*) FROM pawbridge_animal.animals WHERE special_mark=?", key) == 1L,
                "The negative observation must commit one owned row without recording a response identity",
            )
            cleanupOwnedRows()
            check(fingerprint() == baseline, "Exact-key cleanup must restore the foreign baseline after response failure")
        }
    }

    @Test
    fun `서로 다른 행위자의 상태·설명 변경이 주어졌을 때__동물을 함께 기다리면__두 커밋 변경을 모두 보존한다`() {
        http { mvc, _ ->
            val id = create(mvc, SHELTER, "OWNED-P8-CONCURRENT-UPDATE", "owned-care-a", "breed").path("id").asLong()
            val responses = raceWhileLocked(
                "SELECT id FROM pawbridge_animal.animals WHERE id=" + id + " FOR UPDATE",
                Callable {
                    send(
                        mvc,
                        "PATCH",
                        ROOT + "/" + id + "/status",
                        token(ADMIN),
                        "{\"newStatus\":\"ADOPTED\"}",
                        200,
                    )
                },
                Callable {
                    send(
                        mvc,
                        "PATCH",
                        ROOT + "/" + id + "/description",
                        token(SHELTER),
                        "{\"description\":\"concurrent synthetic description\"}",
                        200,
                    )
                },
            )
            val persisted = animal(id)
            check(
                "ADOPTED" == persisted["status"] &&
                    "concurrent synthetic description" == persisted["description"],
                "Two successful concurrent commands must preserve both committed mutations",
            )
            check(
                "ADOPTED" == responses[0].path("status").asText() &&
                    "concurrent synthetic description" == responses[1].path("description").asText(),
                "Each successful response must contain its own mutation",
            )
            projection(id, 2)
            released()
        }
    }

    @Test
    fun `중간 수동 공지가 삭제됐을 때__다시 등록하면__현재 최댓값 뒤로 번호를 올린다`() {
        http { mvc, _ ->
            val first = create(mvc, SHELTER, null, "owned-care-a", "breed")
            val middle = create(mvc, SHELTER, null, "owned-care-a", "breed")
            val last = create(mvc, SHELTER, null, "owned-care-a", "breed")
            send(mvc, "DELETE", ROOT + "/" + middle.path("id").asLong(), token(SHELTER), null, 204)
            val next = create(mvc, SHELTER, null, "owned-care-a", "breed")
            val latest = last.path("apmsNoticeNo").asText()
            val suffix = latest.substring(latest.length - 6).toLong()
            val expected = latest.substring(0, latest.length - 6) + String.format("%06d", suffix + 1)
            check(
                expected == next.path("apmsNoticeNo").asText() &&
                    first.path("apmsNoticeNo").asText() != next.path("apmsNoticeNo").asText(),
                "Deleting a middle row must not reuse the live maximum manual notice",
            )
            check(
                count("SELECT count(*) FROM pawbridge_animal.animals WHERE apms_notice_no=?", expected) == 1L,
                "The generated notice must identify only its newly owned row in this fixture",
            )
            released()
        }
    }

    @Test
    fun `서로 다른 행위자의 동시 등록이 주어졌을 때__삽입이 겹치면__서로 다른 공지를 만든다`() {
        http { mvc, _ ->
            // Register both exact cleanup keys before dispatch; worker threads do not mutate owned-key sets.
            val firstBody = JSON.writeValueAsString(body(null, "owned-care-a", "breed"))
            val secondBody = JSON.writeValueAsString(body(null, "owned-care-a", "breed"))
            val created = raceWhileLocked(
                "LOCK TABLE pawbridge_animal.animals IN SHARE MODE",
                Callable { send(mvc, "POST", ROOT, token(ADMIN), firstBody, 201) },
                Callable { send(mvc, "POST", ROOT, token(SHELTER), secondBody, 201) },
            )
            val first = remember(created[0])
            val second = remember(created[1])
            val a = created[0].path("apmsNoticeNo").asText()
            val b = created[1].path("apmsNoticeNo").asText()
            check(
                first != second && a.matches(Regex("MAN-\\d{6}-\\d{6}")) && b.matches(Regex("MAN-\\d{6}-\\d{6}")) && a != b,
                "Concurrent automatic manual notices must be distinct and retain the original format",
            )
            check(
                jdbc.queryForObject(
                    "SELECT count(*) FROM pawbridge_animal.animals WHERE apms_notice_no IN (?,?)",
                    Long::class.javaObjectType,
                    a,
                    b,
                ) == 2L,
                "The two successful creates must persist two differently numbered rows",
            )
            projection(first, 1)
            projection(second, 1)
            released()
        }
    }

    private fun raceWhileLocked(
        controlledOwnedLockSql: String,
        first: Callable<JsonNode>,
        second: Callable<JsonNode>,
    ): List<JsonNode> {
        val source = pool()
        source.maximumPoolSize = 2
        val workers = Executors.newFixedThreadPool(2)
        pendingConcurrentWorkers = true
        try {
            probe.connection.use { holder ->
                holder.createStatement().use { statement ->
                    holder.autoCommit = false
                    statement.execute("SET LOCAL lock_timeout='3s'")
                    var holderPid: Int
                    statement.executeQuery("SELECT pg_backend_pid()").use { pid ->
                        pid.next()
                        holderPid = pid.getInt(1)
                    }
                    statement.execute(controlledOwnedLockSql)
                    val a = workers.submit(first)
                    val b = workers.submit(second)
                    try {
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                        var waiting = 0
                        do {
                            statement.execute("SELECT pg_stat_clear_snapshot()")
                            statement.executeQuery(
                                "WITH waiters AS (SELECT pid,pg_blocking_pids(pid) AS blockers FROM pg_stat_activity " +
                                    "WHERE datname=current_database() AND usename=current_user AND state='active' AND wait_event_type='Lock') " +
                                    "SELECT count(DISTINCT w.pid) FROM waiters w WHERE " + holderPid + "=ANY(w.blockers) OR EXISTS " +
                                    "(SELECT FROM waiters parent WHERE parent.pid=ANY(w.blockers) AND " + holderPid + "=ANY(parent.blockers))",
                            ).use { rows ->
                                rows.next()
                                waiting = rows.getInt(1)
                            }
                            if (waiting == 2) {
                                break
                            }
                            Thread.sleep(10)
                        } while (System.nanoTime() < deadline)
                        check(
                            waiting == 2 && source.hikariPoolMXBean.activeConnections == 2,
                            "Two distinct owned writer backends must actually overlap at PostgreSQL locks",
                        )
                    } finally {
                        holder.rollback()
                    }
                    return listOf(a.get(8, TimeUnit.SECONDS), b.get(8, TimeUnit.SECONDS))
                }
            }
        } finally {
            workers.shutdown()
            try {
                if (!workers.awaitTermination(8, TimeUnit.SECONDS)) {
                    workers.shutdownNow()
                    check(workers.awaitTermination(3, TimeUnit.SECONDS), "Both owned request workers must finish before cleanup")
                }
                pendingConcurrentWorkers = false
                source.maximumPoolSize = 1
            } catch (interrupted: InterruptedException) {
                workers.shutdownNow()
                Thread.currentThread().interrupt()
                throw interrupted
            }
        }
    }

    private fun cleanupOwnedRows() {
        released()
        val discovered: MutableSet<Long> = LinkedHashSet(ownedIds)
        var unique = true
        for (key in ownedKeys) {
            val rows = jdbc.query(
                "SELECT id FROM pawbridge_animal.animals WHERE special_mark=?",
                RowMapper { row, _ -> row.getLong(1) },
                key,
            )
            unique = unique and (rows.size <= 1)
            discovered.addAll(rows)
        }
        for (id in discovered) {
            check(id > 0 && id != FOREIGN_ANIMAL, "Cleanup cannot select an invalid or foreign fixture Animal")
        }
        for (id in discovered) {
            jdbc.update("DELETE FROM pawbridge_animal.animals WHERE id=?", id)
        }
        ownedIds.clear()
        ownedKeys.clear()
        // Identity sequence consumption on failed/rolled-back inserts is deliberately outside this fingerprint.
        check(fingerprint() == baseline, "All seven tables, including foreign seeds, must return to their baseline")
        check(unique, "Each generated request key must identify at most one Animal")
        released()
    }

    private fun http(action: HttpCheck) {
        observed("actual secured MVC/JPA contract") {
            val source = pool()
            val factory = AtomicReference<EntityManagerFactory>()
            WebApplicationContextRunner()
                .withConfiguration(
                    AutoConfigurations.of(
                        PropertyPlaceholderAutoConfiguration::class.java,
                        JacksonAutoConfiguration::class.java,
                        HttpMessageConvertersAutoConfiguration::class.java,
                        ValidationAutoConfiguration::class.java,
                        DispatcherServletAutoConfiguration::class.java,
                        WebMvcAutoConfiguration::class.java,
                        SecurityAutoConfiguration::class.java,
                        SecurityFilterAutoConfiguration::class.java,
                    ),
                )
                .withUserConfiguration(AnimalCommandConfiguration::class.java, ReadSecurityConfiguration::class.java)
                .withPropertyValues(
                    "pawbridge.animal-commands.enabled=true",
                    "spring.sql.init.mode=never",
                    "spring.jmx.enabled=false",
                )
                .withBean(
                    "writeDataSource",
                    DataSource::class.java,
                    Supplier { source },
                    BeanDefinitionCustomizer { definition -> definition.setDestroyMethodName("") },
                )
                .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { jdbc })
                .withBean(PostTokenAuthenticator::class.java, Supplier { PostTokenAuthenticator(KEY) })
                .run { context ->
                    check(context.startupFailure == null, "The real Animal command and security configuration must start")
                    factory.set(context.getBean("animalCommandEntityManagerFactory", EntityManagerFactory::class.java))
                    val mvc: MockMvc = MockMvcBuilders.webAppContextSetup(context)
                        .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
                        .build()
                    try {
                        action.run(mvc, context)
                    } catch (failure: AssertionError) {
                        throw failure
                    } catch (failure: Exception) {
                        throw AssertionError("Owned Animal HTTP observation failed: " + failure.javaClass.name)
                    }
                }
            check(
                factory.get() != null && !factory.get().isOpen,
                "Each owned application context must close its Animal factory",
            )
            check(!source.isClosed, "Closing a context must not close the externally owned fixture pool")
            released()
        }
    }

    private fun create(mvc: MockMvc, member: Long, notice: String?, care: String, breed: String): JsonNode {
        val created = send(
            mvc,
            "POST",
            ROOT,
            token(member),
            JSON.writeValueAsString(body(notice, care, breed)),
            201,
        )
        remember(created)
        return created
    }

    private fun remember(created: JsonNode): Long {
        val id = created.path("id").asLong()
        check(id > 0 && id != FOREIGN_ANIMAL, "A successful create must return a new owned Animal identity")
        val key = created.path("specialMark").asText()
        check(ownedKeys.contains(key), "A response must preserve its pre-dispatch owned request key")
        val observed = jdbc.query(
            "SELECT id FROM pawbridge_animal.animals WHERE special_mark=?",
            RowMapper { row, _ -> row.getLong(1) },
            key,
        )
        check(
            observed.size == 1 && observed.first() == id,
            "The response identity must match the exact pre-dispatch request key in the database",
        )
        ownedIds.add(id)
        return id
    }

    private fun body(notice: String?, care: String, breed: String): MutableMap<String, Any?> {
        val body = LinkedHashMap<String, Any?>()
        // Create DTO/mapper expose specialMark but not the entity's unique apmsDesertionNo.
        // This UUID key is observed by exact equality; database uniqueness is not assumed.
        val key = "OWNED-P8-" + TestFaker.uuid()
        ownedKeys.add(key)
        body["specialMark"] = key
        body["careRegNo"] = care
        if (notice != null) {
            body["apmsNoticeNo"] = notice
        }
        body["noticeStartDate"] = "2026-10-20"
        body["noticeEndDate"] = "2026-10-30"
        body["species"] = "DOG"
        body["gender"] = "MALE"
        body["neuterStatus"] = "NO"
        body["status"] = "PROTECT"
        body["breed"] = breed
        body["description"] = "initial synthetic description"
        return body
    }

    private fun animal(id: Long): Map<String, Any?> {
        return jdbc.queryForMap("SELECT * FROM pawbridge_animal.animals WHERE id=?", id)
    }

    private fun commandAudit(id: Long): Map<String, LocalDateTime> {
        return jdbc.queryForObject(
            "SELECT created_at,updated_at FROM pawbridge_animal.animals WHERE id=?",
            RowMapper<Map<String, LocalDateTime>> { row, _ ->
                // Match Hibernate's configured JDBC UTC Calendar before its Timestamp -> LocalDateTime conversion.
                val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                mapOf(
                    "created_at" to row.getTimestamp("created_at", utc).toLocalDateTime(),
                    "updated_at" to row.getTimestamp("updated_at", utc).toLocalDateTime(),
                )
            },
            id,
        )!!
    }

    private fun projection(id: Long, revision: Long) {
        val value = jdbc.queryForMap(
            "SELECT a.search_revision,a.search_dirty,d.source_revision,d.analyzer_version,d.tokens::text AS tokens " +
                "FROM pawbridge_animal.animals a JOIN pawbridge_animal.animal_search_documents d ON d.animal_id=a.id WHERE a.id=?",
            id,
        )
        check(
            (value["search_revision"] as Number).toLong() == revision &&
                (value["source_revision"] as Number).toLong() == revision &&
                false == value["search_dirty"] && KoreanSearchAnalyzer.VERSION == value["analyzer_version"] &&
                !(value["tokens"] as String).isBlank(),
            "The persisted Nori projection and source revision must commit together",
        )
    }

    private fun count(sql: String, value: Any): Long {
        return jdbc.queryForObject(sql, Long::class.javaObjectType, value)!!
    }

    private fun fingerprint(): Map<String, Fingerprint> {
        val result = LinkedHashMap<String, Fingerprint>()
        TABLE_KEYS.forEach { (table, key) ->
            val value = jdbc.queryForMap(
                "SELECT count(*) AS n,md5(COALESCE(string_agg(row_to_json(owned_row)::text,',' ORDER BY " +
                    key + "),'')) AS digest FROM " + table + " owned_row",
            )
            result[table] = Fingerprint((value["n"] as Number).toLong(), value["digest"] as String)
        }
        return result
    }

    private fun released() {
        val source = pool()
        check(
            !TransactionSynchronizationManager.isActualTransactionActive() &&
                !TransactionSynchronizationManager.hasResource(source),
            "No Animal transaction or User JDBC binding may escape the operation",
        )
        check(
            source.hikariPoolMXBean.activeConnections == 0 && source.hikariPoolMXBean.threadsAwaitingConnection == 0,
            "The single writer pool must return its connection after success or rollback",
        )
    }

    private fun interface Checked {
        fun run()
    }

    private fun interface HttpCheck {
        fun run(mvc: MockMvc, context: ApplicationContext)
    }

    private data class Fingerprint(val count: Long, val digest: String)

    private class Settings(
        val jdbcUrl: String,
        val database: String,
        val username: String,
        val password: String,
        val marker: String,
    ) {
        override fun toString(): String = "OwnedAnimalCommandSettings[redacted]"

        companion object {
            fun load(): Settings {
                val path = System.getenv("P8_PG_SETTINGS")
                check(path != null && path.isNotBlank(), "P8_PG_SETTINGS is mandatory; owned tests must not skip")
                val file = Path.of(path)
                check(
                    file.isAbsolute && file.normalize() == file &&
                        Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file),
                    "Settings must be an absolute regular non-symlink file",
                )
                check(
                    Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS) ==
                        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE) &&
                        (Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toInt() == 1,
                    "Settings must have exactly 0600 permissions and one hard link",
                )
                check(
                    System.getProperty("user.name") == Files.getOwner(file).name &&
                        Files.getOwner(file) == Files.getOwner(file.parent),
                    "Settings and parent must belong to this process owner",
                )
                var parent: Path? = file.parent
                while (parent != null) {
                    check(!Files.isSymbolicLink(parent), "Settings ancestors must not be symbolic links")
                    parent = parent.parent
                }
                val parentPermissions = Files.getPosixFilePermissions(file.parent, LinkOption.NOFOLLOW_LINKS)
                check(
                    !parentPermissions.contains(PosixFilePermission.GROUP_WRITE) &&
                        !parentPermissions.contains(PosixFilePermission.OTHERS_WRITE),
                    "Settings parent must reject writes from other users",
                )
                val values = Properties()
                Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input: InputStream -> values.load(input) }
                val database = required(values, "database")
                val url = required(values, "jdbcUrl")
                check(
                    database.matches(Regex("pb_animal_command_owned_[0-9a-f]{12}")) &&
                        url == "jdbc:postgresql://127.0.0.1:52620/" + database,
                    "Only the explicitly owned fixed loopback database is allowed",
                )
                val username = required(values, "username")
                val marker = required(values, "marker")
                check(
                    username == "pb_animal_command_writer" && marker.matches(Regex("[0-9a-f]{32}")),
                    "Writer and fixture marker must be explicit",
                )
                return Settings(url, database, username, required(values, "password"), marker)
            }

            private fun required(values: Properties, key: String): String {
                val value = values.getProperty(key)
                check(value != null && value.isNotBlank(), "Every owned setting is required")
                return value
            }
        }
    }

    private fun verifyServer(selected: JdbcTemplate, settings: Settings) {
        selected.queryTimeout = 5
        val identity = selected.queryForMap(
            "SELECT current_database() AS db,current_user AS actor,session_user AS session," +
                "current_setting('server_version_num') AS version,(SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()) AS owner",
        )
        check(
            settings.database == identity["db"] && settings.username == identity["actor"] &&
                settings.username == identity["session"] && "170011" == identity["version"] &&
                settings.username != identity["owner"],
            "Owned database, PostgreSQL version and separate writer identity must match",
        )
        val markers = selected.query("SELECT marker FROM public.pawbridge_owned_fixture", RowMapper { row, _ -> row.getString(1) })
        check(markers.size == 1 && settings.marker == markers.first(), "The exact isolated fixture marker must match")
        check(
            true == selected.queryForObject(
                "SELECT NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole " +
                    "AND NOT pg_has_role(current_user,(SELECT datdba FROM pg_database WHERE datname=current_database()),'MEMBER') " +
                    "FROM pg_roles WHERE rolname=current_user",
                Boolean::class.javaObjectType,
            ),
            "The writer must not inherit administrative or owner privileges",
        )
        for (schema in listOf("public", "pawbridge_animal", "pawbridge_user")) {
            check(
                false == selected.queryForObject(
                    "SELECT has_schema_privilege(current_user,?,'CREATE')",
                    Boolean::class.javaObjectType,
                    schema,
                ),
                "The selected writer must not create schema objects",
            )
        }
        for (table in TABLE_KEYS.keys) {
            check(
                true == selected.queryForObject(
                    "SELECT has_table_privilege(current_user,?,'SELECT') " +
                        "AND NOT has_table_privilege(current_user,?,'TRUNCATE,REFERENCES,TRIGGER')",
                    Boolean::class.javaObjectType,
                    table,
                    table,
                ),
                "Only the selected nonadministrative fixture table grants may be used",
            )
        }
        check(
            true == selected.queryForObject(
                "SELECT has_column_privilege(current_user,'pawbridge_user.users','user_id','UPDATE') " +
                    "AND NOT has_column_privilege(current_user,'pawbridge_user.users','role','UPDATE') " +
                    "AND NOT has_column_privilege(current_user,'pawbridge_user.users','care_reg_no','UPDATE') " +
                    "AND NOT has_table_privilege(current_user,'pawbridge_user.users','INSERT,DELETE,UPDATE') " +
                    "AND NOT has_table_privilege(current_user,'pawbridge_user.contact_deletions','INSERT,UPDATE,DELETE')",
                Boolean::class.javaObjectType,
            ),
            "The actor writer may lock User rows but must not edit roles, shelter identity or deletion state",
        )
    }

    companion object {
        private const val ROOT = "/api/v1/animals"
        private const val ADMIN = 910001L
        private const val SHELTER = 910002L
        private const val WRONG_SHELTER = 910003L
        private const val FOREIGN_ANIMAL = 990020L
        private val KEY: SecretKey = Keys.hmacShaKeyFor(
            "owned-p8-public-synthetic-signing-key-20261020-only".toByteArray(StandardCharsets.UTF_8),
        )
        private val JSON: ObjectMapper = ObjectMapper().findAndRegisterModules()
        private val TABLE_KEYS: Map<String, String> = mapOf(
            "pawbridge_user.users" to "user_id",
            "pawbridge_user.contact_deletions" to "user_id",
            "pawbridge_animal.shelters" to "id",
            "pawbridge_animal.animals" to "id",
            "pawbridge_animal.chatbot_sessions" to "id",
            "pawbridge_animal.animal_search_documents" to "animal_id",
            "pawbridge_animal.shelter_search_documents" to "shelter_id",
        )

        private fun token(member: Long): String = token(member, "ROLE_USER")

        private fun token(member: Long, staleRole: String): String {
            return "Bearer " + Jwts.builder()
                .claim("userId", member)
                .claim("role", staleRole)
                .expiration(Date(System.currentTimeMillis() + 300_000))
                .signWith(KEY)
                .compact()
        }

        private fun send(
            mvc: MockMvc,
            method: String,
            path: String,
            authorization: String?,
            body: String?,
            expected: Int,
        ): JsonNode {
            val request = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path)
                .servletPath(path)
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8)
                .header("X-User-Id", ADMIN.toString())
                .header("X-User-Role", "ROLE_ADMIN")
            if (authorization != null) {
                request.header("Authorization", authorization)
            }
            if (body != null) {
                request.content(body)
            }
            val response = mvc.perform(request).andReturn().response
            check(
                response.status == expected,
                "Unexpected Animal HTTP status: expected=" + expected + ", actual=" + response.status,
            )
            check(response.getHeader("Set-Cookie") == null, "The stateless contract must not issue a session cookie")
            val content = response.getContentAsString(StandardCharsets.UTF_8)
            return if (content.isEmpty()) JSON.nodeFactory.nullNode() else JSON.readTree(content)
        }

        private fun observed(phase: String, action: Checked) {
            try {
                action.run()
            } catch (failure: AssertionError) {
                throw failure
            } catch (failure: Throwable) {
                throw AssertionError(
                    "Owned Animal command observation failed: " + phase + "; type=" + failure.javaClass.name,
                )
            }
        }

        private fun check(condition: Boolean, fixedMessage: String) {
            if (!condition) {
                throw AssertionError(fixedMessage)
            }
        }
    }
}
