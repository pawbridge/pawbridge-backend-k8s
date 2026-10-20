package com.pawbridge.backend;

import com.pawbridge.backend.animal.api.AnimalMemberReads;
import com.pawbridge.backend.animal.api.AnimalMemberReads.Animal;
import com.pawbridge.backend.animal.api.AnimalMemberReads.PageRequest;
import com.pawbridge.backend.animal.internal.memberread.infrastructure.JdbcAnimalMemberReads;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.junit.jupiter.api.Assertions.*;

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
    private static final String ENV = "PAWBRIDGE_ANIMAL_MEMBER_PG_";
    private static final String ID_PREFIX = "90102026";
    private static final long BASE = 901020260000000L;
    private static final long SHELTER_A = BASE + 1, SHELTER_B = BASE + 2;
    private static final long MANUAL_A = BASE + 101, APMS_NOTICE_A = BASE + 102, APMS_ADOPTED_A = BASE + 103;
    private static final long MANUAL_B = BASE + 104, MANUAL_ADOPTED_B = BASE + 105, MISSING = BASE + 999;
    private static final List<Long> ANIMAL_IDS = List.of(MANUAL_A, APMS_NOTICE_A, APMS_ADOPTED_A, MANUAL_B, MANUAL_ADOPTED_B);
    private static final long FIXTURE_LOCK = 901020260000L;
    private static final LocalDate START = LocalDate.of(2026, 10, 1), END = LocalDate.of(2026, 11, 1);
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 10, 20, 1, 2, 3, 123456000);
    private static HikariDataSource pool;
    private static Connection control;
    private static JdbcTemplate fixture;
    private static AnimalMemberReads reads;
    private static String database, marker, username, rowPrefix;
    private static boolean inserted;

    @BeforeAll
    static void openExplicitOwnedFixture() throws Exception {
        String url = required("URL");
        database = required("DATABASE"); marker = required("OWNER_MARKER"); username = required("USER");
        String password = System.getenv(ENV + "PASSWORD");
        assertNotNull(password, "Dedicated fixture password environment must be present");
        assertTrue(database.matches("pb_animal_member_owned_[a-z0-9_]{8,32}"), "Owned fixture DB name required");
        assertTrue(marker.matches("[0-9a-f]{32}"), "Explicit32-hex fixture marker required");
        assertTrue(username.matches("[a-z_][a-z0-9_]{0,62}"), "Dedicated fixture role required");
        assertTrue(url.startsWith("jdbc:postgresql://"), "Explicit PostgreSQL TCP fixture URL required");
        URI target = URI.create(url.substring("jdbc:".length()));
        assertTrue(Set.of("127.0.0.1", "localhost", "[::1]", "::1").contains(target.getHost()), "Only a Root-approved loopback fixture is allowed");
        assertTrue(target.getPort() > 0 && target.getPort() <= 65535, "Explicit fixture port required");
        assertEquals("/" + database, target.getRawPath(), "URL and explicitly owned DB must agree");
        assertNull(target.getUserInfo(), "Put credentials only in dedicated environment fields");
        assertNull(target.getRawQuery(), "Fixture URL options must not override the selected database/role");
        assertNull(target.getFragment(), "Unexpected fixture URL fragment");
        assertTrue(ANIMAL_IDS.stream().allMatch(id -> id.toString().startsWith(ID_PREFIX)));
        assertTrue(Long.toString(SHELTER_A).startsWith(ID_PREFIX) && Long.toString(SHELTER_B).startsWith(ID_PREFIX));
        rowPrefix = "owned-" + marker + "-";
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url); config.setUsername(username); config.setPassword(password);
        config.setMaximumPoolSize(2); config.setMinimumIdle(0); config.setConnectionTimeout(3000);
        config.setValidationTimeout(2000); config.setInitializationFailTimeout(-1);
        config.setPoolName("owned-animal-member-pg");
        config.addDataSourceProperty("connectTimeout", "2"); config.addDataSourceProperty("socketTimeout", "8");
        config.addDataSourceProperty("ApplicationName", "owned-animal-member-pg");
        config.addDataSourceProperty("options", "-c statement_timeout=5000 -c lock_timeout=2000 -c idle_in_transaction_session_timeout=10000");
        pool = new HikariDataSource(config);
        try {
            control = pool.getConnection();
            fixture = new JdbcTemplate(new SingleConnectionDataSource(control, true)); fixture.setQueryTimeout(5);
            requireOwnership();
            assertEquals(Boolean.TRUE, fixture.queryForObject("SELECT pg_try_advisory_lock(?)", Boolean.class, FIXTURE_LOCK), "Owned fixture already in use");
            assertEquals(0L, fixture.queryForObject("SELECT count(*) FROM pawbridge_animal.animals", Long.class), "Fixture must start empty; no startup deletion is performed");
            assertEquals(0L, fixture.queryForObject("SELECT count(*) FROM pawbridge_animal.shelters", Long.class), "Fixture must start empty");
            control.setAutoCommit(false);
            try {
                fixture.update("INSERT INTO pawbridge_animal.shelters(id,care_reg_no,name,created_at) VALUES(?,?,?,?),(?,?,?,?)",
                        SHELTER_A,rowPrefix+"s-a","owned-a",CREATED,SHELTER_B,rowPrefix+"s-b","owned-b",CREATED);
                insert(MANUAL_A,SHELTER_A,"MANUAL","ADOPTED",null,null,null,null,0);
                insert(APMS_NOTICE_A,SHELTER_A,"APMS_ANIMAL","NOTICE",null,null,null,null,0);
                insert(APMS_ADOPTED_A,SHELTER_A,"APMS_ANIMAL","ADOPTED",null,null,null,null,0);
                insert(MANUAL_B,SHELTER_B,"MANUAL","PROTECT","synthetic-breed",LocalDate.now().getYear()-3,"synthetic-mark","https://example.invalid/owned-image",5);
                insert(MANUAL_ADOPTED_B,SHELTER_B,"MANUAL","ADOPTED",null,null,null,null,0);
                control.commit(); inserted = true;
            } catch (Exception | AssertionError failure) { control.rollback(); throw failure; }
            finally { control.setAutoCommit(true); }
            reads = new JdbcAnimalMemberReads(pool, new DataSourceTransactionManager(pool), registration -> {
                throw new AssertionError("Registration lookup is outside this selected PostgreSQL fixture");
            });
        } catch (Exception | AssertionError failure) {
            try { closeOwnedFixture(); } catch (Exception | AssertionError cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static String required(String suffix) {
        String value = System.getenv(ENV + suffix);
        assertNotNull(value, "Missing dedicated fixture environment: " + suffix);
        assertFalse(value.isBlank(), "Empty dedicated fixture environment: " + suffix);
        return value;
    }
    private static void requireOwnership() {
        Map<String,Object> actual = fixture.queryForMap("""
                SELECT current_database() AS db,current_user AS role,
                       pg_get_userbyid(d.datdba) AS db_owner,pg_get_userbyid(n.nspowner) AS schema_owner,
                       obj_description(n.oid,'pg_namespace') AS marker
                FROM pg_database d CROSS JOIN pg_namespace n
                WHERE d.datname=current_database() AND n.nspname='pawbridge_animal'
                """);
        assertEquals(database, actual.get("db"), "Wrong owned database");
        assertEquals(username, actual.get("role"), "Wrong fixture role");
        assertEquals(username, actual.get("db_owner"), "Role must own the isolated fixture database");
        assertEquals(username, actual.get("schema_owner"), "Role must own the fixture schema");
        assertEquals("pawbridge-owned-animal-member:v1:"+marker, actual.get("marker"), "Missing exact Root-owned fixture marker");
    }
    private static void insert(long id,long shelter,String source,String status,String breed,Integer year,String special,String image,int favorites) {
        fixture.update("""
                INSERT INTO pawbridge_animal.animals(id,apms_notice_no,species,breed,birth_year,gender,neuter_status,
                    special_mark,notice_start_date,notice_end_date,image_url,shelter_id,status,api_source,favorite_count,created_at)
                VALUES(?,?,'CAT',?,?,'UNKNOWN','UNKNOWN',?,?,?,?,?,?,?,?,?)
                """,id,rowPrefix+id,breed,year,special,START,END,image,shelter,status,source,favorites,CREATED);
    }

    @Test
    void givenMissingDuplicateAndNullableAnimals__whenBatchOnPostgresql__thenOmitMissingAndKeepAll15DtoValues() {
        List<Animal> batch = reads.batch(Arrays.asList(MANUAL_A, MANUAL_B, MISSING, MANUAL_A, APMS_ADOPTED_A, null));
        assertEquals(3,batch.size());
        Map<Long,Animal> byId = batch.stream().collect(Collectors.toMap(Animal::id, Function.identity()));
        assertEquals(Set.of(MANUAL_A,MANUAL_B,APMS_ADOPTED_A),byId.keySet());
        assertEquals(new Animal(MANUAL_A,rowPrefix+MANUAL_A,"CAT",null,"UNKNOWN",null,null,null,"ADOPTED",END,null,0,SHELTER_A,"owned-a",CREATED),byId.get(MANUAL_A));
        assertEquals(new Animal(MANUAL_B,rowPrefix+MANUAL_B,"CAT","synthetic-breed","UNKNOWN",LocalDate.now().getYear()-3,3,
                "synthetic-mark","PROTECT",END,"https://example.invalid/owned-image",5,SHELTER_B,"owned-b",CREATED),byId.get(MANUAL_B));
        assertEquals("ADOPTED",byId.get(APMS_ADOPTED_A).status());
        assertEquals(List.of(),reads.batch(List.of()));
    }

    @Test
    void givenManualRootAndRelatedApmsIncludingEndedState__whenCollectionPaging__thenApplySqlLimitBeforeRootDedupAndPartialCount() {
        // Shelter A has one MANUAL ADOPTED root and two APMS children (NOTICE,ADOPTED).
        // Three joined SQL rows yield one deduped root. Restricting sort children to MANUAL
        // or public statuses, or adding DISTINCT before LIMIT, changes the second page.
        var first = reads.byShelter(SHELTER_A,new PageRequest(0,2,"shelter.animals.id,asc"));
        assertEquals(List.of(MANUAL_A),ids(first)); assertPage(first,0,2,1,1,true);
        var second = reads.byShelter(SHELTER_A,new PageRequest(1,2,"shelter.animals.id,asc"));
        assertEquals(List.of(MANUAL_A),ids(second)); assertPage(second,1,2,3,2,true);
        var empty = reads.byShelter(SHELTER_A,new PageRequest(2,2,"shelter.animals.id,asc"));
        assertEquals(List.of(),ids(empty)); assertPage(empty,2,2,1,1,true);
        for (String sort : List.of("shelter_animals_id,asc","ShelterAnimalsId,asc","shelter.animals,asc",
                "shelter.animals.shelter.animals.id,asc")) {
            var page = reads.byShelter(SHELTER_A,new PageRequest(0,2,sort));
            assertEquals(List.of(MANUAL_A),ids(page)); assertPage(page,0,2,1,1,true);
        }
    }

    @Test
    void givenTwoManualRootsAndAnotherShelter__whenFullOrEmptyPages__thenCountOnlyRootManualAndKeepEndedRoots() {
        var first = reads.byShelter(SHELTER_B,new PageRequest(0,1,"id,asc"));
        assertEquals(List.of(MANUAL_B),ids(first)); assertPage(first,0,1,2,2,false);
        var second = reads.byShelter(SHELTER_B,new PageRequest(1,1,"id,asc"));
        assertEquals(List.of(MANUAL_ADOPTED_B),ids(second)); assertPage(second,1,1,2,2,true);
        var empty = reads.byShelter(SHELTER_B,new PageRequest(2,1,"id,asc"));
        assertEquals(List.of(),ids(empty)); assertPage(empty,2,1,2,2,true);
        var fullCollection = reads.byShelter(SHELTER_B,new PageRequest(0,2,"shelter.animals.id,asc"));
        assertEquals(Set.of(MANUAL_B,MANUAL_ADOPTED_B),Set.copyOf(ids(fullCollection)));
        assertPage(fullCollection,0,2,2,1,true); // Root ties have no promised synthetic tie-break.
    }
    private static List<Long> ids(AnimalMemberReads.Page page) { return page.content().stream().map(Animal::id).toList(); }
    private static void assertPage(AnimalMemberReads.Page page,int number,int size,long total,int pages,boolean last) {
        assertAll(()->assertEquals(number,page.number()),()->assertEquals(size,page.size()),
                ()->assertEquals(total,page.totalElements()),()->assertEquals(pages,page.totalPages()),()->assertEquals(last,page.last()));
    }

    @AfterAll
    static void closeOwnedFixture() throws Exception {
        try {
            if (inserted && control != null && !control.isClosed()) {
                requireOwnership();
                String in = ANIMAL_IDS.stream().map(String::valueOf).collect(Collectors.joining(","));
                assertEquals(5L,fixture.queryForObject("SELECT count(*) FROM pawbridge_animal.animals WHERE id IN ("+in+") AND apms_notice_no LIKE ?",Long.class,rowPrefix+"%"),"Only exact owned animals may be removed");
                assertEquals(2L,fixture.queryForObject("SELECT count(*) FROM pawbridge_animal.shelters WHERE id IN (?,?) AND care_reg_no LIKE ?",Long.class,SHELTER_A,SHELTER_B,rowPrefix+"%"),"Only exact owned shelters may be removed");
                control.setAutoCommit(false);
                try {
                    assertEquals(5,fixture.update("DELETE FROM pawbridge_animal.animals WHERE id IN ("+in+") AND apms_notice_no LIKE ?",rowPrefix+"%"));
                    assertEquals(2,fixture.update("DELETE FROM pawbridge_animal.shelters WHERE id IN (?,?) AND care_reg_no LIKE ?",SHELTER_A,SHELTER_B,rowPrefix+"%"));
                    control.commit(); inserted=false;
                } catch (Exception | AssertionError failure) { control.rollback(); throw failure; }
                finally { control.setAutoCommit(true); }
            }
        } finally {
            try { if (control != null && !control.isClosed()) control.close(); }
            finally { if (pool != null) pool.close(); }
        }
    }
}
