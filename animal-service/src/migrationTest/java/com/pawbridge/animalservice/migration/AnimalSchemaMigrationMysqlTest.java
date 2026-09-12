package com.pawbridge.animalservice.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.animalservice.travel.PetTravelCatalog;
import com.pawbridge.animalservice.travel.PetTravelCollector;
import com.pawbridge.animalservice.travel.PetTravelService;
import com.pawbridge.animalservice.travel.TourApiClient;
import com.pawbridge.animalservice.travel.TourApiProperties;
import java.time.Clock;
import java.time.ZoneOffset;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.entity.Shelter;
import com.pawbridge.animalservice.entity.SyncHistory;
import com.pawbridge.animalservice.entity.ProcessedEvent;
import com.pawbridge.animalservice.entity.OutboxEvent;
import com.pawbridge.animalservice.chatbot.entity.ChatbotSession;
import com.pawbridge.animalservice.chatbot.entity.ChatbotMessage;
import com.pawbridge.animalservice.chatbot.entity.ChatbotBlockLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.api.FlywayException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("mysql")
class AnimalSchemaMigrationMysqlTest {
    private AnimalSchemaMigration.Settings settings;

    @BeforeEach
    void prepare_disposable_database() throws Exception {
        String port = System.getenv("ANIMAL_MIGRATION_TEST_PORT");
        if (port == null || !port.matches("[0-9]{1,5}")) {
            throw new IllegalStateException("Set ANIMAL_MIGRATION_TEST_PORT for the disposable container");
        }
        String url = "jdbc:mysql://127.0.0.1:" + port + "/pawbridge_animal";
        var env = AnimalSchemaMigrationTest.environment(url);
        env.put("ANIMAL_MIGRATION_USERNAME", "root");
        env.put("ANIMAL_MIGRATION_PASSWORD", "local_flyway_test_only");
        env.put("ANIMAL_MIGRATION_CONFIRM_TARGET", url);
        settings = AnimalSchemaMigration.Settings.from(env);
        try (var connection = connection(); var statement = connection.createStatement()) {
            // Never clean a DB without the dedicated test container marker.
            try (var marker = statement.executeQuery("SELECT marker FROM flyway_test_guard.guard")) {
                if (!marker.next() || !"animal-flyway-disposable".equals(marker.getString(1))) {
                    throw new IllegalStateException("Missing disposable database guard");
                }
            }
            statement.execute("DROP TABLE IF EXISTS flyway_schema_history");
            statement.execute("DROP TABLE IF EXISTS migration_probe");
            // Reverse dependency order; fixed allowlist confined to this guarded test schema.
            for (String table : List.of("apms_photo_archive", "apms_photo_scan", "pet_travel_places", "pet_travel_targets", "pet_travel_regions",
                    "pet_travel_collection_state", "pet_travel_collection_runs", "pet_travel_request_budgets",
                    "BATCH_JOB_SEQ", "BATCH_JOB_EXECUTION_SEQ", "BATCH_STEP_EXECUTION_SEQ",
                    "BATCH_JOB_EXECUTION_CONTEXT", "BATCH_STEP_EXECUTION_CONTEXT", "BATCH_STEP_EXECUTION",
                    "BATCH_JOB_EXECUTION_PARAMS", "BATCH_JOB_EXECUTION", "BATCH_JOB_INSTANCE",
                    "chatbot_block_logs", "chatbot_messages", "chatbot_sessions", "outbox_events",
                    "processed_events", "sync_history", "animals", "shelters")) {
                statement.execute("DROP TABLE IF EXISTS `" + table + "`");
            }
        }
    }

    @Test
    void location_without_sql_cannot_report_migration_success() {
        assertThatThrownBy(() -> AnimalSchemaMigration.execute("migrate", settings,
                "classpath:com/pawbridge/animalservice/migration"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No reviewed migrations are packaged");
    }

    @Test
    void existing_schema_is_not_silently_baselined() throws Exception {
        try (var connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE migration_probe (id BIGINT PRIMARY KEY)");
            statement.execute("INSERT INTO migration_probe VALUES (73)");
        }
        assertThatThrownBy(() -> AnimalSchemaMigration.execute("migrate", settings, "classpath:db/migration"))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("non-empty schema");
        try (var connection = connection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT id FROM migration_probe")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getLong(1)).isEqualTo(73);
        }
    }

    @Test
    void initial_schema_matches_all_entities_and_initializes_batch_sequences() throws Exception {
        AnimalSchemaMigration.execute("migrate", settings, "classpath:db/migration");
        try (var connection = connection(); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM information_schema.TABLES "
                    + "WHERE TABLE_SCHEMA = 'pawbridge_animal'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(26); // V1 17 + V2 6 + V4 2 + Flyway history.
            }
            for (String table : List.of("BATCH_JOB_SEQ", "BATCH_JOB_EXECUTION_SEQ", "BATCH_STEP_EXECUTION_SEQ")) {
                try (var rows = statement.executeQuery("SELECT ID, UNIQUE_KEY FROM " + table)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1)).isZero();
                    assertThat(rows.getString(2)).isEqualTo("0");
                    assertThat(rows.next()).isFalse();
                }
            }
        }
        var registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.url", settings.url())
                .applySetting("hibernate.connection.username", settings.username())
                .applySetting("hibernate.connection.password", settings.password())
                .applySetting("hibernate.hbm2ddl.auto", "validate")
                .applySetting("hibernate.physical_naming_strategy",
                        "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                .build();
        try {
            var metadata = new MetadataSources(registry);
            for (Class<?> entity : List.of(Animal.class, Shelter.class, SyncHistory.class,
                    ProcessedEvent.class, OutboxEvent.class, ChatbotSession.class,
                    ChatbotMessage.class, ChatbotBlockLog.class)) {
                metadata.addAnnotatedClass(entity);
            }
            try (var factory = metadata.buildMetadata().buildSessionFactory()) {
                assertThat(factory.isOpen()).isTrue();
            }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    @Test
    @Tag("rehearsal")
    void explicitly_baselined_existing_schema_preserves_rows_and_batch_counters() throws Exception {
        org.flywaydb.core.Flyway.configure()
                .configuration(AnimalSchemaMigration.configured(settings, "classpath:db/migration").getConfiguration())
                .target("1").load().migrate();
        try (var connection = connection(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE flyway_schema_history");
            statement.execute("INSERT INTO processed_events VALUES ('existing-event', 'test', NOW(6))");
            statement.execute("UPDATE BATCH_JOB_SEQ SET ID = 73");
        }
        // Explicit baseline is rehearsed only here, on the guarded disposable database.
        AnimalSchemaMigration.configured(settings, "classpath:db/migration").baseline();
        AnimalSchemaMigration.execute("migrate", settings, "classpath:db/migration");
        AnimalSchemaMigration.execute("validate", settings, "classpath:db/migration");
        try (var connection = connection(); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT event_id FROM processed_events")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("existing-event");
            }
            try (var rows = statement.executeQuery("SELECT ID FROM BATCH_JOB_SEQ")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(73);
            }
        }
    }

    @Test
    void basic_discovery_is_public_before_details_and_inventory_completion_is_independent() {
        var catalog = catalog();
        var time = Instant.parse("2026-09-12T00:00:00Z");
        catalog.saveRegions(Map.of("11", "서울"), time);
        catalog.observeBasic(Map.of("contentid","100","title","서울 공원","addr1","서울",
                "modifiedtime","20260912000000","showflag","1"),"11",time);
        assertThat(catalog.regions().get(0).completedAt()).isNull();
        assertThat(catalog.places("11")).hasSize(1);
        var before=new PetTravelService(catalog).detail("100");
        assertThat(before.petInformationStatus()).isEqualTo("PREPARING");
        assertThat(before.petInformationFetchedAt()).isNull();
        assertThat(new PetTravelService(catalog).places("11").availability()).isEqualTo("PARTIAL");
        assertThat(catalog.completeRegion("11", time)).isTrue();
        var target = catalog.pending(10).get(0);
        assertThat(catalog.publish(target, Map.of("contentid", "100", "title", "서울 공원"), Map.of(), time)).isTrue();
        assertThat(catalog.detail("100").orElseThrow().pet()).isEmpty();
        assertThat(catalog.completeRegion("11", time)).isTrue();
        assertThat(catalog.regions().get(0).completedAt()).isEqualTo(time);
    }

    @Test
    void refresh_failure_keeps_snapshot_but_latest_photo_rights_take_effect() {
        var catalog = catalog();
        var time = Instant.parse("2026-09-12T00:00:00Z");
        catalog.observe("100", "11", "20260912000000", true, "https://example.invalid/photo.jpg", "Type1", time, false);
        catalog.publish(catalog.pending(10).get(0), Map.of("contentid", "100", "title", "공원", "cpyrhtDivCd", "Type1"),
                Map.of("contentid", "100", "acmpyNeedMtr", "목줄 필수"), time);
        catalog.observe("100", "11", "20260913000000", true, null, "Type2", time.plusSeconds(60), false);
        // No publication represents failed HTTP: old text survives, old photo rights do not.
        var place = catalog.detail("100").orElseThrow();
        assertThat(place.common()).containsEntry("title", "공원").containsEntry("cpyrhtDivCd", "Type2").doesNotContainKey("firstimage");
        assertThat(place.pet()).containsEntry("acmpyNeedMtr", "목줄 필수");
        assertThat(place.publishedAt()).isEqualTo(time);
        assertThat(catalog.pending(10)).hasSize(1);
    }

    @Test
    void hiding_invalidates_inflight_details_and_reappearance_requires_publication() {
        var catalog = catalog();
        var time = Instant.parse("2026-09-12T00:00:00Z");
        var common = Map.of("contentid", "100", "title", "공원");
        catalog.observe("100", "11", "20260912000000", true, null, null, time, false);
        catalog.publish(catalog.pending(10).get(0), common, Map.of(), time);
        catalog.observe("100", "11", "20260913000000", true, null, null, time, true);
        var stale = catalog.pending(10).get(0);
        catalog.observe("100", "11", "20260914000000", false, null, null, time, false);
        assertThat(catalog.detail("100")).isEmpty();
        assertThat(catalog.publish(stale, common, Map.of(), time)).isFalse();
        catalog.observe("100", "11", "20260915000000", true, null, null, time, false);
        assertThat(catalog.detail("100")).isEmpty();
        assertThat(catalog.publish(catalog.pending(10).get(0), common, Map.of(), time)).isTrue();
        assertThat(catalog.places("11")).hasSize(1);
    }

    @Test
    void duplicate_and_older_discoveries_do_not_duplicate_or_revert_the_place() {
        var catalog = catalog();
        var time = Instant.parse("2026-09-12T00:00:00Z");
        catalog.observe("10000000000000000000", "11", "20260912000000", true, null, "Type3", time, false);
        catalog.observe("10000000000000000000", "11", "20260912000000", true, null, "Type3", time, false);
        assertThat(catalog.pending(10)).hasSize(1);
        var target = catalog.pending(10).get(0);
        var common = Map.of("contentid", target.contentId(), "title", "공원");
        assertThat(catalog.publish(target, common, Map.of(), time)).isTrue();
        assertThat(catalog.publish(target, common, Map.of(), time)).isFalse();
        catalog.observe(target.contentId(), "11", "20260911000000", false, null, "Type2", time, false);
        assertThat(catalog.detail(target.contentId()).orElseThrow().common()).containsEntry("cpyrhtDivCd", "Type3");
        assertThat(catalog.pending(10)).isEmpty();
        assertThat(catalog.places("11")).hasSize(1);
    }

    @Test
    void invalid_detail_does_not_consume_pending_work() {
        var catalog = catalog();
        var time = Instant.parse("2026-09-12T00:00:00Z");
        catalog.observe("100", "11", "20260912000000", true, null, null, time, false);
        var target = catalog.pending(10).get(0);
        assertThatThrownBy(() -> catalog.publish(target, Map.of("contentid", "999", "title", "다른 장소"), Map.of(), time))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(catalog.pending(10)).containsExactly(target);
        assertThat(catalog.detail("100")).isEmpty();
    }

    private PetTravelCatalog catalog() {
        AnimalSchemaMigration.execute("migrate", settings, "classpath:db/migration");
        var ds = new DriverManagerDataSource(settings.url(), settings.username(), settings.password());
        return new PetTravelCatalog(new JdbcTemplate(ds), new ObjectMapper(), new DataSourceTransactionManager(ds));
    }

    @Test
    void failed_pending_row_does_not_block_refresh_of_other_places() {
        var catalog=catalog();var time=Instant.parse("2026-09-12T00:00:00Z");
        catalog.observe("123","11","20260912000000",true,null,null,time,false);
        catalog.publish(catalog.pending(1).get(0),Map.of("contentid","123","title","기존 장소"),Map.of(),time);
        catalog.observe("124","11","20260912000000",true,null,null,time,false);
        catalog.detailFailed(catalog.pending(1).get(0),time.plusSeconds(60));
        catalog.beginDetails(time.plusSeconds(1));
        assertThat(catalog.pending(10)).extracting(PetTravelCatalog.Target::contentId).containsExactly("123","124");
    }

    @Test
    void v3_preserves_v2_snapshot_and_releases_legacy_details_cursor() throws Exception {
        org.flywaydb.core.Flyway.configure()
                .configuration(AnimalSchemaMigration.configured(settings,"classpath:db/migration").getConfiguration())
                .target("2").load().migrate();
        try(var connection=connection();var statement=connection.createStatement()) {
            statement.execute("INSERT INTO pet_travel_targets VALUES ('KOREA_TOURISM_ORGANIZATION','123','11','20260912000000',TRUE,NULL,NULL,1,FALSE,NOW(6))");
            statement.execute("INSERT INTO pet_travel_places VALUES ('KOREA_TOURISM_ORGANIZATION','123','11','기존 공원',JSON_OBJECT('contentid','123','title','기존 공원'),JSON_OBJECT(),NULL,NULL,TRUE,NOW(6))");
            statement.execute("UPDATE pet_travel_collection_state SET phase='DETAILS'");
        }
        var catalog=catalog();
        assertThat(catalog.collectionState().phase()).isEqualTo("HIDDEN");
        assertThat(catalog.detail("123").orElseThrow().common()).containsEntry("title","기존 공원");
        assertThat(catalog.detail("123").orElseThrow().detailStatus()).isEqualTo("READY");
        AnimalSchemaMigration.execute("validate",settings,"classpath:db/migration");
    }

    @Test
    void discovery_refresh_preserves_details_and_hidden_reappearance_does_not_reuse_old_conditions() {
        var catalog=catalog(); var time=Instant.parse("2026-09-12T00:00:00Z");
        var row=new java.util.HashMap<>(Map.of("contentid","123","title","기본 공원","addr1","서울",
                "modifiedtime","20260912000000","showflag","1"));
        catalog.observeBasic(row,"11",time);
        catalog.publish(catalog.pending(1).get(0),Map.of("contentid","123","title","옛 제목","overview","소개문"),
                Map.of("contentid","123","acmpyNeedMtr","목줄"),time);
        catalog.observeBasic(row,"11",time.plusSeconds(60));
        var saved=catalog.detail("123").orElseThrow();
        assertThat(saved.common()).containsEntry("title","기본 공원").containsEntry("overview","소개문");
        assertThat(saved.publishedAt()).isEqualTo(time);
        assertThat(saved.basicFetchedAt()).isEqualTo(time.plusSeconds(60));
        row.put("modifiedtime","20260913000000");row.put("showflag","0");
        catalog.observeBasic(row,"11",time.plusSeconds(120));
        assertThat(catalog.detail("123")).isEmpty();
        row.put("modifiedtime","20260914000000");row.put("showflag","1");
        catalog.observeBasic(row,"11",time.plusSeconds(180));
        var reappeared=catalog.detail("123").orElseThrow();
        assertThat(reappeared.pet()).isEmpty();
        assertThat(reappeared.publishedAt()).isNull();
        assertThat(reappeared.detailStatus()).isEqualTo("PREPARING");
    }

    @Test
    void pending_detail_failure_does_not_block_next_inventory_hiding() throws Exception {
        var catalog=catalog();var client=mock(TourApiClient.class);
        var properties=new TourApiProperties();properties.setEnabled(true);
        when(client.fetch(TourApiClient.Operation.REGIONS,"")).thenReturn(List.of(Map.of("code","11","name","서울")));
        var shown=Map.of("contentid","123","title","공원","lDongRegnCd","11","modifiedtime","20260912000000","showflag","1");
        when(client.fetchPage(TourApiClient.Operation.SYNC,"0",1)).thenReturn(new TourApiClient.Page(List.of(),0));
        when(client.fetchPage(TourApiClient.Operation.SYNC,"1",1)).thenReturn(new TourApiClient.Page(List.of(shown),1));
        when(client.fetch(TourApiClient.Operation.COMMON,"123")).thenThrow(new IllegalStateException("synthetic failure"));
        assertThat(collector(catalog,client,properties).collect().status()).isEqualTo("PARTIAL");
        assertThat(catalog.detail("123")).isPresent();
        var stale=catalog.pending(1).get(0);
        var hidden=new java.util.HashMap<>(shown);hidden.put("showflag","0");hidden.put("modifiedtime","20260913000000");
        when(client.fetchPage(TourApiClient.Operation.SYNC,"0",1)).thenReturn(new TourApiClient.Page(List.of(hidden),1));
        when(client.fetchPage(TourApiClient.Operation.SYNC,"1",1)).thenReturn(new TourApiClient.Page(List.of(),0));
        clearInvocations(client);
        assertThat(collector(catalog,client,properties).collect().status()).isEqualTo("COMPLETED");
        assertThat(catalog.detail("123")).isEmpty();
        assertThat(catalog.publish(stale,Map.of("contentid","123","title","옛 공원"),Map.of(),Instant.now())).isFalse();
        verify(client,never()).fetch(eq(TourApiClient.Operation.COMMON),anyString());
    }

    @Test
    void collection_publishes_to_database_and_public_reads_do_not_call_the_provider() throws Exception {
        var catalog=catalog();
        var client=mock(TourApiClient.class);
        var properties=new TourApiProperties(); properties.setEnabled(true);
        when(client.fetch(TourApiClient.Operation.REGIONS,"")).thenReturn(List.of(Map.of("code","11","name","서울")));
        when(client.fetchPage(TourApiClient.Operation.SYNC,"0",1)).thenReturn(new TourApiClient.Page(List.of(),0));
        when(client.fetchPage(TourApiClient.Operation.SYNC,"1",1)).thenReturn(new TourApiClient.Page(List.of(
                Map.of("contentid","123","title","수집 공원","lDongRegnCd","11","modifiedtime","20260912000000","showflag","1")),1));
        when(client.fetch(TourApiClient.Operation.COMMON,"123")).thenAnswer(invocation-> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return List.of(Map.of("contentid","123","title","수집 공원"));
        });
        when(client.fetch(TourApiClient.Operation.PET,"123")).thenReturn(List.of(Map.of("contentid","123","acmpyNeedMtr","목줄")));
        var dataSource=new DriverManagerDataSource(settings.url(),settings.username(),settings.password());
        var manager=new DataSourceTransactionManager(dataSource);
        var factory=new org.springframework.batch.core.repository.support.JobRepositoryFactoryBean();
        factory.setDataSource(dataSource);factory.setTransactionManager(manager);factory.afterPropertiesSet();
        var jobRepository=factory.getObject();
        var launcher=new org.springframework.batch.core.launch.support.TaskExecutorJobLauncher();
        launcher.setJobRepository(jobRepository);launcher.setTaskExecutor(new org.springframework.core.task.SyncTaskExecutor());launcher.afterPropertiesSet();
        var job=new com.pawbridge.animalservice.travel.PetTravelBatchConfiguration()
                .petTravelCollectionJob(jobRepository,manager,collector(catalog,client,properties));
        var execution=launcher.run(job,new org.springframework.batch.core.JobParametersBuilder().addString("test","stored-read").toJobParameters());
        assertThat(execution.getStatus()).isEqualTo(org.springframework.batch.core.BatchStatus.COMPLETED);
        assertThat(new JdbcTemplate(dataSource).queryForObject("SELECT requests FROM pet_travel_collection_runs",Integer.class)).isEqualTo(5);
        clearInvocations(client);
        var service=new PetTravelService(catalog);
        assertThat(service.places("11").availability()).isEqualTo("READY");
        assertThat(service.detail("123").conditions().requirements()).isEqualTo("목줄");
        service.regions(); service.places("11"); service.detail("123");
        verifyNoInteractions(client);
    }

    @Test
    void quota_is_persistent_and_detail_failure_can_resume_without_republishing_partial_data() throws Exception {
        var catalog=catalog();
        var client=mock(TourApiClient.class);
        var properties=new TourApiProperties();properties.setEnabled(true);
        when(client.fetch(TourApiClient.Operation.REGIONS,"")).thenReturn(List.of(Map.of("code","11","name","서울")));
        when(client.fetchPage(TourApiClient.Operation.SYNC,"0",1)).thenReturn(new TourApiClient.Page(List.of(),0));
        when(client.fetchPage(TourApiClient.Operation.SYNC,"1",1)).thenReturn(new TourApiClient.Page(List.of(
                Map.of("contentid","123","title","수집 공원","lDongRegnCd","11","modifiedtime","20260912000000","showflag","1")),1));
        when(client.fetch(TourApiClient.Operation.COMMON,"123")).thenThrow(new IllegalStateException("upstream sensitive detail"));
        assertThat(collector(catalog,client,properties).collect().status()).isEqualTo("PARTIAL");
        assertThat(catalog.collectionState().phase()).isEqualTo("HIDDEN");
        assertThat(catalog.pending(1)).hasSize(1);
        assertThat(new PetTravelService(catalog).places("11").availability()).isEqualTo("READY");
        assertThat(new PetTravelService(catalog).detail("123").petInformationStatus()).isEqualTo("FAILED");
        doReturn(List.of(Map.of("contentid","123","title","복구 공원"))).when(client).fetch(TourApiClient.Operation.COMMON,"123");
        when(client.fetch(TourApiClient.Operation.PET,"123")).thenReturn(List.of());
        assertThat(collector(catalog,client,properties).collect().status()).isEqualTo("COMPLETED");
        assertThat(catalog.detail("123").orElseThrow().pet()).isEmpty();
        assertThat(catalog.reserveRequest("TEST",java.time.LocalDate.of(2026,9,12),1)).isTrue();
        assertThat(catalog.reserveRequest("TEST",java.time.LocalDate.of(2026,9,12),1)).isFalse();
    }

    @Test
    void quota_does_not_advance_a_page_or_hide_previously_published_data() throws Exception {
        var catalog=catalog();
        var client=mock(TourApiClient.class);
        var properties=new TourApiProperties();properties.setEnabled(true);properties.setDailyRequestLimit(1);
        when(client.fetch(TourApiClient.Operation.REGIONS,"")).thenReturn(List.of(Map.of("code","11","name","서울")));
        when(client.fetchPage(TourApiClient.Operation.SYNC,"0",1)).thenReturn(new TourApiClient.Page(List.of(),0));
        assertThat(collector(catalog,client,properties).collect().status()).isEqualTo("QUOTA");
        assertThat(catalog.collectionState().phase()).isEqualTo("SHOWN");
        assertThat(catalog.collectionState().nextPage()).isEqualTo(1);
        verify(client,never()).fetchPage(TourApiClient.Operation.SYNC,"1",1);
        clearInvocations(client);
        assertThat(collector(catalog,client,properties).collect().status()).isEqualTo("QUOTA");
        verifyNoInteractions(client);
    }

    @Test
    void legal_district_collection_isolates_missing_regions_and_recovers_on_next_scan() throws Exception {
        var catalog=catalog();
        var client=mock(TourApiClient.class);
        var properties=new TourApiProperties(); properties.setEnabled(true);
        when(client.fetch(TourApiClient.Operation.REGIONS,"")).thenReturn(List.of(Map.of("code","36110","name","세종특별자치시")));
        when(client.fetchPage(TourApiClient.Operation.SYNC,"0",1)).thenReturn(new TourApiClient.Page(List.of(),0));
        var healthy=Map.of("contentid","123","title","세종 공원","lDongRegnCd","36110","modifiedtime","20260912000000","showflag","1");
        var missing=Map.of("contentid","124","title","미확인 공원","areacode","8","modifiedtime","20260912000000","showflag","1");
        when(client.fetchPage(TourApiClient.Operation.SYNC,"1",1)).thenReturn(new TourApiClient.Page(List.of(healthy,missing),2));
        when(client.fetch(TourApiClient.Operation.COMMON,"123")).thenReturn(List.of(Map.of("contentid","123","title","세종 공원")));
        when(client.fetch(TourApiClient.Operation.PET,"123")).thenReturn(List.of());
        assertThat(collector(catalog,client,properties).collect().status()).isEqualTo("PARTIAL");
        assertThat(catalog.hasUnresolvedRegions()).isTrue();
        assertThat(catalog.collectionState().errorCode()).isEqualTo("REGION_UNRESOLVED");
        assertThat(catalog.collectionState().phase()).isEqualTo("HIDDEN");
        assertThat(new PetTravelService(catalog).places("36110").items()).hasSize(1);
        verify(client,never()).fetch(TourApiClient.Operation.COMMON,"124");
        var recovered=Map.of("contentid","124","title","복구 공원","lDongRegnCd","36110","modifiedtime","20260912000000","showflag","1");
        when(client.fetchPage(TourApiClient.Operation.SYNC,"1",1)).thenReturn(new TourApiClient.Page(List.of(healthy,recovered),2));
        when(client.fetch(TourApiClient.Operation.COMMON,"124")).thenReturn(List.of(Map.of("contentid","124","title","복구 공원")));
        when(client.fetch(TourApiClient.Operation.PET,"124")).thenReturn(List.of());
        assertThat(collector(catalog,client,properties).collect().status()).isEqualTo("COMPLETED");
        assertThat(catalog.hasUnresolvedRegions()).isFalse();
        assertThat(new PetTravelService(catalog).places("36110").items()).hasSize(2);
    }

    @Test
    void orphan_run_requires_locked_recovery_and_resumes_saved_cursor_without_resetting_budget() throws Exception {
        var catalog=catalog();
        var client=mock(TourApiClient.class);
        var properties=new TourApiProperties();properties.setEnabled(true);
        var time=Instant.parse("2026-09-12T00:00:00Z");
        var runId="00000000-0000-0000-0000-000000000001";
        catalog.saveRegions(Map.of("11","서울"),time);
        catalog.observeBasic(Map.of("contentid","100","title","기존 장소","modifiedtime","20260912000000","showflag","1"),"11",time);
        catalog.publish(catalog.pending(1).get(0),Map.of("contentid","100","title","기존 장소"),Map.of(),time);
        catalog.startRun(runId,time);
        catalog.checkpoint("SHOWN",2);
        catalog.reserveRequest("COMMON",java.time.LocalDate.of(2026,9,12),900);
        assertThatThrownBy(()->collector(catalog,client,properties).collect()).hasMessage("TRAVEL_ORPHAN_RUN");
        verifyNoInteractions(client);
        var recoverySql="UPDATE pet_travel_collection_runs SET status='FAILED',error_code='OPERATOR_INTERRUPTED',finished_at=UTC_TIMESTAMP(6) "
                + "WHERE id=? AND status='RUNNING' AND IS_USED_LOCK('pawbridge_animal.petTravelCollection')=CONNECTION_ID()";
        try(var recovery=connection();var update=recovery.prepareStatement(recoverySql);var lock=recovery.createStatement()) {
            update.setString(1,runId);
            assertThat(update.executeUpdate()).isZero(); // No lock: no recovery mutation.
            try(var result=lock.executeQuery("SELECT GET_LOCK('pawbridge_animal.petTravelCollection',0)")) {
                assertThat(result.next()).isTrue();assertThat(result.getInt(1)).isEqualTo(1);
            }
            try {
                assertThatThrownBy(()->collector(catalog,client,properties).collect()).hasMessage("TRAVEL_COLLECTION_BUSY");
                assertThat(update.executeUpdate()).isEqualTo(1);
                assertThat(update.executeUpdate()).isZero(); // Already recovered: do not overwrite again.
            } finally {lock.execute("SELECT RELEASE_LOCK('pawbridge_animal.petTravelCollection')");}
        }
        assertThat(catalog.collectionState().nextPage()).isEqualTo(2);
        assertThat(catalog.detail("100").orElseThrow().publishedAt()).isEqualTo(time);
        when(client.fetch(TourApiClient.Operation.REGIONS,"")).thenReturn(List.of(Map.of("code","11","name","서울")));
        when(client.fetchPage(TourApiClient.Operation.SYNC,"1",2)).thenReturn(new TourApiClient.Page(List.of(
                Map.of("contentid","101","title","새 장소","lDongRegnCd","11","modifiedtime","20260912000000","showflag","1")),101));
        when(client.fetch(TourApiClient.Operation.COMMON,"101")).thenReturn(List.of(Map.of("contentid","101","title","새 장소")));
        when(client.fetch(TourApiClient.Operation.PET,"101")).thenReturn(List.of());
        assertThat(collector(catalog,client,properties).collect().status()).isEqualTo("COMPLETED");
        verify(client,never()).fetchPage(TourApiClient.Operation.SYNC,"1",1);
        assertThat(catalog.places("11")).hasSize(2);
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(settings.url(),settings.username(),settings.password()));
        assertThat(jdbc.queryForObject("SELECT used FROM pet_travel_request_budgets WHERE operation='COMMON' AND request_day='2026-09-12'",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT error_code FROM pet_travel_collection_runs WHERE id=?",String.class,runId)).isEqualTo("OPERATOR_INTERRUPTED");
    }

    @Test
    void configured_refresh_age_defers_fresh_details_and_recollects_expired_details() throws Exception {
        var catalog=catalog();
        var client=mock(TourApiClient.class);
        var properties=new TourApiProperties(); properties.setEnabled(true); properties.setDetailRefreshDays(21);
        when(client.fetch(TourApiClient.Operation.REGIONS,"")).thenReturn(List.of(Map.of("code","36110","name","세종")));
        when(client.fetchPage(TourApiClient.Operation.SYNC,"0",1)).thenReturn(new TourApiClient.Page(List.of(),0));
        when(client.fetchPage(TourApiClient.Operation.SYNC,"1",1)).thenReturn(new TourApiClient.Page(List.of(
                Map.of("contentid","123","title","테스트 장소","lDongRegnCd","36110","modifiedtime","20260912000000","showflag","1")),1));
        when(client.fetch(TourApiClient.Operation.COMMON,"123")).thenReturn(List.of(Map.of("contentid","123","title","테스트 장소")));
        when(client.fetch(TourApiClient.Operation.PET,"123")).thenReturn(List.of());
        var ds=new DriverManagerDataSource(settings.url(),settings.username(),settings.password());
        var start=Instant.parse("2026-09-12T00:00:00Z");
        assertThat(new PetTravelCollector(ds,catalog,client,properties,Clock.fixed(start,ZoneOffset.UTC)).collect().published()).isEqualTo(1);
        clearInvocations(client);
        assertThat(new PetTravelCollector(ds,catalog,client,properties,
                Clock.fixed(start.plus(java.time.Duration.ofDays(15)),ZoneOffset.UTC)).collect().published()).isZero();
        verify(client,never()).fetch(eq(TourApiClient.Operation.COMMON),anyString());
        verify(client,never()).fetch(eq(TourApiClient.Operation.PET),anyString());
        assertThat(catalog.detail("123").orElseThrow().publishedAt()).isEqualTo(start);
        var expired=start.plus(java.time.Duration.ofDays(22));
        assertThat(new PetTravelCollector(ds,catalog,client,properties,Clock.fixed(expired,ZoneOffset.UTC)).collect().published()).isEqualTo(1);
        verify(client,times(1)).fetch(TourApiClient.Operation.COMMON,"123");
        verify(client,times(1)).fetch(TourApiClient.Operation.PET,"123");
        assertThat(catalog.detail("123").orElseThrow().publishedAt()).isEqualTo(expired);
    }

    @Test
    void initial_collection_and_later_refresh_finish_across_long_daily_restarts() throws Exception {
        var catalog=catalog();
        var client=mock(TourApiClient.class);
        var properties=new TourApiProperties(); properties.setEnabled(true); properties.setMaxDetailsPerRun(1);
        when(client.fetch(TourApiClient.Operation.REGIONS,"")).thenReturn(List.of(Map.of("code","36110","name","세종")));
        when(client.fetchPage(TourApiClient.Operation.SYNC,"0",1)).thenReturn(new TourApiClient.Page(List.of(),0));
        var rows=java.util.stream.IntStream.rangeClosed(101,112).mapToObj(id ->
                Map.of("contentid",Integer.toString(id),"title","테스트 장소","lDongRegnCd","36110",
                        "modifiedtime","20260912000000","showflag","1")).toList();
        when(client.fetchPage(TourApiClient.Operation.SYNC,"1",1)).thenReturn(new TourApiClient.Page(rows,12));
        when(client.fetch(eq(TourApiClient.Operation.COMMON),anyString())).thenAnswer(invocation ->
                List.of(Map.of("contentid",invocation.getArgument(1,String.class),"title","테스트 장소")));
        when(client.fetch(eq(TourApiClient.Operation.PET),anyString())).thenReturn(List.of());
        var start=Instant.parse("2026-09-12T00:00:00Z");
        // Recreate both catalog and collector each day: progress must live in MySQL, not memory.
        for (int cycle=0;cycle<2;cycle++) {
            clearInvocations(client);
            for (int day=0;day<12;day++) {
                var ds=new DriverManagerDataSource(settings.url(),settings.username(),settings.password());
                var restartedCatalog=new PetTravelCatalog(new JdbcTemplate(ds),new ObjectMapper(),new DataSourceTransactionManager(ds));
                var clock=Clock.fixed(start.plus(java.time.Duration.ofDays(cycle*30L+day)),ZoneOffset.UTC);
                var result=new PetTravelCollector(ds,restartedCatalog,client,properties,clock).collect();
                assertThat(result.status()).as("cycle %s day %s",cycle,day)
                        .isEqualTo(day==11?"COMPLETED":"PARTIAL");
            }
            for (var row:rows) verify(client,times(1)).fetch(TourApiClient.Operation.COMMON,row.get("contentid"));
            assertThat(catalog.pending(100)).isEmpty();
            assertThat(catalog.collectionState().phase()).isEqualTo("HIDDEN");
        }
    }

    private PetTravelCollector collector(PetTravelCatalog catalog,TourApiClient client,TourApiProperties properties) {
        return new PetTravelCollector(new DriverManagerDataSource(settings.url(),settings.username(),settings.password()),
                catalog,client,properties,Clock.fixed(Instant.parse("2026-09-12T00:00:00Z"),ZoneOffset.UTC));
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(settings.url(), settings.username(), settings.password());
    }
}
