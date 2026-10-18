package com.pawbridge.animalservice.migration;

import com.pawbridge.animalservice.search.SearchDocumentWriter;
import java.util.Optional;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.animalservice.persistence.CollectionSessionLock;
import com.pawbridge.animalservice.persistence.CollectionSessionLock.Operation;
import com.pawbridge.animalservice.photo.ArchivedPhoto;
import com.pawbridge.animalservice.photo.PhotoArchiveStore;
import com.pawbridge.animalservice.shelter.ShelterDirectoryClient;
import com.pawbridge.animalservice.shelter.ShelterDirectoryCollector;
import com.pawbridge.animalservice.shelter.ShelterDirectoryStore;
import com.pawbridge.animalservice.travel.PetTravelCatalog;
import com.pawbridge.animalservice.travel.TourApiClient;
import com.pawbridge.animalservice.lostsearch.gallery.LostGalleryFeed;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.core.task.SyncTaskExecutor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("postgresql")
class AnimalPostgresqlCompatibilityTest {
    private HikariDataSource source;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private static final Instant NOW = Instant.parse("2026-09-19T10:00:00Z");
    private static final String APMS = "pawbridge_animal.apmsAnimalSyncJob";

    @BeforeEach
    void prepare_guarded_database() throws Exception {
        String port = System.getenv("ANIMAL_PG_MIGRATION_TEST_PORT");
        if (port == null || !port.matches("[0-9]{1,5}")) throw new IllegalStateException("Missing PostgreSQL test port");
        String url = "jdbc:postgresql://127.0.0.1:" + port + "/pawbridge";
        Map<String,String> env = AnimalPostgresqlMigrationTest.environment(url);
        env.put("ANIMAL_PG_MIGRATION_CONFIRM_TARGET", url);
        AnimalPostgresqlMigration.Settings settings = AnimalPostgresqlMigration.Settings.from(env);
        try (Connection connection = DriverManager.getConnection(url, settings.username(), settings.password());
             Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery("SELECT marker FROM migration_test_guard.guard")) {
                if (!rows.next() || !"animal-pg-disposable".equals(rows.getString(1)) || rows.next())
                    throw new IllegalStateException("Missing disposable database guard");
            }
            statement.execute("DROP SCHEMA IF EXISTS pawbridge_animal CASCADE");
            statement.execute("CREATE SCHEMA pawbridge_animal");
        }
        AnimalPostgresqlMigration.execute("migrate",settings);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);config.setUsername(settings.username());config.setPassword(settings.password());
        config.setSchema("pawbridge_animal");config.setConnectionInitSql("SET TIME ZONE 'UTC'");
        config.setMaximumPoolSize(4);config.setMinimumIdle(0);config.setConnectionTimeout(3000);
        source = new HikariDataSource(config);
        jdbc = new JdbcTemplate(source);manager = new DataSourceTransactionManager(source);
    }
    @AfterEach void close_pool() { if (source != null) source.close(); }

    @Test
    void session_lock_excludes_other_connections_survives_rollback_and_releases_once() throws Exception {
        try (Connection first = source.getConnection(); Connection second = source.getConnection()) {
            assertThat(CollectionSessionLock.query(first,APMS,Operation.ACQUIRE)).isEqualTo(1);
            try {
                assertThat(CollectionSessionLock.query(second,APMS,Operation.ACQUIRE)).isZero();
                assertThat(CollectionSessionLock.query(second,APMS,Operation.RELEASE)).isZero();
                first.setAutoCommit(false);first.rollback();first.setAutoCommit(true);
                assertThat(CollectionSessionLock.query(first,APMS,Operation.OWNED)).isEqualTo(1);
                assertThat(CollectionSessionLock.query(first,APMS,Operation.OWNED)).isEqualTo(1);
                assertThat(CollectionSessionLock.query(second,APMS,Operation.OWNED)).isZero();
                assertThat(CollectionSessionLock.query(second,"pawbridge_animal.petTravelCollection",Operation.ACQUIRE)).isEqualTo(1);
                assertThat(CollectionSessionLock.query(second,"pawbridge_animal.petTravelCollection",Operation.RELEASE)).isEqualTo(1);
            } finally { CollectionSessionLock.query(first,APMS,Operation.RELEASE); }
            assertThat(CollectionSessionLock.query(second,APMS,Operation.ACQUIRE)).isEqualTo(1);
            assertThat(CollectionSessionLock.query(second,APMS,Operation.RELEASE)).isEqualTo(1);
        }
    }

    @Test
    void photo_claim_rejects_changed_source_and_expired_worker_without_holding_a_connection() {
        seedAnimal();
        PhotoArchiveStore store = new PhotoArchiveStore(jdbc,manager);
        store.discover(20);
        PhotoArchiveStore.Claim old = store.claim(300).orElseThrow();
        assertThat(source.getHikariPoolMXBean().getActiveConnections()).isZero();
        ArchivedPhoto photo = new ArchivedPhoto(new byte[]{1},"a".repeat(64),"b".repeat(64),"image/webp",10,10,"test");
        jdbc.update("UPDATE animals SET image_url='http://example.test/Photo.jpg' WHERE id=1");
        assertThat(store.complete(old,photo,600)).isFalse();
        jdbc.update("UPDATE animals SET image_url='http://example.test/photo.jpg' WHERE id=1");
        jdbc.update("UPDATE apms_photo_archive SET lease_until=clock_timestamp()-INTERVAL '1 second'");
        PhotoArchiveStore.Claim replacement = store.claim(300).orElseThrow();
        assertThat(store.complete(old,photo,600)).isFalse();
        assertThat(store.retry(old,"OLD_WORKER",5)).isFalse();
        assertThat(store.complete(replacement,photo,600)).isTrue();
        assertThat(store.claim(300)).isEmpty();
        jdbc.update("UPDATE animals SET image_url='http://example.test/new.jpg' WHERE id=1");
        store.discover(20);store.discover(20);
        PhotoArchiveStore.Claim changed = store.claim(300,true).orElseThrow();
        assertThat(changed.generation()).isEqualTo(2);
        assertThat(store.retry(changed,"HTTP_FAILURE",60)).isTrue();
        assertThat(store.claim(300)).isEmpty();
    }

    @Test
    void travel_upserts_keep_public_filters_checkpoints_and_stale_write_protection() {
        PetTravelCatalog catalog = catalog();
        catalog.saveRegions(Map.of("11","서울"),NOW);
        catalog.saveRegions(Map.of("11","서울특별시","26","부산"),NOW.plusSeconds(1));
        assertThat(catalog.regions()).extracting(PetTravelCatalog.Region::code).containsExactly("11","26");
        for (int i=100;i<112;i++) catalog.observeBasic(row(Integer.toString(i),"12"),"11",NOW);
        catalog.observeBasic(row("200","32"),"11",NOW);
        assertThat(catalog.countPlaces("11")).isEqualTo(12);
        assertThat(catalog.places("11",0)).hasSize(10);
        assertThat(catalog.places("11",1)).hasSize(2);
        PetTravelCatalog.Target target = catalog.pending(100).stream().filter(t->t.contentId().equals("100")).findFirst().orElseThrow();
        assertThat(catalog.publish(target,row("100","12"),Map.of("contentid","100","acmpyNeedMtr","목줄"),NOW)).isTrue();
        for (PetTravelCatalog.Target remaining : catalog.pending(100))
            catalog.publishCommon(remaining,row(remaining.contentId(),"12"),NOW);
        catalog.beginDetails(NOW.plusSeconds(1));
        PetTravelCatalog.Target refresh = catalog.pending(100).stream().filter(t->t.contentId().equals("100")).findFirst().orElseThrow();
        assertThat(catalog.publishCommon(refresh,row("100","12"),NOW.plusSeconds(2))).isTrue();
        assertThat(catalog.detail("100").orElseThrow().pet()).containsEntry("acmpyNeedMtr","목줄");
        catalog.observe("100","11","20260920000000",false,null,null,NOW.plusSeconds(3),false);
        assertThat(catalog.publish(refresh,row("100","12"),Map.of(),NOW)).isFalse();
        assertThat(catalog.detail("100")).isEmpty();
        assertThat(catalog.countPlaces("11")).isEqualTo(11);
        assertThat(catalog.reserveRequest("COMMON",LocalDate.of(2026,9,19),1)).isTrue();
        assertThat(catalog.reserveRequest("COMMON",LocalDate.of(2026,9,19),1)).isFalse();
        catalog.checkpoint("SHOWN",2);assertThat(catalog.collectionState().nextPage()).isEqualTo(2);
        catalog.completeCycle(NOW);assertThat(catalog.collectionState().phase()).isEqualTo("HIDDEN");
    }

    @Test
    void bulk_pet_and_visit_json_are_updated_without_losing_independent_resources() {
        PetTravelCatalog catalog = catalog();catalog.observeBasic(row("100","12"),"11",NOW);
        PetTravelCatalog.PetCollectionState state = catalog.petCollectionState();
        TourApiClient.Page page = new TourApiClient.Page(List.of(Map.of("contentid","100","acmpyNeedMtr","목줄")),1);
        catalog.savePetPage(state,page,NOW,NOW.plusSeconds(86400));
        assertThat(catalog.petCollectionState().nextPage()).isEqualTo(1);
        assertThat(catalog.detail("100").orElseThrow().pet()).containsEntry("acmpyNeedMtr","목줄");
        assertThatThrownBy(()->catalog.savePetPage(state,page,NOW,NOW)).hasMessage("PET_CHECKPOINT_CHANGED");
        PetTravelCatalog.VisitTarget visit = catalog.pendingVisitDetails(PetTravelCatalog.VisitResource.INTRO,10).get(0);
        assertThat(catalog.saveVisitIntro(visit,List.of(Map.of("contentid","100","contenttypeid","12","parking","가능")),NOW)).isTrue();
        assertThat(catalog.saveVisitInformation(visit,List.of(Map.of("contentid","100","contenttypeid","12","infoname","안내")),NOW)).isTrue();
        assertThat(catalog.saveVisitImages(visit,List.of(Map.of("contentid","100","serialnum","1","originimgurl","https://example.test/a.jpg","cpyrhtDivCd","Type1")),NOW)).isTrue();
        PetTravelCatalog.Place detail = catalog.detail("100").orElseThrow();
        assertThat(detail.intro()).containsEntry("parking","가능");assertThat(detail.information()).hasSize(1);assertThat(detail.images()).hasSize(1);
        assertThat(catalog.pendingVisitDetails(PetTravelCatalog.VisitResource.IMAGES,10)).isEmpty();
        catalog.visitDetailFailed(PetTravelCatalog.VisitResource.INTRO,visit,NOW.plusSeconds(1));
        assertThat(catalog.detail("100").orElseThrow().images()).hasSize(1);
    }

    @Test
    void shelter_updates_merge_scalar_patch_and_remove_stale_coordinates() throws Exception {
        try (com.pawbridge.animalservice.search.KoreanSearchAnalyzer analyzer=new com.pawbridge.animalservice.search.KoreanSearchAnalyzer()) {
        source.setMaximumPoolSize(1); // Save must reuse the collector session, not borrow another one.
        ShelterDirectoryClient client = mock(ShelterDirectoryClient.class);
        ShelterDirectoryStore store = new ShelterDirectoryStore(jdbc,new ObjectMapper().findAndRegisterModules(),new SearchDocumentWriter(source,Optional.of(analyzer)));
        ShelterDirectoryCollector collector = new ShelterDirectoryCollector(source,client,store);
        when(client.collect()).thenReturn(List.of(Map.of("careRegNo","shelter-1","careNm","보호소","careAddr","서울","careTel","02-123","lat","37.5","lng","127","dataStdDt","2026-09-19")));
        assertThat(collector.collect()).isEqualTo(1);
        long id = jdbc.queryForObject("SELECT id FROM shelters WHERE care_reg_no='shelter-1'",Long.class);
        when(client.collect()).thenReturn(List.of(Map.of("careRegNo","shelter-1","careNm","보호소","careAddr","부산","dataStdDt","2026-09-20")));
        collector.collect();
        assertThat(store.find(id).latitude()).isNull();assertThat(store.find(id).phone()).isEqualTo("02-123");
        when(client.collect()).thenReturn(List.of(Map.of("careRegNo","shelter-1","careNm","오래된 이름","careAddr","서울","dataStdDt","2026-09-18")));
        collector.collect();
        assertThat(jdbc.queryForObject("SELECT address FROM shelters WHERE id=?",String.class,id)).isEqualTo("부산");
        assertThat(jdbc.queryForObject("SELECT search_dirty FROM shelters WHERE id=?",Boolean.class,id)).isFalse();
        assertThat(jdbc.queryForObject("SELECT tokens @@ plainto_tsquery('simple','부산') FROM shelter_search_documents WHERE shelter_id=?",Boolean.class,id)).isTrue();
        // Missing incoming address must preserve both the existing address and its search terms.
        when(client.collect()).thenReturn(List.of(Map.of("careRegNo","shelter-1","careNm","새 보호소")));
        collector.collect();
        assertThat(jdbc.queryForObject("SELECT tokens @@ plainto_tsquery('simple','부산') FROM shelter_search_documents WHERE shelter_id=?",Boolean.class,id)).isTrue();
        jdbc.execute("ALTER TABLE shelter_search_documents ADD CONSTRAINT sync_failure CHECK(shelter_id<>"+id+") NOT VALID");
        try {
            when(client.collect()).thenReturn(List.of(Map.of("careRegNo","shelter-1","careNm","실패 보호소","careAddr","대구")));
            assertThatThrownBy(collector::collect).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(jdbc.queryForObject("SELECT address FROM shelters WHERE id=?",String.class,id)).isEqualTo("부산");
        } finally { jdbc.execute("ALTER TABLE shelter_search_documents DROP CONSTRAINT sync_failure"); }
        assertThat(source.getHikariPoolMXBean().getActiveConnections()).isZero();
        }
    }

    @Test
    void spring_batch_runs_and_persists_execution_context_using_postgresql_sequences() throws Exception {
        JobRepositoryFactoryBean factory = new JobRepositoryFactoryBean();
        factory.setDataSource(source);factory.setTransactionManager(manager);factory.setTablePrefix("pawbridge_animal.BATCH_");
        factory.afterPropertiesSet();JobRepository repository = factory.getObject();
        Job job = new JobBuilder("postgresqlCompatibility",repository).start(new StepBuilder("write",repository)
                .tasklet((contribution,context)->{
                    contribution.getStepExecution().getExecutionContext().putString("cursor","동물-100");
                    jdbc.update("INSERT INTO processed_events VALUES ('batch-test','test',CURRENT_TIMESTAMP)");
                    return RepeatStatus.FINISHED;
                },manager).build()).build();
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();launcher.setJobRepository(repository);
        launcher.setTaskExecutor(new SyncTaskExecutor());launcher.afterPropertiesSet();
        JobExecution execution = launcher.run(job,new JobParametersBuilder().addLong("run",1L).toJobParameters());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        JobExecution saved = repository.getLastJobExecution(job.getName(),execution.getJobParameters());
        assertThat(saved.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(repository.getLastStepExecution(saved.getJobInstance(),"write")
                .getExecutionContext().getString("cursor")).isEqualTo("동물-100");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events",Integer.class)).isEqualTo(1);
    }

    @Test
    void gallery_streams_on_postgresql_and_returns_connection_on_consumer_failure() {
        seedAnimal();
        jdbc.update("INSERT INTO apms_photo_archive(animal_id,slot,desertion_no,source_url,state,archived_source_url,stored_sha256,object_key,stored_bytes,content_type) VALUES (1,1,'test-desertion','http://example.test/photo.jpg','READY','http://example.test/photo.jpg',?, ?,1,'image/webp')", "a".repeat(64),"apms/photos/"+"a".repeat(64)+".webp");
        LostGalleryFeed feed = new LostGalleryFeed(jdbc,new ObjectMapper(),null);
        TransactionTemplate transaction = new TransactionTemplate(manager);transaction.setReadOnly(true);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        AtomicInteger count = new AtomicInteger();
        transaction.executeWithoutResult(status -> feed.streamEntries(entry -> count.incrementAndGet()));
        assertThat(count.get()).isEqualTo(1);
        assertThatThrownBy(()->transaction.executeWithoutResult(status -> feed.streamEntries(entry->{throw new IllegalStateException("consumer stopped");})))
                .hasMessage("consumer stopped");
        assertThat(source.getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    @Test
    void existing_jpa_entities_validate_against_the_postgresql_schema() {
        org.hibernate.boot.registry.StandardServiceRegistry registry = new org.hibernate.boot.registry.StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.datasource",source)
                .applySetting("hibernate.default_schema","pawbridge_animal")
                .applySetting("hibernate.hbm2ddl.auto","validate")
                .applySetting("hibernate.physical_naming_strategy","org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                .build();
        try {
            org.hibernate.boot.MetadataSources metadata = new org.hibernate.boot.MetadataSources(registry);
            for (Class<?> entity : List.of(
                    com.pawbridge.animalservice.entity.Animal.class,com.pawbridge.animalservice.entity.Shelter.class,
                    com.pawbridge.animalservice.entity.SyncHistory.class,com.pawbridge.animalservice.entity.ProcessedEvent.class,
                    com.pawbridge.animalservice.entity.OutboxEvent.class,com.pawbridge.animalservice.chatbot.entity.ChatbotSession.class,
                    com.pawbridge.animalservice.chatbot.entity.ChatbotMessage.class,com.pawbridge.animalservice.chatbot.entity.ChatbotBlockLog.class)) {
                metadata.addAnnotatedClass(entity);
            }
            try (org.hibernate.SessionFactory factory = metadata.buildMetadata().buildSessionFactory()) {
                assertThat(factory.isOpen()).isTrue();
            }
        } finally { org.hibernate.boot.registry.StandardServiceRegistryBuilder.destroy(registry); }
    }

    private PetTravelCatalog catalog() { return new PetTravelCatalog(jdbc,new ObjectMapper(),manager); }
    private Map<String,String> row(String id,String type) {
        return Map.of("contentid",id,"title","공원"+id,"contenttypeid",type,"showflag","1","modifiedtime","20260919000000");
    }
    private void seedAnimal() {
        jdbc.update("INSERT INTO shelters(id,created_at,care_reg_no,name) VALUES (1,CURRENT_TIMESTAMP,'test-shelter','보호소')");
        jdbc.update("INSERT INTO animals(id,created_at,api_source,apms_desertion_no,apms_notice_no,favorite_count,gender,neuter_status,notice_end_date,notice_start_date,species,status,shelter_id,image_url) VALUES (1,CURRENT_TIMESTAMP,'APMS_ANIMAL','test-desertion','test-notice',0,'UNKNOWN','UNKNOWN',CURRENT_DATE,CURRENT_DATE,'DOG','PROTECT',1,'http://example.test/photo.jpg')");
    }
}
