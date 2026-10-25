package com.pawbridge.backend.boot.configuration

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.sheltercommand.application.ShelterCommands
import com.pawbridge.backend.boot.security.PostTokenAuthenticator
import com.pawbridge.backend.support.TestFaker
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembershipRejectedException
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import jakarta.persistence.EntityManager
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
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.autoconfigure.data.web.SpringDataWebAutoConfiguration
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.util.Calendar
import java.util.Date
import java.util.Properties
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier
import javax.crypto.SecretKey
import javax.sql.DataSource

/** Candidate secured Shelter writes and actual approval locks, only on the new P15 owned database. */
@Tag("owned-postgresql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(45)
class OwnedPostgresqlShelterCommandTest {
    private lateinit var settings: Settings
    private var pool: HikariDataSource? = null
    private lateinit var probe: DriverManagerDataSource
    private lateinit var jdbc: JdbcTemplate
    private lateinit var baseline: Map<String, Fingerprint>
    private val creations = LinkedHashMap<String, Long?>()
    private val applicants = LinkedHashMap<Long, Map<String, Any?>>()
    private val applications = LinkedHashMap<Long, Map<String, Any?>>()
    private lateinit var ownShelter: Map<String, Any?>
    private var unresolvedWorkers = false

    private fun pool(): HikariDataSource = pool!!

    @BeforeAll
    fun verify__new_owned_identity_and_exact_mutable_rows_before_application_connections() {
        observed("P15 isolated identity") {
            settings = Settings.load()
            probe = DriverManagerDataSource(settings.jdbcUrl, settings.username, settings.password)
            val timeout = Properties()
            timeout.setProperty("connectTimeout", "3")
            timeout.setProperty("socketTimeout", "5")
            probe.setConnectionProperties(timeout)
            verifyServer(JdbcTemplate(probe))
            val config = HikariConfig()
            config.jdbcUrl = settings.jdbcUrl
            config.username = settings.username
            config.password = settings.password
            config.poolName = "owned-p15-shelter-writer"
            config.maximumPoolSize = 4
            config.minimumIdle = 0
            config.connectionTimeout = 2000
            config.validationTimeout = 1000
            config.initializationFailTimeout = -1
            config.isReadOnly = false
            config.addDataSourceProperty("connectTimeout", "3")
            config.addDataSourceProperty("socketTimeout", "10")
            val source = HikariDataSource(config)
            pool = source
            jdbc = JdbcTemplate(source)
            jdbc.queryTimeout = 5
            for (id in listOf(910008L, 910009L)) {
                val row: Map<String, Any?> =
                    jdbc.queryForMap("SELECT *,updated_at::text AS audit_text FROM pawbridge_user.users WHERE user_id=?", id)
                check(
                    "ROLE_USER" == row["role"] && row["care_reg_no"] == null,
                    "Only the two exact unassociated applicants may be restored",
                )
                applicants[id] = row
            }
            for (id in listOf(910101L, 910102L)) {
                val row: Map<String, Any?> = jdbc.queryForMap(
                    "SELECT *,reviewed_at::text AS review_time_text FROM pawbridge_user.shelter_applications WHERE id=?",
                    id,
                )
                check(
                    (row["user_id"] as Number).toLong() == id - 93 && "PENDING" == row["status"],
                    "Only the exact applicant-owned pending requests may be restored",
                )
                applications[id] = row
            }
            ownShelter = jdbc.queryForMap("SELECT *,updated_at::text AS audit_text FROM pawbridge_animal.shelters WHERE id=990010")
            check("owned-care-a" == ownShelter["care_reg_no"], "Only the pre-owned own-care shelter permits nullable-field restoration")
            baseline = fingerprint()
            released()
        }
    }

    @BeforeEach
    fun require__all_eleven_original_table_fingerprints() {
        check(!unresolvedWorkers && fingerprint() == baseline, "Every case requires the complete eleven-table baseline")
    }

    @AfterEach
    fun restore__only_registered_new_shelters_and_exact_owned_review_fields() {
        observed("P15 exact owned restoration") {
            check(!unresolvedWorkers, "Unsettled workers forbid all restoration")
            released()
            for (entry in applications.entries) {
                val id = entry.key
                val row = entry.value
                check(id == 910101L || id == 910102L, "No foreign application may enter restoration")
                check(
                    jdbc.update(
                        "UPDATE pawbridge_user.shelter_applications SET status=?,reviewed_by=?,reviewed_at=CAST(? AS timestamp),review_note=?,care_reg_no=? WHERE id=? AND user_id=? AND shelter_name=? AND requested_at=?",
                        row["status"], row["reviewed_by"], row["review_time_text"], row["review_note"], row["care_reg_no"], id,
                        row["user_id"], row["shelter_name"], row["requested_at"],
                    ) == 1,
                    "Owned request identity must remain unchanged",
                )
            }
            for (entry in applicants.entries) {
                val id = entry.key
                val row = entry.value
                check(id == 910008L || id == 910009L, "No foreign member may enter restoration")
                check(
                    jdbc.update(
                        "UPDATE pawbridge_user.users SET role=?,care_reg_no=?,updated_at=CAST(? AS timestamp) WHERE user_id=? AND name=? AND email=?",
                        row["role"], row["care_reg_no"], row["audit_text"], id, row["name"], row["email"],
                    ) == 1,
                    "Owned applicant identity must remain unchanged",
                )
            }
            check(
                jdbc.update(
                    "UPDATE pawbridge_animal.shelters SET phone=?,email=?,introduction=?,adoption_procedure=?,operating_hours=?,updated_at=CAST(? AS timestamp) WHERE id=990010 AND care_reg_no='owned-care-a' AND name=?",
                    ownShelter["phone"], ownShelter["email"], ownShelter["introduction"], ownShelter["adoption_procedure"],
                    ownShelter["operating_hours"], ownShelter["audit_text"], ownShelter["name"],
                ) == 1,
                "Own shelter identity must remain unchanged",
            )
            for (entry in creations.entries) {
                val care = entry.key
                check(care.matches(Regex("p15-[0-9a-f]{32}")), "Only dispatch-registered UUID care numbers may be removed")
                val ids = jdbc.query(
                    "SELECT id FROM pawbridge_animal.shelters WHERE care_reg_no=? AND name=?",
                    RowMapper { r, _ -> r.getLong(1) },
                    care,
                    name(care),
                )
                check(ids.size <= 1, "Owned creation identity must be unique")
                if (ids.isNotEmpty()) {
                    val id = ids.first()
                    check(id > 990100 && (entry.value == null || entry.value == id), "Only the registered response identity may be cleaned")
                    check(
                        jdbc.update(
                            "DELETE FROM pawbridge_animal.shelters WHERE id=? AND care_reg_no=? AND name=?",
                            id, care, name(care),
                        ) == 1,
                        "Owned creation cleanup must affect exactly its registered row",
                    )
                }
            }
            creations.clear()
            check(fingerprint() == baseline, "Every original row in all eleven tables must return byte-for-value to baseline")
            released()
        }
    }

    @AfterAll
    fun close__only_after_all_workers_and_bindings_are_returned() {
        check(!unresolvedWorkers, "Unsettled workers forbid pool close")
        val source = pool
        if (source != null) {
            released()
            source.close()
            check(source.isClosed, "The P15 pool must close")
        }
    }

    @Test
    fun `현재 DB 역할이 주어졌을 때__보호된 보호소 쓰기를 실행하면__응답을 유지하고 타 보호소·대기·위조·연결 삭제를 거절한다`() {
        http { mvc, _ ->
            val created = create(mvc)
            val afterCreate = fingerprint()
            for (id in listOf(910004L, 910005L, 910006L, 910099L)) {
                send(mvc, "PUT", ROOT + "/" + created, token(id, "ROLE_SHELTER"), mapOf(), 403)
            }
            send(mvc, "POST", ROOT, token(OWN, "ROLE_ADMIN"), newBody(newOwnedCare()), 403)
            send(mvc, "POST", ROOT, token(910004, "ROLE_ADMIN"), newBody(newOwnedCare()), 403)
            send(mvc, "DELETE", ROOT + "/" + created, token(OWN, "ROLE_ADMIN"), null, 403)
            send(mvc, "PUT", ROOT + "/990010", token(FOREIGN, "ROLE_SHELTER"), mapOf(), 403)
            send(mvc, "PUT", ROOT + "/990010", null, mapOf(), 401)
            check(fingerprint() == afterCreate, "Denied or forged requests must not mutate any table")
            val cleared = LinkedHashMap<String, Any?>()
            for (field in listOf("phone", "email", "introduction", "adoptionProcedure", "operatingHours")) {
                cleared[field] = null
            }
            val changed = send(mvc, "PUT", ROOT + "/990010", token(OWN, "ROLE_SHELTER"), cleared, 200)
            for (field in cleared.keys) {
                check(!changed.has(field), "Nullable update fields must retain source non-null JSON inclusion")
            }
            check(
                true == jdbc.queryForObject(
                    "SELECT phone IS NULL AND email IS NULL AND introduction IS NULL AND adoption_procedure IS NULL AND operating_hours IS NULL FROM pawbridge_animal.shelters WHERE id=990010",
                    Boolean::class.javaObjectType,
                ),
                "All five nullable fields must commit on the own shelter",
            )
            send(mvc, "PUT", ROOT + "/" + created, token(ADMIN, "ROLE_ADMIN"), mapOf("phone" to "p15-phone"), 200)
            send(mvc, "PUT", ROOT + "/999999", token(ADMIN, "ROLE_ADMIN"), mapOf(), 500)
            send(mvc, "POST", ROOT, token(ADMIN, "ROLE_ADMIN"), mapOf("careRegNo" to "", "name" to "x"), 400)
            send(mvc, "PUT", ROOT + "/" + created, token(ADMIN, "ROLE_ADMIN"), mapOf("email" to "not-an-email"), 400)
            for (linked in listOf(990010L, 990012L, 990013L, 990014L, 990015L)) {
                send(mvc, "DELETE", ROOT + "/" + linked, token(ADMIN, "ROLE_ADMIN"), null, 409)
            }
            send(mvc, "DELETE", ROOT + "/" + created, token(ADMIN, "ROLE_ADMIN"), null, 204)
            check(
                jdbc.queryForObject(
                    "SELECT count(*) FROM pawbridge_animal.shelters WHERE id=?",
                    Long::class.javaObjectType,
                    created,
                ) == 0L,
                "An unlinked owned creation must be physically removed",
            )
        }
    }

    @Test
    fun `이전 감사나 null 감사 보호소가 주어졌을 때__HTTP로 수정하면__커밋된 행과 같은 감사를 돌려준다`() {
        http { mvc, _ ->
            val id = create(mvc)
            val care = care(id)
            val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            val created = jdbc.queryForObject(
                "SELECT created_at FROM pawbridge_animal.shelters WHERE id=? AND care_reg_no=?",
                RowMapper { row, _ -> row.getTimestamp(1, utc).toLocalDateTime() },
                id,
                care,
            )
            var previous = jdbc.queryForObject(
                "SELECT updated_at FROM pawbridge_animal.shelters WHERE id=? AND care_reg_no=?",
                RowMapper { row, _ -> row.getTimestamp(1, utc).toLocalDateTime() },
                id,
                care,
            )!!
            for (revision in 0 until 2) {
                if (revision == 1) {
                    check(
                        jdbc.update(
                            "UPDATE pawbridge_animal.shelters SET updated_at=NULL WHERE id=? AND care_reg_no=? AND name=?",
                            id, care, name(care),
                        ) == 1,
                        "Only the registered owned creation may model a legacy null audit",
                    )
                }
                val response = send(mvc, "PUT", ROOT + "/" + id, token(ADMIN, "ROLE_ADMIN"), mapOf("phone" to "p15-audit-" + revision), 200)
                val returned = JSON.treeToValue(response.path("updatedAt"), LocalDateTime::class.java)
                val committed = jdbc.queryForObject(
                    "SELECT updated_at FROM pawbridge_animal.shelters WHERE id=? AND care_reg_no=?",
                    RowMapper { row, _ -> row.getTimestamp(1, utc).toLocalDateTime() },
                    id,
                    care,
                )!!
                check(returned != null && returned.isAfter(previous), "The response must expose the advanced auditing value, including a legacy null value")
                check(
                    Duration.between(returned, committed).abs().compareTo(Duration.ofNanos(1000)) < 0,
                    "Response and committed audit must match within PostgreSQL microsecond precision",
                )
                check(
                    created == JSON.treeToValue(response.path("createdAt"), LocalDateTime::class.java),
                    "Updating must preserve creation audit",
                )
                previous = committed
            }
        }
    }

    @Test
    fun `관측 이력이 있는 보호소가 주어졌을 때__실제 삭제가 롤백 안에서 flush되면__해당 행만 연쇄 삭제하고 관측 이력은 남긴다`() {
        http { _, context ->
            val manager = context.getBean("animalCommandJpaTransactionManager", JpaTransactionManager::class.java)
            val tx = writer(manager)
            val commands = context.getBean(ShelterCommands::class.java)
            val em = context.getBean("animalCommandEntityManager", EntityManager::class.java)
            val history = fingerprint()["pawbridge_animal.shelter_daily_observations"]
            check(
                count("pawbridge_animal.shelter_search_documents", "shelter_id", 990016) == 1L &&
                    count("pawbridge_animal.shelter_public_information", "shelter_id", 990016) == 1L,
                "Clear fixture must exercise both real cascade rows",
            )
            tx.executeWithoutResult { status ->
                commands.delete(ADMIN, 990016L)
                em.flush()
                check(
                    count("pawbridge_animal.shelters", "id", 990016) == 0L &&
                        count("pawbridge_animal.shelter_search_documents", "shelter_id", 990016) == 0L &&
                        count("pawbridge_animal.shelter_public_information", "shelter_id", 990016) == 0L,
                    "Actual flush must cascade projection and public information deletion",
                )
                check(
                    fingerprint()["pawbridge_animal.shelter_daily_observations"] == history,
                    "Directory deletion must retain the non-FK observation history",
                )
                status.setRollbackOnly()
            }
            check(fingerprint() == baseline, "Rollback must restore all eleven tables after real cascade SQL")
        }
    }

    @Test
    fun `승인이 KEY SHARE를 쥐었을 때__다른 관리자가 삭제하면__FOR UPDATE가 막고 커밋된 멤버십은 409를 돌려준다`() {
        http { mvc, context ->
            val shelter = create(mvc)
            val registration = care(shelter)
            val memberships = context.getBean(ShelterMembership::class.java)
            val tx = TransactionTemplate(context.getBean("writeTransactionManager", DataSourceTransactionManager::class.java))
            tx.isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
            val worker = Executors.newSingleThreadExecutor()
            val child = AtomicReference<Future<HttpResult>>()
            unresolvedWorkers = true
            try {
                tx.executeWithoutResult {
                    memberships.approve(ADMIN, 910101, registration, "P15 owned approval")
                    val holder: Int = jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.javaObjectType)!!
                    child.set(
                        worker.submit(Callable { exchange(mvc, "DELETE", ROOT + "/" + shelter, token(OTHER_ADMIN, "ROLE_ADMIN"), null) }),
                    )
                    val blocked = awaitBlocked(holder, "for update")
                    check(blocked != holder, "Deletion must use another physical PostgreSQL PID blocked by KEY SHARE")
                }
                check(
                    child.get().get(8, TimeUnit.SECONDS).status == 409,
                    "After approval commits, waiting deletion must observe the newly linked User and return409",
                )
                check(
                    count("pawbridge_animal.shelters", "id", shelter) == 1L &&
                        true == jdbc.queryForObject(
                            "SELECT role='ROLE_SHELTER' AND care_reg_no=? FROM pawbridge_user.users WHERE user_id=910008",
                            Boolean::class.javaObjectType,
                            registration,
                        ),
                    "Committed approval must retain both the shelter and the exact member association",
                )
                check(
                    true == jdbc.queryForObject(
                        "SELECT status='APPROVED' AND care_reg_no=? AND reviewed_by=910001 AND reviewed_at IS NOT NULL FROM pawbridge_user.shelter_applications WHERE id=910101 AND user_id=910008",
                        Boolean::class.javaObjectType,
                        registration,
                    ),
                    "Approval review and member association must be visible together after the writer commit",
                )
            } finally {
                settle(worker)
            }
        }
    }

    @Test
    fun `삭제가 FOR UPDATE를 쥐었을 때__승인이 읽기를 마치고 KEY SHARE를 기다리면__삭제가 커밋되고 승인은 롤백된다`() {
        http { mvc, context ->
            val created = create(mvc)
            val care = care(created)
            val before = fingerprint()
            val commands = context.getBean(ShelterCommands::class.java)
            val tx = writer(context.getBean("animalCommandJpaTransactionManager", JpaTransactionManager::class.java))
            val worker = Executors.newSingleThreadExecutor()
            val child = AtomicReference<Future<HttpResult>>()
            unresolvedWorkers = true
            try {
                tx.executeWithoutResult {
                    // Keep delete pending until outer commit, allowing the actual independent full-detail read first.
                    commands.delete(OTHER_ADMIN, created)
                    val holder: Int = jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.javaObjectType)!!
                    child.set(
                        worker.submit(
                            Callable {
                                exchange(
                                    mvc, "POST", "/api/v1/admin/users/shelter-applications/910102/approve", token(ADMIN, "ROLE_ADMIN"),
                                    mapOf("careRegNo" to care, "note" to "P15 reverse race"),
                                )
                            },
                        ),
                    )
                    val blocked = awaitBlocked(holder, "for key share")
                    check(
                        blocked != holder,
                        "Approval must reach its actual post-detail KEY SHARE on a separate physical PostgreSQL PID",
                    )
                }
                val result = child.get().get(8, TimeUnit.SECONDS)
                check(result.status == 503, "Committed deletion must make the post-lock approval unavailable and roll back its User TX")
                check(
                    result.body.path("message").asText() == ShelterMembershipRejectedException.Reason.SHELTER_UNAVAILABLE.message(),
                    "The existing fixed shelter-unavailable response must remain the public failure",
                )
                check(count("pawbridge_animal.shelters", "id", created) == 0L, "The unlinked new shelter deletion must physically commit")
                val after = fingerprint()
                for (table in TABLE_KEYS.keys) {
                    if (table != "pawbridge_animal.shelters" && table != "pawbridge_animal.shelter_search_documents") {
                        check(
                            before[table] == after[table],
                            "Failed approval must preserve User/application and every unrelated table",
                        )
                    }
                }
            } finally {
                settle(worker)
            }
        }
    }

    @Test
    fun `상속된 REPEATABLE READ나 읽기 전용이 주어졌을 때__보호소 쓰기를 시작하면__업무 전에 거절하고 모든 테이블을 롤백한다`() {
        http { _, context ->
            val manager = context.getBean("animalCommandJpaTransactionManager", JpaTransactionManager::class.java)
            val commands = context.getBean(ShelterCommands::class.java)
            for (readOnly in listOf(false, true)) {
                val outer = TransactionTemplate(manager)
                outer.isolationLevel =
                    if (readOnly) TransactionDefinition.ISOLATION_READ_COMMITTED else TransactionDefinition.ISOLATION_REPEATABLE_READ
                outer.isReadOnly = readOnly
                var rejected = false
                try {
                    outer.executeWithoutResult {
                        commands.update(ADMIN, 990010L, ShelterCommands.Update("must-not-commit", null, null, null, null))
                    }
                } catch (expected: IllegalTransactionStateException) {
                    rejected = true
                }
                check(rejected, "Actual JpaTM must reject inherited RR or RO before invoking the Shelter write callback")
                check(fingerprint() == baseline, "Rejected inherited transaction must preserve every original table")
                released()
            }
        }
    }

    @Test
    fun `보충 문자가 주어졌을 때__소유 보호소를 등록·수정하면__원래 400을 돌려주고 비공개 DB 로그는 없다`() {
        http { mvc, _ ->
            val created = create(mvc)
            val before = fingerprint()
            val nonce = TestFaker.uuid().toString().replace("-", "")
            val marker = "OWNED-P15-PRIVATE-" + nonce
            val address = "p15-private-address-" + nonce
            val email = "p15-private-" + nonce + "@invalid.example"
            val unsupported = marker + String(Character.toChars(0x1F600))
            val logger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
            val events = ListAppender<ILoggingEvent>()
            events.start()
            logger.addAppender(events)
            try {
                val update = send(
                    mvc,
                    "PUT",
                    ROOT + "/" + created,
                    token(ADMIN, "ROLE_ADMIN"),
                    mapOf(
                        "phone" to "p15-private-phone-" + nonce,
                        "email" to email,
                        "introduction" to unsupported,
                        "adoptionProcedure" to "p15-private-procedure",
                        "operatingHours" to "p15-private-hours",
                    ),
                    400,
                )
                check(
                    update.path("code").asInt() == 400 &&
                        update.path("message").asText() == "일부 이모지 등 지원하지 않는 문자를 제외해 주세요." &&
                        update.path("data").isNull,
                    "Owned update must retain the original fixed rollback-charset400 envelope",
                )
                check(fingerprint() == before, "Rejected supplementary update must preserve all eleven tables")
                released()
                val registration = newOwnedCare()
                val body = LinkedHashMap<String, Any?>(newBody(registration))
                body["address"] = address
                body["email"] = email
                body["introduction"] = unsupported
                val create = send(mvc, "POST", ROOT, token(ADMIN, "ROLE_ADMIN"), body, 400)
                check(
                    create.path("code").asInt() == 400 &&
                        create.path("message").asText() == "일부 이모지 등 지원하지 않는 문자를 제외해 주세요." &&
                        create.path("data").isNull,
                    "Owned create must retain the original fixed rollback-charset400 envelope",
                )
                check(fingerprint() == before, "Rejected supplementary create must preserve all eleven tables")
                released()
                for (event in events.list) {
                    check(
                        event.loggerName != "org.hibernate.engine.jdbc.spi.SqlExceptionHelper" && event.throwableProxy == null,
                        "Rollback charset rejection must not emit Hibernate SQL or throwable diagnostics",
                    )
                    val message = event.formattedMessage
                    check(
                        !message.contains("Failing row") && !message.contains("SqlExceptionHelper"),
                        "Rollback charset rejection must not emit a failed-row diagnostic",
                    )
                    for (privateValue in listOf(marker, "p15-address", "p15@example.invalid", address, email)) {
                        check(
                            !message.contains(privateValue),
                            "Rollback charset rejection must not log owned synthetic address, mail or payload markers",
                        )
                    }
                }
            } finally {
                logger.detachAppender(events)
                events.stop()
            }
        }
    }

    private fun create(mvc: MockMvc): Long {
        val care = newOwnedCare()
        val response = send(mvc, "POST", ROOT, token(ADMIN, "ROLE_ADMIN"), newBody(care), 201)
        val id = response.path("id").asLong()
        check(
            id > 990100 && response.size() == 13 && response.path("careRegNo").asText() == care &&
                response.path("name").asText() == name(care) && response.hasNonNull("createdAt") && response.hasNonNull("updatedAt"),
            "Creation must return the original thirteen-field detail and pre-registered identity",
        )
        creations[care] = id
        check(
            true == jdbc.queryForObject(
                "SELECT NOT s.search_dirty AND d.source_revision=s.search_revision AND d.analyzer_version IS NOT NULL FROM pawbridge_animal.shelters s JOIN pawbridge_animal.shelter_search_documents d ON d.shelter_id=s.id WHERE s.id=? AND s.care_reg_no=?",
                Boolean::class.javaObjectType,
                id,
                care,
            ),
            "Source row and real search projection must be independently visible after the same commit",
        )
        return id
    }

    private fun newOwnedCare(): String {
        val care = "p15-" + TestFaker.uuid().toString().replace("-", "")
        check(
            !creations.containsKey(care) &&
                jdbc.queryForObject(
                    "SELECT count(*) FROM pawbridge_animal.shelters WHERE care_reg_no=?",
                    Long::class.javaObjectType,
                    care,
                ) == 0L,
            "New care identity must be absent before dispatch",
        )
        creations[care] = null
        return care
    }

    private fun care(id: Long): String {
        return creations.entries.filter { e -> e.value != null && e.value == id }.map { e -> e.key }.firstOrNull()
            ?: throw AssertionError("Only a registered owned creation may enter a race")
    }

    private fun count(table: String, key: String, id: Long): Long {
        check(TABLE_KEYS.containsKey(table), "Only the eleven selected tables may be observed")
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + key + "=?", Long::class.javaObjectType, id)!!
    }

    private fun settle(worker: ExecutorService) {
        worker.shutdown()
        var done = false
        try {
            done = worker.awaitTermination(10, TimeUnit.SECONDS)
            if (!done) {
                worker.shutdownNow()
                done = worker.awaitTermination(3, TimeUnit.SECONDS)
            }
        } catch (interrupted: InterruptedException) {
            worker.shutdownNow()
            Thread.currentThread().interrupt()
            throw AssertionError("Interrupted workers remain fenced from owned restoration and pool close")
        } finally {
            unresolvedWorkers = !done
        }
        check(done, "Every child must terminate before any restoration, context close or pool close")
    }

    private fun awaitBlocked(holder: Int, lock: String): Int {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        do {
            try {
                probe.connection.use { control ->
                    control.prepareStatement("SELECT pg_stat_clear_snapshot()").use { clear -> clear.execute() }
                    control.prepareStatement(
                        "SELECT pid FROM pg_stat_activity WHERE datname=current_database() AND usename=current_user AND pid<>pg_backend_pid() AND state='active' AND wait_event_type='Lock' AND ?=ANY(pg_blocking_pids(pid)) AND query ILIKE '%pawbridge_animal.shelters%' AND query ILIKE ?",
                    ).use { query ->
                        query.setInt(1, holder)
                        query.setString(2, "%" + lock + "%")
                        query.queryTimeout = 2
                        query.executeQuery().use { rows ->
                            if (rows.next()) {
                                val pid = rows.getInt(1)
                                check(!rows.next(), "Only the controlled child may wait on the selected Shelter lock")
                                return pid
                            }
                        }
                    }
                    Thread.sleep(20)
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw AssertionError("Interrupted lock observation must not become success")
            } catch (failure: Exception) {
                throw AssertionError("P15 owned lock observation failed: " + failure.javaClass.name)
            }
        } while (System.nanoTime() < until)
        throw AssertionError("The actual child SQL must reach the exact controlled PostgreSQL blocking graph")
    }

    private fun http(action: HttpCheck) {
        observed("P15 actual secured MVC/JPA") {
            val source = pool()
            val factory = AtomicReference<EntityManagerFactory>()
            val userManager = DataSourceTransactionManager(source)
            WebApplicationContextRunner().withConfiguration(
                AutoConfigurations.of(
                    PropertyPlaceholderAutoConfiguration::class.java,
                    JacksonAutoConfiguration::class.java,
                    HttpMessageConvertersAutoConfiguration::class.java,
                    ValidationAutoConfiguration::class.java,
                    DispatcherServletAutoConfiguration::class.java,
                    WebMvcAutoConfiguration::class.java,
                    SpringDataWebAutoConfiguration::class.java,
                    SecurityAutoConfiguration::class.java,
                    SecurityFilterAutoConfiguration::class.java,
                ),
            )
                .withUserConfiguration(
                    ShelterCommandConfiguration::class.java,
                    ShelterMembershipConfiguration::class.java,
                    ReadSecurityConfiguration::class.java,
                )
                .withPropertyValues(
                    "pawbridge.shelter-commands.enabled=true",
                    "pawbridge.shelter-membership.enabled=true",
                    "spring.sql.init.mode=never",
                    "spring.jmx.enabled=false",
                )
                .withBean(
                    "writeDataSource",
                    DataSource::class.java,
                    Supplier { source },
                    BeanDefinitionCustomizer { d -> d.setDestroyMethodName("") },
                )
                .withBean(
                    "dataSource",
                    DataSource::class.java,
                    Supplier { source },
                    BeanDefinitionCustomizer { d -> d.setDestroyMethodName("") },
                )
                .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { jdbc })
                .withBean("writeTransactionManager", DataSourceTransactionManager::class.java, Supplier { userManager })
                .withBean("transactionManager", DataSourceTransactionManager::class.java, Supplier { userManager })
                .withBean("readClock", Clock::class.java, Supplier { Clock.systemUTC() })
                .withBean(PostTokenAuthenticator::class.java, Supplier { PostTokenAuthenticator(KEY) })
                .run { context ->
                    check(
                        context.startupFailure == null,
                        "The real Shelter writer, full module reads, membership and security must start",
                    )
                    factory.set(context.getBean("animalCommandEntityManagerFactory", EntityManagerFactory::class.java))
                    val mvc: MockMvc = MockMvcBuilders.webAppContextSetup(context)
                        .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
                        .build()
                    try {
                        action.run(mvc, context)
                    } catch (failure: AssertionError) {
                        throw failure
                    } catch (failure: Exception) {
                        throw AssertionError("P15 application observation failed: " + failure.javaClass.name)
                    }
                }
            check(
                !unresolvedWorkers && factory.get() != null && !factory.get().isOpen,
                "Only a settled P15 context may finish with its EntityManagerFactory closed",
            )
            check(!source.isClosed, "The context must leave its externally owned pool open")
            released()
        }
    }

    private fun fingerprint(): Map<String, Fingerprint> {
        val result = LinkedHashMap<String, Fingerprint>()
        TABLE_KEYS.forEach { (table, key) ->
            val row = jdbc.queryForMap(
                "SELECT count(*) AS n,md5(COALESCE(string_agg(row_to_json(t)::text,',' ORDER BY " + key + "),'')) AS digest FROM " + table + " t",
            )
            result[table] = Fingerprint((row["n"] as Number).toLong(), row["digest"] as String)
        }
        return result
    }

    private fun released() {
        val source = pool()
        check(
            !TransactionSynchronizationManager.isActualTransactionActive() && !TransactionSynchronizationManager.hasResource(source),
            "No caller TX or same-writer binding may escape a request",
        )
        check(
            source.hikariPoolMXBean.activeConnections == 0 && source.hikariPoolMXBean.threadsAwaitingConnection == 0,
            "All four P15 connections must return after success or rollback",
        )
    }

    private fun verifyServer(selected: JdbcTemplate) {
        selected.queryTimeout = 5
        val identity = selected.queryForMap(
            "SELECT current_database() AS db,current_user AS actor,session_user AS session,current_setting('server_version_num') AS version,(SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()) AS owner",
        )
        check(
            settings.database == identity["db"] && settings.username == identity["actor"] &&
                settings.username == identity["session"] && "170011" == identity["version"] &&
                settings.username != identity["owner"],
            "Only the exact isolated PostgreSQL17.11 database and separate writer are accepted",
        )
        val markers = selected.query("SELECT marker FROM public.pawbridge_owned_fixture", RowMapper { r, _ -> r.getString(1) })
        check(markers.size == 1 && settings.marker == markers.first(), "Only the exact single owned marker is accepted")
        check(
            true == selected.queryForObject(
                "SELECT NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT pg_has_role(current_user,(SELECT datdba FROM pg_database WHERE datname=current_database()),'MEMBER') FROM pg_roles WHERE rolname=current_user",
                Boolean::class.javaObjectType,
            ),
            "P15 writer must have no owner or administrator inheritance",
        )
        for (schema in listOf("public", "pawbridge_animal", "pawbridge_user")) {
            check(
                false == selected.queryForObject(
                    "SELECT has_schema_privilege(current_user,?,'CREATE')",
                    Boolean::class.javaObjectType,
                    schema,
                ),
                "P15 writer may not create schema objects",
            )
        }
        for (table in TABLE_KEYS.keys) {
            check(
                true == selected.queryForObject(
                    "SELECT has_table_privilege(current_user,?,'SELECT') AND NOT has_table_privilege(current_user,?,'TRUNCATE,REFERENCES,TRIGGER')",
                    Boolean::class.javaObjectType,
                    table,
                    table,
                ),
                "Only observed tables with no administration privileges are allowed",
            )
        }
        for (table in listOf(
            "pawbridge_animal.animals",
            "pawbridge_animal.outbox_events",
            "pawbridge_animal.chatbot_sessions",
            "pawbridge_animal.animal_search_documents",
            "pawbridge_animal.shelter_public_information",
            "pawbridge_animal.shelter_daily_observations",
            "pawbridge_user.contact_deletions",
        )) {
            check(
                false == selected.queryForObject(
                    "SELECT has_table_privilege(current_user,?,'INSERT,UPDATE,DELETE')",
                    Boolean::class.javaObjectType,
                    table,
                ),
                "Only Shelter and exact User approval columns may mutate",
            )
        }
        check(
            true == selected.queryForObject(
                "SELECT has_table_privilege(current_user,'pawbridge_animal.shelters','INSERT') AND has_table_privilege(current_user,'pawbridge_animal.shelters','UPDATE') AND has_table_privilege(current_user,'pawbridge_animal.shelters','DELETE') AND has_table_privilege(current_user,'pawbridge_animal.shelter_search_documents','INSERT') AND has_table_privilege(current_user,'pawbridge_animal.shelter_search_documents','UPDATE') AND NOT has_table_privilege(current_user,'pawbridge_animal.shelter_search_documents','DELETE') AND NOT has_table_privilege(current_user,'pawbridge_user.users','INSERT,DELETE') AND has_table_privilege(current_user,'pawbridge_user.shelter_applications','INSERT') AND has_table_privilege(current_user,'pawbridge_user.shelter_applications','UPDATE') AND NOT has_table_privilege(current_user,'pawbridge_user.shelter_applications','DELETE')",
                Boolean::class.javaObjectType,
            ),
            "P15 fixture must retain exact CUD and no forbidden User/search deletion privileges",
        )
        for (column in listOf("user_id", "role", "care_reg_no", "updated_at")) {
            check(
                true == selected.queryForObject(
                    "SELECT has_column_privilege(current_user,'pawbridge_user.users',?,'UPDATE')",
                    Boolean::class.javaObjectType,
                    column,
                ),
                "Only the selected User approval and row-lock columns may be writable",
            )
        }
        for (column in listOf("name", "email")) {
            check(
                false == selected.queryForObject(
                    "SELECT has_column_privilege(current_user,'pawbridge_user.users',?,'UPDATE')",
                    Boolean::class.javaObjectType,
                    column,
                ),
                "User identity columns must stay read-only",
            )
        }
    }

    private fun interface Checked {
        fun run()
    }

    private fun interface HttpCheck {
        fun run(mvc: MockMvc, context: ApplicationContext)
    }

    private class HttpResult(val status: Int, val body: JsonNode)

    private class Fingerprint(val count: Long, val digest: String) {
        override fun equals(other: Any?): Boolean {
            return other is Fingerprint && count == other.count && digest == other.digest
        }

        override fun hashCode(): Int = 31 * count.hashCode() + digest.hashCode()
    }

    private class Settings(
        val jdbcUrl: String,
        val database: String,
        val username: String,
        val password: String,
        val marker: String,
    ) {
        override fun toString(): String = "OwnedShelterSettings[redacted]"

        companion object {
            fun load(): Settings {
                val path = System.getenv("P15_PG_SETTINGS")
                check(path != null && path.isNotBlank(), "P15_PG_SETTINGS is mandatory and never causes a skip")
                val file = Path.of(path)
                check(
                    file.isAbsolute && file.normalize() == file && file.fileName.toString() == "shelter-command.properties" &&
                        Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file),
                    "Only an exact absolute P15 settings file is accepted",
                )
                check(
                    Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS) ==
                        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE) &&
                        (Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toInt() == 1,
                    "P15 settings must have0600 and one hard link",
                )
                check(
                    System.getProperty("user.name") == Files.getOwner(file).name &&
                        Files.getOwner(file) == Files.getOwner(file.parent),
                    "Settings and parent must have the current process owner",
                )
                var parent: Path? = file.parent
                while (parent != null) {
                    check(!Files.isSymbolicLink(parent), "Settings ancestors cannot be symbolic links")
                    parent = parent.parent
                }
                val permissions = Files.getPosixFilePermissions(file.parent, LinkOption.NOFOLLOW_LINKS)
                check(
                    !permissions.contains(PosixFilePermission.GROUP_WRITE) && !permissions.contains(PosixFilePermission.OTHERS_WRITE),
                    "P15 settings parent must reject other-user writes",
                )
                val values = Properties()
                Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input: InputStream -> values.load(input) }
                val database = required(values, "database")
                val url = required(values, "jdbcUrl")
                val username = required(values, "username")
                val marker = required(values, "marker")
                check(
                    database.matches(Regex("pb_shelter_command_owned_[0-9a-f]{12}")) &&
                        url == "jdbc:postgresql://127.0.0.1:52620/" + database,
                    "Only the isolated P15 loopback database is allowed",
                )
                check(
                    username == "pb_shelter_command_writer" && marker.matches(Regex("[0-9a-f]{32}")),
                    "P15 writer and marker must be explicit",
                )
                return Settings(url, database, username, required(values, "password"), marker)
            }

            private fun required(values: Properties, key: String): String {
                val result = values.getProperty(key)
                check(result != null && result.isNotBlank(), "Every owned setting is mandatory")
                return result
            }
        }
    }

    companion object {
        private const val ROOT = "/api/v1/shelters"
        private const val ADMIN = 910001L
        private const val OTHER_ADMIN = 910007L
        private const val OWN = 910002L
        private const val FOREIGN = 910003L
        private val KEY: SecretKey =
            Keys.hmacShaKeyFor("owned-p15-public-synthetic-signing-key-20261021-only".toByteArray(StandardCharsets.UTF_8))
        private val JSON: ObjectMapper = ObjectMapper().findAndRegisterModules()
        private val TABLE_KEYS: Map<String, String> = mapOf(
            "pawbridge_animal.shelters" to "id",
            "pawbridge_animal.animals" to "id",
            "pawbridge_animal.outbox_events" to "id",
            "pawbridge_animal.chatbot_sessions" to "id",
            "pawbridge_animal.animal_search_documents" to "animal_id",
            "pawbridge_animal.shelter_search_documents" to "shelter_id",
            "pawbridge_animal.shelter_public_information" to "shelter_id",
            "pawbridge_animal.shelter_daily_observations" to "shelter_id,observation_date",
            "pawbridge_user.users" to "user_id",
            "pawbridge_user.contact_deletions" to "user_id",
            "pawbridge_user.shelter_applications" to "id",
        )

        private fun newBody(care: String): Map<String, Any?> {
            return mapOf(
                "careRegNo" to care,
                "name" to name(care),
                "phone" to "p15-phone",
                "address" to "p15-address",
                "ownerName" to "p15-owner",
                "organizationName" to "p15-org",
                "email" to "p15@example.invalid",
                "introduction" to "p15-intro",
                "adoptionProcedure" to "p15-procedure",
                "operatingHours" to "p15-hours",
            )
        }

        private fun name(care: String): String = "OWNED-P15-" + care

        private fun writer(manager: JpaTransactionManager): TransactionTemplate {
            val tx = TransactionTemplate(manager)
            tx.isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
            return tx
        }

        private fun token(id: Long, role: String): String {
            return "Bearer " + Jwts.builder()
                .claim("userId", id)
                .claim("role", role)
                .expiration(Date(System.currentTimeMillis() + 300_000))
                .signWith(KEY)
                .compact()
        }

        private fun send(mvc: MockMvc, method: String, path: String, authorization: String?, body: Map<String, Any?>?, status: Int): JsonNode {
            val result = exchange(mvc, method, path, authorization, body)
            check(result.status == status, "Unexpected owned Shelter HTTP status: expected=" + status + ",actual=" + result.status)
            return result.body
        }

        private fun exchange(mvc: MockMvc, method: String, path: String, authorization: String?, body: Map<String, Any?>?): HttpResult {
            val request = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path)
                .servletPath(path)
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8)
                .header("X-User-Id", ADMIN)
                .header("X-User-Role", "ROLE_ADMIN")
            if (authorization != null) {
                request.header("Authorization", authorization)
            }
            if (body != null) {
                request.content(JSON.writeValueAsString(body))
            }
            val response = mvc.perform(request).andReturn().response
            check(response.getHeader("Set-Cookie") == null, "All Shelter writes must remain stateless")
            val text = response.getContentAsString(StandardCharsets.UTF_8)
            return HttpResult(response.status, if (text.isEmpty()) JSON.nodeFactory.nullNode() else JSON.readTree(text))
        }

        private fun observed(phase: String, action: Checked) {
            try {
                action.run()
            } catch (failure: AssertionError) {
                throw failure
            } catch (failure: Throwable) {
                throw AssertionError("Owned Shelter observation failed: " + phase + "; type=" + failure.javaClass.name)
            }
        }

        private fun check(condition: Boolean, fixed: String) {
            if (!condition) {
                throw AssertionError(fixed)
            }
        }
    }
}
