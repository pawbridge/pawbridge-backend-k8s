package com.pawbridge.backend

import com.pawbridge.backend.animal.api.AnimalMemberReads
import com.pawbridge.backend.animal.api.AnimalMemberReads.Animal
import com.pawbridge.backend.animal.api.AnimalMemberReads.PageRequest
import com.pawbridge.backend.animal.api.AnimalShelterReads
import com.pawbridge.backend.animal.internal.memberread.infrastructure.JdbcAnimalMemberReads
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.net.URI
import java.sql.Connection
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Root-owned PostgreSQL execution gate, not a Spring application/server startup.
 * Required environment (all independent from application/production settings):
 * PAWBRIDGE_ANIMAL_MEMBER_PG_ENABLED=owned-fixture-only
 * PAWBRIDGE_ANIMAL_MEMBER_PG_URL=jdbc:postgresql://127.0.0.1:EXPLICIT_PORT/OWNED_DATABASE
 * PAWBRIDGE_ANIMAL_MEMBER_PG_DATABASE=pb_animal_member_owned_[a-z0-9_]{8,32}
 * PAWBRIDGE_ANIMAL_MEMBER_PG_OWNER_MARKER=32 lowercase hex characters, as in the schema comment
 * PAWBRIDGE_ANIMAL_MEMBER_PG_USER=the dedicated DB/schema owner; PG_PASSWORD=that role's password
 * (the complete password variable is PAWBRIDGE_ANIMAL_MEMBER_PG_PASSWORD).
 *
 * Root provisions the empty DB and applies owned-postgresql/animal-member-owned-schema.sql first.
 * This test never creates/drops schemas or databases. It inserts exactly2 shelters/5 animals,
 * removes only its exact IDs plus matching owner prefix, and keeps a fixture advisory lock.
 * Pool max2/min0; connect2s/socket8s; server statement5s/lock2s limits. Root approves these first.
 * Default environment disables all methods before opening a pool. Root must select the tag and
 * require all3 tests to execute with skip0; a skipped result is no PostgreSQL verification.
 *
 * Assertions are synthetic candidate-on-PG observations derived from the pinned source and
 * Spring/Hibernate behavior. The original JPA implementation is NOT executed by this test.
 * No legacy oracle equality, production data/privileges or outer User TX suspension is claimed.
 */
@Tag("owned-postgresql")
@EnabledIfEnvironmentVariable(named = "PAWBRIDGE_ANIMAL_MEMBER_PG_ENABLED", matches = "owned-fixture-only")
@Execution(ExecutionMode.SAME_THREAD)
class OwnedPostgresqlAnimalMemberReadTest {
    @Test
    fun `없는 동물과 중복과 null이 섞였을 때__PostgreSQL에서 배치 조회하면__없는 동물은 빼고 DTO 값 15개를 유지한다`() {
        // given
        val requested = listOf(MANUAL_A, MANUAL_B, MISSING, MANUAL_A, APMS_ADOPTED_A, null)

        // when
        val batch = reads.batch(requested)!!
        val emptyBatch = reads.batch(listOf())

        // then
        assertEquals(3, batch.size)
        val byId: Map<Long?, Animal> = batch.associateBy { animal -> animal.id }
        assertEquals(setOf(MANUAL_A, MANUAL_B, APMS_ADOPTED_A), byId.keys)
        assertEquals(
            Animal(MANUAL_A, rowPrefix + MANUAL_A, "CAT", null, "UNKNOWN", null, null, null, "ADOPTED", END, null, 0, SHELTER_A, "owned-a", CREATED),
            byId[MANUAL_A],
        )
        assertEquals(
            Animal(
                MANUAL_B, rowPrefix + MANUAL_B, "CAT", "synthetic-breed", "UNKNOWN", LocalDate.now().year - 3, 3,
                "synthetic-mark", "PROTECT", END, "https://example.invalid/owned-image", 5, SHELTER_B, "owned-b", CREATED,
            ),
            byId[MANUAL_B],
        )
        assertEquals("ADOPTED", byId[APMS_ADOPTED_A]!!.status)
        assertEquals(listOf<Animal>(), emptyBatch)
    }

    @Test
    fun `수동 루트와 연관 APMS가 있을 때__컬렉션을 페이징하면__LIMIT 후 루트 중복을 제거하고 부분 건수를 센다`() {
        // given
        // Shelter A has one MANUAL ADOPTED root and two APMS children (NOTICE,ADOPTED).
        // Three joined SQL rows yield one deduped root. Restricting sort children to MANUAL
        // or public statuses, or adding DISTINCT before LIMIT, changes the second page.

        // when
        val first = reads.byShelter(SHELTER_A, PageRequest(0, 2, "shelter.animals.id,asc"))!!
        val second = reads.byShelter(SHELTER_A, PageRequest(1, 2, "shelter.animals.id,asc"))!!
        val empty = reads.byShelter(SHELTER_A, PageRequest(2, 2, "shelter.animals.id,asc"))!!

        // then
        assertEquals(listOf(MANUAL_A), ids(first))
        assertPage(first, 0, 2, 1, 1, true)
        assertEquals(listOf(MANUAL_A), ids(second))
        assertPage(second, 1, 2, 3, 2, true)
        assertEquals(listOf<Long>(), ids(empty))
        assertPage(empty, 2, 2, 1, 1, true)
        for (sort in listOf(
            "shelter_animals_id,asc",
            "ShelterAnimalsId,asc",
            "shelter.animals,asc",
            "shelter.animals.shelter.animals.id,asc",
        )) {
            val page = reads.byShelter(SHELTER_A, PageRequest(0, 2, sort))!!
            assertEquals(listOf(MANUAL_A), ids(page))
            assertPage(page, 0, 2, 1, 1, true)
        }
    }

    @Test
    fun `수동 루트 둘과 다른 보호소가 있을 때__꽉 찬 페이지와 빈 페이지를 조회하면__수동 루트만 세고 종료 루트는 유지한다`() {
        // given
        // when
        val first = reads.byShelter(SHELTER_B, PageRequest(0, 1, "id,asc"))!!
        val second = reads.byShelter(SHELTER_B, PageRequest(1, 1, "id,asc"))!!
        val empty = reads.byShelter(SHELTER_B, PageRequest(2, 1, "id,asc"))!!
        val fullCollection = reads.byShelter(SHELTER_B, PageRequest(0, 2, "shelter.animals.id,asc"))!!

        // then
        assertEquals(listOf(MANUAL_B), ids(first))
        assertPage(first, 0, 1, 2, 2, false)
        assertEquals(listOf(MANUAL_ADOPTED_B), ids(second))
        assertPage(second, 1, 1, 2, 2, true)
        assertEquals(listOf<Long>(), ids(empty))
        assertPage(empty, 2, 1, 2, 2, true)
        assertEquals(setOf(MANUAL_B, MANUAL_ADOPTED_B), ids(fullCollection).toSet())
        assertPage(fullCollection, 0, 2, 2, 1, true) // Root ties have no promised synthetic tie-break.
    }

    private fun ids(page: AnimalMemberReads.Page): List<Long?> {
        return page.content!!.map { animal -> animal.id }
    }

    private fun assertPage(page: AnimalMemberReads.Page, number: Int, size: Int, total: Long, pages: Int, last: Boolean) {
        assertAll(
            Executable { assertEquals(number, page.number) },
            Executable { assertEquals(size, page.size) },
            Executable { assertEquals(total, page.totalElements) },
            Executable { assertEquals(pages, page.totalPages) },
            Executable { assertEquals(last, page.last) },
        )
    }

    companion object {
        private const val ENV = "PAWBRIDGE_ANIMAL_MEMBER_PG_"
        private const val ID_PREFIX = "90102026"
        private const val BASE = 901020260000000L
        private const val SHELTER_A = BASE + 1
        private const val SHELTER_B = BASE + 2
        private const val MANUAL_A = BASE + 101
        private const val APMS_NOTICE_A = BASE + 102
        private const val APMS_ADOPTED_A = BASE + 103
        private const val MANUAL_B = BASE + 104
        private const val MANUAL_ADOPTED_B = BASE + 105
        private const val MISSING = BASE + 999
        private val ANIMAL_IDS: List<Long> = listOf(MANUAL_A, APMS_NOTICE_A, APMS_ADOPTED_A, MANUAL_B, MANUAL_ADOPTED_B)
        private const val FIXTURE_LOCK = 901020260000L
        private val START: LocalDate = LocalDate.of(2026, 10, 1)
        private val END: LocalDate = LocalDate.of(2026, 11, 1)
        private val CREATED: LocalDateTime = LocalDateTime.of(2026, 10, 20, 1, 2, 3, 123456000)
        private var pool: HikariDataSource? = null
        private var control: Connection? = null
        private lateinit var fixture: JdbcTemplate
        private lateinit var reads: AnimalMemberReads
        private lateinit var database: String
        private lateinit var marker: String
        private lateinit var username: String
        private lateinit var rowPrefix: String
        private var inserted = false

        @BeforeAll
        @JvmStatic
        fun openExplicitOwnedFixture() {
            val url = required("URL")
            database = required("DATABASE")
            marker = required("OWNER_MARKER")
            username = required("USER")
            val password = System.getenv(ENV + "PASSWORD")
            assertNotNull(password, "Dedicated fixture password environment must be present")
            assertTrue(database.matches(Regex("pb_animal_member_owned_[a-z0-9_]{8,32}")), "Owned fixture DB name required")
            assertTrue(marker.matches(Regex("[0-9a-f]{32}")), "Explicit32-hex fixture marker required")
            assertTrue(username.matches(Regex("[a-z_][a-z0-9_]{0,62}")), "Dedicated fixture role required")
            assertTrue(url.startsWith("jdbc:postgresql://"), "Explicit PostgreSQL TCP fixture URL required")
            val target = URI.create(url.substring("jdbc:".length))
            assertTrue(
                setOf("127.0.0.1", "localhost", "[::1]", "::1").contains(target.host),
                "Only a Root-approved loopback fixture is allowed",
            )
            assertTrue(target.port > 0 && target.port <= 65535, "Explicit fixture port required")
            assertEquals("/" + database, target.rawPath, "URL and explicitly owned DB must agree")
            assertNull(target.userInfo, "Put credentials only in dedicated environment fields")
            assertNull(target.rawQuery, "Fixture URL options must not override the selected database/role")
            assertNull(target.fragment, "Unexpected fixture URL fragment")
            assertTrue(ANIMAL_IDS.all { id -> id.toString().startsWith(ID_PREFIX) })
            assertTrue(SHELTER_A.toString().startsWith(ID_PREFIX) && SHELTER_B.toString().startsWith(ID_PREFIX))
            rowPrefix = "owned-" + marker + "-"
            val config = HikariConfig()
            config.jdbcUrl = url
            config.username = username
            config.password = password
            config.maximumPoolSize = 2
            config.minimumIdle = 0
            config.connectionTimeout = 3000
            config.validationTimeout = 2000
            config.initializationFailTimeout = -1
            config.poolName = "owned-animal-member-pg"
            config.addDataSourceProperty("connectTimeout", "2")
            config.addDataSourceProperty("socketTimeout", "8")
            config.addDataSourceProperty("ApplicationName", "owned-animal-member-pg")
            config.addDataSourceProperty(
                "options",
                "-c statement_timeout=5000 -c lock_timeout=2000 -c idle_in_transaction_session_timeout=10000",
            )
            val source = HikariDataSource(config)
            pool = source
            try {
                val connection = source.connection
                control = connection
                fixture = JdbcTemplate(SingleConnectionDataSource(connection, true))
                fixture.queryTimeout = 5
                requireOwnership()
                assertEquals(
                    true,
                    fixture.queryForObject("SELECT pg_try_advisory_lock(?)", Boolean::class.javaObjectType, FIXTURE_LOCK),
                    "Owned fixture already in use",
                )
                assertEquals(
                    0L,
                    fixture.queryForObject("SELECT count(*) FROM pawbridge_animal.animals", Long::class.javaObjectType),
                    "Fixture must start empty; no startup deletion is performed",
                )
                assertEquals(
                    0L,
                    fixture.queryForObject("SELECT count(*) FROM pawbridge_animal.shelters", Long::class.javaObjectType),
                    "Fixture must start empty",
                )
                connection.autoCommit = false
                try {
                    fixture.update(
                        "INSERT INTO pawbridge_animal.shelters(id,care_reg_no,name,created_at) VALUES(?,?,?,?),(?,?,?,?)",
                        SHELTER_A, rowPrefix + "s-a", "owned-a", CREATED, SHELTER_B, rowPrefix + "s-b", "owned-b", CREATED,
                    )
                    insert(MANUAL_A, SHELTER_A, "MANUAL", "ADOPTED", null, null, null, null, 0)
                    insert(APMS_NOTICE_A, SHELTER_A, "APMS_ANIMAL", "NOTICE", null, null, null, null, 0)
                    insert(APMS_ADOPTED_A, SHELTER_A, "APMS_ANIMAL", "ADOPTED", null, null, null, null, 0)
                    insert(
                        MANUAL_B, SHELTER_B, "MANUAL", "PROTECT", "synthetic-breed", LocalDate.now().year - 3,
                        "synthetic-mark", "https://example.invalid/owned-image", 5,
                    )
                    insert(MANUAL_ADOPTED_B, SHELTER_B, "MANUAL", "ADOPTED", null, null, null, null, 0)
                    connection.commit()
                    inserted = true
                } catch (failure: Exception) {
                    connection.rollback()
                    throw failure
                } catch (failure: AssertionError) {
                    connection.rollback()
                    throw failure
                } finally {
                    connection.autoCommit = true
                }
                reads = JdbcAnimalMemberReads(
                    source,
                    DataSourceTransactionManager(source),
                    object : AnimalShelterReads {
                        override fun findByCareRegNo(careRegNo: String?): AnimalShelterReads.Shelter? {
                            throw AssertionError("Registration lookup is outside this selected PostgreSQL fixture")
                        }
                    },
                )
            } catch (failure: Exception) {
                try {
                    closeOwnedFixture()
                } catch (cleanup: Exception) {
                    failure.addSuppressed(cleanup)
                } catch (cleanup: AssertionError) {
                    failure.addSuppressed(cleanup)
                }
                throw failure
            } catch (failure: AssertionError) {
                try {
                    closeOwnedFixture()
                } catch (cleanup: Exception) {
                    failure.addSuppressed(cleanup)
                } catch (cleanup: AssertionError) {
                    failure.addSuppressed(cleanup)
                }
                throw failure
            }
        }

        private fun required(suffix: String): String {
            val value = System.getenv(ENV + suffix)
            assertNotNull(value, "Missing dedicated fixture environment: " + suffix)
            assertFalse(value.isBlank(), "Empty dedicated fixture environment: " + suffix)
            return value
        }

        private fun requireOwnership() {
            val actual = fixture.queryForMap(
                """
                SELECT current_database() AS db,current_user AS role,
                       pg_get_userbyid(d.datdba) AS db_owner,pg_get_userbyid(n.nspowner) AS schema_owner,
                       obj_description(n.oid,'pg_namespace') AS marker
                FROM pg_database d CROSS JOIN pg_namespace n
                WHERE d.datname=current_database() AND n.nspname='pawbridge_animal'
                """.trimIndent(),
            )
            assertEquals(database, actual["db"], "Wrong owned database")
            assertEquals(username, actual["role"], "Wrong fixture role")
            assertEquals(username, actual["db_owner"], "Role must own the isolated fixture database")
            assertEquals(username, actual["schema_owner"], "Role must own the fixture schema")
            assertEquals("pawbridge-owned-animal-member:v1:" + marker, actual["marker"], "Missing exact Root-owned fixture marker")
        }

        private fun insert(
            id: Long,
            shelter: Long,
            source: String,
            status: String,
            breed: String?,
            year: Int?,
            special: String?,
            image: String?,
            favorites: Int,
        ) {
            fixture.update(
                """
                INSERT INTO pawbridge_animal.animals(id,apms_notice_no,species,breed,birth_year,gender,neuter_status,
                    special_mark,notice_start_date,notice_end_date,image_url,shelter_id,status,api_source,favorite_count,created_at)
                VALUES(?,?,'CAT',?,?,'UNKNOWN','UNKNOWN',?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                id, rowPrefix + id, breed, year, special, START, END, image, shelter, status, source, favorites, CREATED,
            )
        }

        @AfterAll
        @JvmStatic
        fun closeOwnedFixture() {
            try {
                val connection = control
                if (inserted && connection != null && !connection.isClosed) {
                    requireOwnership()
                    val ids = ANIMAL_IDS.joinToString(",") { id -> id.toString() }
                    assertEquals(
                        5L,
                        fixture.queryForObject(
                            "SELECT count(*) FROM pawbridge_animal.animals WHERE id IN (" + ids + ") AND apms_notice_no LIKE ?",
                            Long::class.javaObjectType,
                            rowPrefix + "%",
                        ),
                        "Only exact owned animals may be removed",
                    )
                    assertEquals(
                        2L,
                        fixture.queryForObject(
                            "SELECT count(*) FROM pawbridge_animal.shelters WHERE id IN (?,?) AND care_reg_no LIKE ?",
                            Long::class.javaObjectType,
                            SHELTER_A,
                            SHELTER_B,
                            rowPrefix + "%",
                        ),
                        "Only exact owned shelters may be removed",
                    )
                    connection.autoCommit = false
                    try {
                        assertEquals(
                            5,
                            fixture.update(
                                "DELETE FROM pawbridge_animal.animals WHERE id IN (" + ids + ") AND apms_notice_no LIKE ?",
                                rowPrefix + "%",
                            ),
                        )
                        assertEquals(
                            2,
                            fixture.update(
                                "DELETE FROM pawbridge_animal.shelters WHERE id IN (?,?) AND care_reg_no LIKE ?",
                                SHELTER_A,
                                SHELTER_B,
                                rowPrefix + "%",
                            ),
                        )
                        connection.commit()
                        inserted = false
                    } catch (failure: Exception) {
                        connection.rollback()
                        throw failure
                    } catch (failure: AssertionError) {
                        connection.rollback()
                        throw failure
                    } finally {
                        connection.autoCommit = true
                    }
                }
            } finally {
                try {
                    val connection = control
                    if (connection != null && !connection.isClosed) {
                        connection.close()
                    }
                } finally {
                    pool?.close()
                }
            }
        }
    }
}
