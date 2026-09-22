package com.pawbridge.animalservice.migration;

import com.pawbridge.animalservice.search.SearchDocumentWriter;
import com.pawbridge.animalservice.batch.writer.AnimalItemWriter;
import com.pawbridge.animalservice.service.AnimalCommandService;
import com.pawbridge.animalservice.service.OutboxService;
import com.pawbridge.animalservice.service.NoticeNumberGenerator;
import com.pawbridge.animalservice.repository.ShelterRepository;
import com.pawbridge.animalservice.repository.OutboxEventRepository;
import com.pawbridge.animalservice.dto.request.UpdateAnimalDescriptionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.springframework.batch.item.Chunk;
import static org.mockito.Mockito.mock;

import com.pawbridge.animalservice.admin.repository.AdminStatsRepository;
import com.pawbridge.animalservice.search.KoreanSearchAnalyzer;
import com.pawbridge.animalservice.search.PostgresqlSearchProjector;
import com.pawbridge.animalservice.exception.SearchProjectionPendingException;
import com.pawbridge.animalservice.dto.request.AnimalSearchRequest;
import com.pawbridge.animalservice.dto.response.AnimalResponse;
import com.pawbridge.animalservice.dto.response.StatusStatsResponse;
import com.pawbridge.animalservice.entity.*;
import com.pawbridge.animalservice.enums.*;
import com.pawbridge.animalservice.mapper.AnimalMapper;
import com.pawbridge.animalservice.repository.AnimalRepository;
import com.pawbridge.animalservice.repository.AnimalStatsRepository;
import com.pawbridge.animalservice.service.AnimalQueryService;
import com.pawbridge.animalservice.service.PostgresqlAnimalQueryService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@Tag("postgresql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnimalPostgresqlQueryTest {
    private HikariDataSource source;
    private JdbcTemplate jdbc;
    private SessionFactory factory;
    private StandardServiceRegistry registry;
    private AnimalQueryService query;
    private AnimalRepository animals;
    private ShelterRepository shelters;
    private OutboxEventRepository outbox;
    private AnimalStatsRepository stats;
    private AdminStatsRepository adminStats;
    private TransactionTemplate transaction;
    private final LocalDate today=LocalDate.now();

    @BeforeAll
    void prepare_guarded_schema_and_real_jpa_repositories() throws Exception {
        String port=System.getenv("ANIMAL_PG_MIGRATION_TEST_PORT");
        if (port==null || !port.matches("[0-9]{1,5}")) throw new IllegalStateException("Disposable PostgreSQL port required");
        String url="jdbc:postgresql://127.0.0.1:"+port+"/pawbridge";
        Map<String,String> env=AnimalPostgresqlMigrationTest.environment(url);
        env.put("ANIMAL_PG_MIGRATION_CONFIRM_TARGET",url);
        AnimalPostgresqlMigration.Settings settings=AnimalPostgresqlMigration.Settings.from(env);
        try (Connection connection=DriverManager.getConnection(url,settings.username(),settings.password());
             Statement statement=connection.createStatement()) {
            try (ResultSet rows=statement.executeQuery("SELECT marker FROM migration_test_guard.guard")) {
                if (!rows.next() || !"animal-pg-disposable".equals(rows.getString(1)) || rows.next())
                    throw new IllegalStateException("Missing disposable database guard");
            }
            statement.execute("DROP SCHEMA IF EXISTS pawbridge_animal CASCADE");
            statement.execute("CREATE SCHEMA pawbridge_animal");
        }
        AnimalPostgresqlMigration.execute("migrate",settings);
        HikariConfig config=new HikariConfig();config.setJdbcUrl(url);config.setUsername(settings.username());
        config.setPassword(settings.password());config.setSchema("pawbridge_animal");
        config.setConnectionInitSql("SET TIME ZONE 'UTC'");config.setMaximumPoolSize(3);config.setMinimumIdle(0);
        source=new HikariDataSource(config);jdbc=new JdbcTemplate(source);
        registry=new StandardServiceRegistryBuilder()
                .applySettings(new org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter().getJpaPropertyMap())
                .applySetting("hibernate.connection.datasource",source)
                .applySetting("hibernate.default_schema","pawbridge_animal").applySetting("hibernate.hbm2ddl.auto","validate")
                .applySetting("hibernate.physical_naming_strategy","org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy").build();
        MetadataSources metadata=new MetadataSources(registry);
        for (Class<?> entity:List.of(Animal.class,Shelter.class,SyncHistory.class,ProcessedEvent.class,OutboxEvent.class,
                com.pawbridge.animalservice.chatbot.entity.ChatbotSession.class,
                com.pawbridge.animalservice.chatbot.entity.ChatbotMessage.class,
                com.pawbridge.animalservice.chatbot.entity.ChatbotBlockLog.class)) metadata.addAnnotatedClass(entity);
        // Standalone Hibernate has no Spring auditing context. Supply only fixture timestamps.
        factory=metadata.buildMetadata().getSessionFactoryBuilder().applyInterceptor(new org.hibernate.Interceptor() {
            @Override
            public boolean onPersist(Object entity,Object id,Object[] state,String[] names,org.hibernate.type.Type[] types) {
                if (!(entity instanceof BaseTimeEntity)) return false;
                boolean changed=false;
                for(int index=0;index<names.length;index++) {
                    if ((names[index].equals("createdAt") || names[index].equals("updatedAt")) && state[index]==null) {
                        state[index]=today.atStartOfDay();
                        org.springframework.test.util.ReflectionTestUtils.setField(entity,names[index],state[index]);
                        changed=true;
                    }
                }
                return changed;
            }
        }).build();
        EntityManager shared=SharedEntityManagerCreator.createSharedEntityManager(factory);
        JpaRepositoryFactory repositories=new JpaRepositoryFactory(shared);
        animals=repositories.getRepository(AnimalRepository.class);
        shelters=repositories.getRepository(ShelterRepository.class);
        outbox=repositories.getRepository(OutboxEventRepository.class);
        stats=repositories.getRepository(AnimalStatsRepository.class);
        adminStats=repositories.getRepository(AdminStatsRepository.class);
        JpaTransactionManager manager=new JpaTransactionManager(factory);manager.setDataSource(source);
        manager.setJpaDialect(new org.springframework.orm.jpa.vendor.HibernateJpaDialect());
        transaction=new TransactionTemplate(manager);transaction.setReadOnly(true);
        ProxyFactory proxy=new ProxyFactory(new PostgresqlAnimalQueryService(source,animals,new AnimalMapper()));
        TransactionInterceptor interceptor=new TransactionInterceptor();interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());proxy.addAdvice(interceptor);
        query=(AnimalQueryService)proxy.getProxy();
    }

    @BeforeEach
    void seed_representative_animals() {
        jdbc.execute("TRUNCATE animals,shelters CASCADE");
        jdbc.update("INSERT INTO shelters(id,created_at,care_reg_no,name,address) VALUES (1,NOW(),'seoul','서울 보호소','서울특별시 도봉구'),(2,NOW(),'busan','부산 보호소','부산광역시 강서구')");
        animal(1,"NOTICE","말티즈","흰색","오른쪽 귀 검정",today,1,today.getYear()-2);
        animal(2,"PROTECT","믹스견","검정","왼쪽 귀 흰색",today,1,today.getYear()-4);
        animal(3,"ADOPTED","말티즈","흰색","오른쪽 귀 검정",today.minusDays(1),1,today.getYear()-1);
        animal(4,"EUTHANIZED","말티즈","흰색","오른쪽 귀 검정",today,2,null);
        animal(5,"PROTECT","푸들","갈색","목줄 100%_표식",today.minusDays(29),2,today.getYear());
        animal(6,"PROTECT","말티쥬","흰색","특징 없음",today.minusDays(30),2,today.getYear()-3);
    }
    private void animal(long id,String status,String breed,String color,String mark,LocalDate date,long shelter,Integer birthYear) {
        jdbc.update("INSERT INTO animals(id,created_at,updated_at,api_source,apms_desertion_no,apms_notice_no,favorite_count,"
                +"gender,neuter_status,notice_start_date,notice_end_date,species,status,shelter_id,breed,color,special_mark,"
                +"happen_date,apms_updated_at,birth_year,image_url) VALUES (?,?,?,'APMS_ANIMAL',?,?,0,"
                +"'MALE','NO',?,?,'DOG',?,?,?,?,?,?,?,?,?)",id,today.atStartOfDay(),today.atStartOfDay(),"D-"+id,"MAN-"+id,today,today.plusDays(2),status,shelter,
                breed,color,mark,date,today.atTime(1,0),birthYear,"https://example.test/"+id+".jpg");
    }
    private List<Long> ids(Page<AnimalResponse> page) { return page.getContent().stream().map(AnimalResponse::getId).toList(); }
    private PageRequest relevance() { return PageRequest.of(0,20,Sort.by(Sort.Direction.DESC,"relevance")); }

    @Test
    void default_states_and_exact_notice_lookup_keep_different_visibility_contracts() {
        Page<AnimalResponse> all=query.searchAnimals(new AnimalSearchRequest(),PageRequest.of(0,2));
        assertThat(all.getTotalElements()).isEqualTo(4);assertThat(ids(all)).containsExactly(1L,2L);
        assertThat(ids(query.searchAnimals(new AnimalSearchRequest(),PageRequest.of(1,2)))).containsExactly(5L,6L);
        assertThat(ids(query.searchAnimals(AnimalSearchRequest.builder().noticeNo(" MAN-3 ").build(),PageRequest.of(0,20)))).containsExactly(3L);
        assertThat(query.searchAnimals(AnimalSearchRequest.builder().noticeNo("MAN-3*").build(),PageRequest.of(0,20))).isEmpty();
        assertThat(query.searchAnimals(AnimalSearchRequest.builder().noticeNo("man-3").build(),PageRequest.of(0,20))).isEmpty();
        assertThat(query.searchAnimals(AnimalSearchRequest.builder().noticeNo("MAN-3").status(AnimalStatus.PROTECT).build(),PageRequest.of(0,20))).isEmpty();
        assertThat(query.findByApmsDesertionNo("D-3").getStatus()).isEqualTo(AnimalStatus.ADOPTED);
    }

    @Test
    void structured_filters_and_age_sort_keep_count_page_and_nulls_consistent() {
        AnimalSearchRequest filters=AnimalSearchRequest.builder().region("서울").city("도봉구").shelterId(1L)
                .species(Species.DOG).gender(Gender.MALE).neuterStatus(NeuterStatus.NO).minAge(1).maxAge(3).build();
        Page<AnimalResponse> page=query.searchAnimals(filters,PageRequest.of(0,1));
        assertThat(page.getTotalElements()).isEqualTo(1);assertThat(ids(page)).containsExactly(1L);
        assertThat(page.getContent().get(0).getAge()).isEqualTo(2);
        jdbc.update("UPDATE animals SET birth_year=NULL WHERE id=2");
        assertThat(ids(query.searchAnimals(new AnimalSearchRequest(),PageRequest.of(0,20,Sort.by("age"))))).containsExactly(5L,1L,6L,2L);
        assertThat(query.searchAnimals(AnimalSearchRequest.builder().region("%_").build(),PageRequest.of(0,20))).isEmpty();
        Page<AnimalResponse> empty=query.searchAnimals(filters,PageRequest.of(3,1));
        assertThat(empty).isEmpty();assertThat(empty.getTotalElements()).isEqualTo(1);
    }

    @Test
    void korean_text_requires_literal_evidence_without_automatic_typo_expansion() {
        assertThat(ids(query.searchAnimals(AnimalSearchRequest.builder().keyword("흰색 말티즈 오른쪽 귀 검정").build(),relevance()))).startsWith(1L).doesNotContain(3L,4L);
        assertThat(ids(query.searchAnimals(AnimalSearchRequest.builder().breed("말티즈").build(),relevance()))).containsExactly(1L);
        assertThat(ids(query.searchAnimals(AnimalSearchRequest.builder().keyword("100%_표식").build(),relevance()))).containsExactly(5L);
        assertThat(query.searchAnimals(AnimalSearchRequest.builder().keyword("' OR 1=1 --").build(),relevance())).isEmpty();
        assertThat(ids(query.searchAnimals(AnimalSearchRequest.builder().keyword("푸들").build(),relevance()))).containsExactly(5L);
        assertThat(ids(query.searchAnimals(AnimalSearchRequest.builder().keyword("말티즈").build(),relevance()))).containsExactly(1L);
        assertThat(query.searchAnimals(AnimalSearchRequest.builder().keyword("말티즈 흰색 없는낱말").build(),relevance())).isEmpty();
    }

    @Test
    void partial_breed_search_is_identical_with_or_without_analysis_and_never_requires_projection() throws Exception {
        animal(16,"PROTECT","포메라니안","갈색","특징 없음",today,1,2025);
        animal(17,"PROTECT","포메라니안 믹스","갈색","특징 없음",today,1,2025);
        try (KoreanSearchAnalyzer analyzer=new KoreanSearchAnalyzer()) {
            for (AnimalQueryService backend:List.of(query,analyzedQuery(analyzer))) {
                assertThat(ids(backend.searchAnimals(AnimalSearchRequest.builder().breed(" 포메 ").build(),relevance())))
                        .containsExactly(16L,17L);
                assertThat(ids(backend.searchAnimals(AnimalSearchRequest.builder().breed("포메라니안").build(),relevance())))
                        .containsExactly(16L,17L);
                assertThat(backend.searchAnimals(AnimalSearchRequest.builder().breed("포메라니언").build(),relevance())).isEmpty();
                assertThat(backend.searchAnimals(AnimalSearchRequest.builder().breed("%_").build(),relevance())).isEmpty();
                assertThat(ids(backend.searchAnimals(AnimalSearchRequest.builder().breed("말티즈").build(),relevance())))
                        .containsExactly(1L);
            }
        }
    }

    @Test
    void analyzed_postgresql_candidate_recovers_inflections_without_matching_unrelated_traits() throws Exception {
        animal(7,"PROTECT","믹스견","흰색","흰털 접힌귀",today,1,2025);
        animal(8,"PROTECT","믹스견","갈색","쫑긋한 삼각귀",today,1,2025);
        animal(9,"PROTECT","믹스견","갈색","완전 귀여움",today,1,2025);
        animal(10,"PROTECT","믹스견","갈색","겁이 많고 얌전함",today,1,2025);
        animal(11,"PROTECT","믹스견","갈색","겁이 없고 활발함",today,1,2025);
        animal(12,"PROTECT","믹스견","갈색","사람을 좋아해요",today,1,2025);
        animal(13,"PROTECT","믹스견","갈색","눈 상태 안 좋음",today,1,2025);
        animal(14,"PROTECT","믹스견","검정","특징 없음",today,1,2025);
        animal(15,"PROTECT","믹스견","흰색","흰색 노견",today,1,2025);
        animal(16,"PROTECT","포메라니안","갈색","특징 없음",today,1,2025);
        animal(17,"ADOPTED","믹스견","흰색","흰털 접힌귀",today,1,2025);
        animal(18,"PROTECT","믹스견","갈색","온순하고 사람을 좋아함",today,1,2025);
        animal(19,"PROTECT","믹스견","갈색","사람을 좋아하는 아기강아지",today,1,2025);
        try (KoreanPostgresqlSearchEvaluation candidate=new KoreanPostgresqlSearchEvaluation(jdbc)) {
            candidate.prepare();
            assertThat(candidate.search("keyword","귀 접힘",20).ids()).containsExactly(7L);
            assertThat(candidate.search("keyword","겁이 많음",20).ids()).containsExactly(10L);
            assertThat(candidate.search("keyword","사람을 좋아",20).ids()).containsExactlyInAnyOrder(12L,18L,19L);
            assertThat(candidate.search("keyword","검정색",20).ids()).contains(2L,14L).doesNotContain(15L);
            assertThat(candidate.search("breed","포메라니언",20).ids()).containsExactly(16L);
            assertThat(candidate.search("keyword","' OR 1=1 --",20).ids()).isEmpty();
        }
    }

    @Test
    void predicate_search_does_not_join_different_clauses_or_ignore_negation() throws Exception {
        animal(20,"PROTECT","믹스견","갈색","아직은 사람이 무서움/ 엄마품을 너무 좋아하는 아기강아지",today,1,2025);
        animal(21,"PROTECT","믹스견","갈색","신발훔치는걸 좋아한다고함/ 겁이 없고 사람을 안무서워 하는듯함",today,1,2025);
        animal(22,"PROTECT","믹스견","갈색","사람을 무서워하고 간식을 좋아함",today,1,2025);
        animal(23,"PROTECT","믹스견","갈색","사람 손길을 좋아함",today,1,2025);
        animal(24,"PROTECT","믹스견","갈색","사람을 안 좋아함",today,1,2025);
        animal(25,"PROTECT","믹스견","갈색","사람을 좋아하지 않음",today,1,2025);
        animal(26,"PROTECT","믹스견","갈색","사람을 좋아하는 것은 아님",today,1,2025);
        animal(27,"PROTECT","믹스견","갈색","사람을 좋아하지만 겁이 많음",today,1,2025);
        animal(28,"PROTECT","믹스견","갈색","사람 엄청 좋아함",today,1,2025);
        animal(29,"PROTECT","믹스견","갈색","사람",today,1,2025);
        jdbc.update("UPDATE animals SET description='좋아함' WHERE id=29");
        animal(30,"PROTECT","믹스견","갈색","사람을 좋아하고 잘 짖지 않음",today,1,2025);
        animal(31,"PROTECT","믹스견","갈색","사람을 좋아하지 않지만 다른 강아지는 좋아함",today,1,2025);
        try (KoreanPostgresqlSearchEvaluation candidate=new KoreanPostgresqlSearchEvaluation(jdbc)) {
            candidate.prepare();
            assertThat(candidate.search("keyword","사람을 좋아",100).ids()).containsExactlyInAnyOrder(23L,27L,28L,30L);
            assertThat(candidate.search("keyword","사람을 좋아하지 않음",100).ids()).containsExactlyInAnyOrder(24L,25L,26L,31L);
        }
    }

    @Test
    void unprepared_keyword_search_fails_without_analyzing_source_while_structured_search_remains_available() throws Exception {
        try (KoreanSearchAnalyzer analyzer=new KoreanSearchAnalyzer()) {
            PostgresqlSearchProjector projector=new PostgresqlSearchProjector(source,analyzer);
            AnimalQueryService analyzed=analyzedQuery(analyzer);
            AnimalSearchRequest request=AnimalSearchRequest.builder().keyword("검정").build();
            assertThatThrownBy(()->analyzed.searchAnimals(request,relevance())).isInstanceOf(SearchProjectionPendingException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM animal_search_documents",Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM animals WHERE search_dirty",Long.class)).isEqualTo(6);
            assertThat(analyzed.searchAnimals(new AnimalSearchRequest(),PageRequest.of(0,20)).getTotalElements()).isEqualTo(4);
            assertThat(projector.refreshDirtyAnimals(2)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM animals WHERE search_dirty",Long.class)).isEqualTo(4);
            assertThatThrownBy(()->analyzed.searchAnimals(request,relevance())).isInstanceOf(SearchProjectionPendingException.class);
            assertThat(projector.refreshDirtyAnimals(100)).isEqualTo(4);
            assertThat(projector.refreshDirtyShelters(100)).isEqualTo(2);
            assertThat(ids(analyzed.searchAnimals(request,relevance()))).containsExactlyInAnyOrder(1L,2L);
            assertThat(projector.refreshDirtyAnimals(100)).isZero();
            assertThatThrownBy(()->projector.refreshDirtyAnimals(101)).isInstanceOf(IllegalArgumentException.class);
            // Live eligibility/favorite changes don't need text re-analysis.
            jdbc.update("UPDATE animals SET status='ADOPTED',favorite_count=4 WHERE id=1");
            assertThat(projector.refreshDirtyAnimals(100)).isZero();
            assertThat(ids(analyzed.searchAnimals(request,relevance()))).containsExactly(2L);
            assertThat(ids(analyzed.searchAnimals(AnimalSearchRequest.builder().keyword("검정").noticeNo("MAN-1").build(),relevance())))
                    .containsExactly(1L);
        }
    }

    @Test
    void description_and_search_document_commit_together_and_projection_failure_rolls_back_source() throws Exception {
        try (KoreanSearchAnalyzer analyzer=new KoreanSearchAnalyzer()) {
            PostgresqlSearchProjector maintenance=new PostgresqlSearchProjector(source,analyzer);
            maintenance.refreshAnimals(100);maintenance.refreshShelters(100);
            SearchDocumentWriter documents=new SearchDocumentWriter(source,Optional.of(analyzer));
            AnimalCommandService command=new AnimalCommandService(documents,animals,shelters,new AnimalMapper(),
                    new OutboxService(outbox,new ObjectMapper().findAndRegisterModules()),mock(NoticeNumberGenerator.class));
            TransactionTemplate write=new TransactionTemplate(transaction.getTransactionManager());
            UpdateAnimalDescriptionRequest request=new UpdateAnimalDescriptionRequest();
            request.setDescription("사람을 좋아함");
            write.executeWithoutResult(status -> command.updateDescription(1L,request));
            assertThat(jdbc.queryForObject("SELECT search_dirty FROM animals WHERE id=1",Boolean.class)).isFalse();
            assertThat(jdbc.queryForObject("SELECT source_revision FROM animal_search_documents WHERE animal_id=1",Long.class))
                    .isEqualTo(jdbc.queryForObject("SELECT search_revision FROM animals WHERE id=1",Long.class));
            assertThat(ids(analyzedQuery(analyzer).searchAnimals(AnimalSearchRequest.builder().keyword("사람을 좋아").build(),relevance())))
                    .containsExactly(1L);
            assertThat(maintenance.refreshAnimals(100)).isZero();
            long events=outbox.count();
            jdbc.execute("ALTER TABLE animal_search_documents ADD CONSTRAINT sync_failure CHECK (animal_id<>1) NOT VALID");
            try {
                request.setDescription("새로운 설명");
                assertThatThrownBy(()->write.executeWithoutResult(status->command.updateDescription(1L,request)))
                        .isInstanceOf(org.springframework.dao.DataAccessException.class);
                assertThat(jdbc.queryForObject("SELECT description FROM animals WHERE id=1",String.class)).isEqualTo("사람을 좋아함");
                assertThat(jdbc.queryForObject("SELECT source_revision FROM animal_search_documents WHERE animal_id=1",Long.class)).isEqualTo(2);
                assertThat(outbox.count()).isEqualTo(events);
            } finally { jdbc.execute("ALTER TABLE animal_search_documents DROP CONSTRAINT sync_failure"); }
            assertThatThrownBy(()->documents.animal(1L)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void apms_writer_publishes_new_and_existing_search_rows_in_the_chunk_transaction() throws Exception {
        jdbc.execute("SELECT setval(pg_get_serial_sequence('animals','id'),1000)");
        try (KoreanSearchAnalyzer analyzer=new KoreanSearchAnalyzer()) {
            PostgresqlSearchProjector maintenance=new PostgresqlSearchProjector(source,analyzer);
            maintenance.refreshAnimals(100);maintenance.refreshShelters(100);
            SearchDocumentWriter documents=new SearchDocumentWriter(source,Optional.of(analyzer));
            AnimalItemWriter writer=new AnimalItemWriter(documents,animals);
            TransactionTemplate write=new TransactionTemplate(transaction.getTransactionManager());
            Long addedId=write.execute(status -> {
                Shelter shelter=shelters.findById(1L).orElseThrow();
                Animal added=Animal.builder().apmsNoticeNo("NEW-1").apmsDesertionNo("NEW-1").species(Species.DOG)
                        .noticeStartDate(today).noticeEndDate(today.plusDays(10))
                        .breed("믹스견").color("갈색").gender(Gender.MALE).neuterStatus(NeuterStatus.UNKNOWN)
                        .status(AnimalStatus.PROTECT).apiSource(ApiSource.APMS_ANIMAL).specialMark("귀가 접힘").shelter(shelter).build();
                // This lightweight Hibernate fixture does not start Spring Data auditing.
                org.springframework.test.util.ReflectionTestUtils.setField(added,"createdAt",today.atStartOfDay());
                org.springframework.test.util.ReflectionTestUtils.setField(added,"updatedAt",today.atStartOfDay());
                Animal changed=Animal.builder().id(1L).breed("말티즈").color("갈색").gender(Gender.MALE)
                        .noticeStartDate(today).noticeEndDate(today.plusDays(10))
                        .neuterStatus(NeuterStatus.NO).status(AnimalStatus.PROTECT).specialMark("귀가 접힘").shelter(shelter).build();
                try { writer.write(new Chunk<>(List.of(added,changed))); }
                catch(Exception failure) { throw new RuntimeException(failure); }
                return added.getId();
            });
            assertThat(jdbc.queryForObject("SELECT count(*) FROM animals WHERE search_dirty",Long.class)).isZero();
            assertThat(ids(analyzedQuery(analyzer).searchAnimals(AnimalSearchRequest.builder().keyword("귀 접힘").build(),relevance())))
                    .containsExactlyInAnyOrder(1L,addedId);
            assertThat(maintenance.refreshAnimals(100)).isZero();
            Long revision=jdbc.queryForObject("SELECT source_revision FROM animal_search_documents WHERE animal_id=1",Long.class);
            write.executeWithoutResult(status -> {
                Animal changed=animals.findById(1L).orElseThrow();changed.updateStatus(AnimalStatus.ADOPTED);
                try { writer.write(new Chunk<>(changed)); } catch(Exception failure) { throw new RuntimeException(failure); }
            });
            assertThat(jdbc.queryForObject("SELECT source_revision FROM animal_search_documents WHERE animal_id=1",Long.class)).isEqualTo(revision);
        }
    }

    @Test
    void manual_registration_publishes_animal_and_shelter_search_before_returning() throws Exception {
        try(KoreanSearchAnalyzer analyzer=new KoreanSearchAnalyzer()) {
            jdbc.queryForObject("SELECT setval(pg_get_serial_sequence('animals','id'),1000)",Long.class);
            jdbc.queryForObject("SELECT setval(pg_get_serial_sequence('shelters','id'),1000)",Long.class);
            PostgresqlSearchProjector maintenance=new PostgresqlSearchProjector(source,analyzer);
            maintenance.refreshAnimals(100);maintenance.refreshShelters(100);
            SearchDocumentWriter documents=new SearchDocumentWriter(source,Optional.of(analyzer));
            com.pawbridge.animalservice.service.ShelterCommandService shelterCommand=
                    new com.pawbridge.animalservice.service.ShelterCommandService(documents,shelters,
                            new com.pawbridge.animalservice.mapper.ShelterMapper());
            AnimalCommandService command=new AnimalCommandService(documents,animals,shelters,new AnimalMapper(),
                    new OutboxService(outbox,new ObjectMapper().findAndRegisterModules()),mock(NoticeNumberGenerator.class));
            TransactionTemplate write=new TransactionTemplate(transaction.getTransactionManager());
            write.executeWithoutResult(status -> shelterCommand.create(
                    com.pawbridge.animalservice.dto.request.CreateShelterRequest.builder()
                            .careRegNo("manual").name("하늘 보호소").address("서울 도봉구").build()));
            Long id=write.execute(status -> command.create(
                    com.pawbridge.animalservice.dto.request.CreateAnimalRequest.builder()
                            .careRegNo("manual").apmsNoticeNo("MANUAL-NEW").species(Species.DOG)
                            .gender(Gender.MALE).neuterStatus(NeuterStatus.NO).status(AnimalStatus.PROTECT)
                            .noticeStartDate(today).noticeEndDate(today.plusDays(10))
                            .breed("믹스견").color("노랑").build()).getId());
            assertThat(ids(analyzedQuery(analyzer).searchAnimals(
                    AnimalSearchRequest.builder().keyword("하늘 노랑").build(),relevance()))).containsExactly(id);
            assertThat(maintenance.refreshAnimals(100)).isZero();assertThat(maintenance.refreshShelters(100)).isZero();
        }
    }

    @Test
    void shelter_preparation_publishes_search_in_its_transaction_and_rolls_back_on_search_failure() throws Exception {
        try(KoreanSearchAnalyzer analyzer=new KoreanSearchAnalyzer()) {
            jdbc.queryForObject("SELECT setval(pg_get_serial_sequence('shelters','id'),1000)",Long.class);
            SearchDocumentWriter documents=new SearchDocumentWriter(source,Optional.of(analyzer));
            com.pawbridge.animalservice.batch.ApmsAnimalSnapshot snapshot=mock(com.pawbridge.animalservice.batch.ApmsAnimalSnapshot.class);
            org.mockito.Mockito.when(snapshot.animals()).thenReturn(List.of(
                    com.pawbridge.animalservice.dto.apms.ApmsAnimal.builder().careRegNo("batch-new")
                            .careNm("하늘 보호소").careAddr("서울 도봉구").build()));
            com.pawbridge.animalservice.batch.tasklet.ShelterPrepTasklet tasklet=
                    new com.pawbridge.animalservice.batch.tasklet.ShelterPrepTasklet(documents,snapshot,shelters);
            TransactionTemplate write=new TransactionTemplate(transaction.getTransactionManager());
            jdbc.execute("ALTER TABLE shelter_search_documents ADD CONSTRAINT simulated_new_shelter_failure CHECK(shelter_id<1000)");
            try {
                assertThatThrownBy(()->write.executeWithoutResult(status -> tasklet.execute(null,null)))
                        .isInstanceOf(org.springframework.dao.DataAccessException.class);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM shelters WHERE care_reg_no='batch-new'",Long.class)).isZero();
            } finally { jdbc.execute("ALTER TABLE shelter_search_documents DROP CONSTRAINT simulated_new_shelter_failure"); }
            write.executeWithoutResult(status -> tasklet.execute(null,null));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM shelters s JOIN shelter_search_documents d ON d.shelter_id=s.id "
                    +"WHERE s.care_reg_no='batch-new' AND NOT s.search_dirty AND d.source_revision=s.search_revision "
                    +"AND d.tokens @@ plainto_tsquery('simple','하늘')",Long.class)).isEqualTo(1);
        }
    }

    @Test
    void real_jpa_and_apms_bulk_updates_mark_search_pending_but_rollback_does_not() throws Exception {
        try (KoreanSearchAnalyzer analyzer=new KoreanSearchAnalyzer()) {
            PostgresqlSearchProjector projector=new PostgresqlSearchProjector(source,analyzer);
            projector.refreshShelters(100);projector.refreshAnimals(100);
            TransactionTemplate write=new TransactionTemplate(transaction.getTransactionManager());
            write.executeWithoutResult(status -> animals.findById(1L).orElseThrow().updateDescription("사람을 좋아함"));
            assertThat(jdbc.queryForObject("SELECT search_revision FROM animals WHERE id=1",Long.class)).isEqualTo(2);
            AnimalQueryService analyzed=analyzedQuery(analyzer);
            AnimalSearchRequest request=AnimalSearchRequest.builder().keyword("사람을 좋아").build();
            assertThatThrownBy(()->analyzed.searchAnimals(request,relevance())).isInstanceOf(SearchProjectionPendingException.class);
            assertThat(projector.refreshAnimals(100)).isEqualTo(1);
            assertThat(ids(analyzed.searchAnimals(request,relevance()))).containsExactly(1L);
            write.executeWithoutResult(status -> {
                animals.findById(1L).orElseThrow().updateDescription("롤백되는 특징");animals.flush();status.setRollbackOnly();
            });
            assertThat(projector.refreshAnimals(100)).isZero();
            write.executeWithoutResult(status -> {
                Animal animal=animals.findById(1L).orElseThrow();
                assertThat(animals.updateAnimalFromApms(animal.getId(),animal.getShelter(),animal.getBreed(),animal.getBirthYear(),
                        animal.getWeight(),"갈색",animal.getGender(),animal.getNeuterStatus(),"접힌귀",animal.getApmsProcessState(),
                        animal.getNoticeStartDate(),animal.getNoticeEndDate(),animal.getApmsUpdatedAt(),animal.getHappenDate(),
                        animal.getHappenPlace(),animal.getImageUrl(),animal.getImageUrl2(),animal.getStatus())).isEqualTo(1);
            });
            assertThat(jdbc.queryForObject("SELECT search_revision FROM animals WHERE id=1",Long.class)).isEqualTo(3);
            assertThatThrownBy(()->analyzed.searchAnimals(AnimalSearchRequest.builder().keyword("귀 접힘").build(),relevance()))
                    .isInstanceOf(SearchProjectionPendingException.class);
            assertThat(projector.refreshAnimals(100)).isEqualTo(1);
            assertThat(ids(analyzed.searchAnimals(AnimalSearchRequest.builder().keyword("귀 접힘").build(),relevance()))).containsExactly(1L);
        }
    }

    @Test
    void failed_projection_rolls_back_the_page_and_retries_without_losing_pending_rows() throws Exception {
        try (KoreanSearchAnalyzer analyzer=new KoreanSearchAnalyzer()) {
            PostgresqlSearchProjector projector=new PostgresqlSearchProjector(source,analyzer);
            jdbc.execute("ALTER TABLE animal_search_documents ADD CONSTRAINT simulated_write_failure CHECK(animal_id<>2)");
            try {
                assertThatThrownBy(()->projector.refreshAnimals(100)).isInstanceOf(org.springframework.dao.DataAccessException.class);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM animal_search_documents",Long.class)).isZero();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM animals WHERE search_dirty",Long.class)).isEqualTo(6);
            } finally { jdbc.execute("ALTER TABLE animal_search_documents DROP CONSTRAINT simulated_write_failure"); }
            assertThat(projector.refreshAnimals(100)).isEqualTo(6);
        }
    }

    @Test
    void concurrent_source_lock_is_skipped_and_latest_commit_is_projected_before_delete_cascades() throws Exception {
        try (KoreanSearchAnalyzer analyzer=new KoreanSearchAnalyzer()) {
            PostgresqlSearchProjector projector=new PostgresqlSearchProjector(source,analyzer);
            try(Connection connection=source.getConnection();Statement statement=connection.createStatement()) {
                connection.setAutoCommit(false);
                statement.executeUpdate("UPDATE animals SET special_mark='동시 변경된 접힌귀' WHERE id=1");
                assertThat(projector.refreshAnimals(100)).isEqualTo(5);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM animal_search_documents WHERE animal_id=1",Long.class)).isZero();
                connection.commit();
            }
            assertThat(projector.refreshAnimals(100)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT source_revision FROM animal_search_documents WHERE animal_id=1",Long.class)).isEqualTo(2);
            jdbc.update("DELETE FROM animals WHERE id=1");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM animal_search_documents WHERE animal_id=1",Long.class)).isZero();
            assertThat(projector.refreshAnimals(100)).isZero();
        }
    }

    @Test
    void shelter_pending_blocks_search_while_version_corruption_is_repaired_by_audit() throws Exception {
        try (KoreanSearchAnalyzer analyzer=new KoreanSearchAnalyzer()) {
            PostgresqlSearchProjector projector=new PostgresqlSearchProjector(source,analyzer);
            projector.refreshShelters(100);projector.refreshAnimals(100);
            AnimalQueryService analyzed=analyzedQuery(analyzer);
            jdbc.update("UPDATE shelters SET name='하늘 보호소' WHERE id=1");
            assertThat(projector.refreshAnimals(100)).isZero();
            AnimalSearchRequest request=AnimalSearchRequest.builder().keyword("하늘").region("서울").species(Species.DOG).build();
            assertThatThrownBy(()->analyzed.searchAnimals(request,relevance())).isInstanceOf(SearchProjectionPendingException.class);
            assertThat(projector.refreshShelters(100)).isEqualTo(1);
            assertThat(ids(analyzed.searchAnimals(request,relevance()))).containsExactlyInAnyOrder(1L,2L);
            jdbc.update("UPDATE animal_search_documents SET analyzer_version='old-version' WHERE animal_id=1");
            // A direct document mutation bypasses normal source writes; the audit owns detection.
            assertThat(ids(analyzed.searchAnimals(request,relevance()))).containsExactlyInAnyOrder(1L,2L);
            assertThat(projector.refreshDirtyAnimals(100)).isZero();
            assertThat(projector.refreshAnimals(100)).isEqualTo(1);
            assertThat(ids(analyzed.searchAnimals(request,relevance()))).containsExactlyInAnyOrder(1L,2L);
        }
    }

    @Test
    void changed_terms_replace_old_matches_and_keep_order_and_count_after_persistence() throws Exception {
        try(KoreanSearchAnalyzer analyzer=new KoreanSearchAnalyzer()) {
            PostgresqlSearchProjector projector=new PostgresqlSearchProjector(source,analyzer);
            projector.refreshShelters(100);projector.refreshAnimals(100);
            SearchDocumentWriter documents=new SearchDocumentWriter(source,Optional.of(analyzer));
            TransactionTemplate write=new TransactionTemplate(transaction.getTransactionManager());
            write.executeWithoutResult(status -> {
            jdbc.update("UPDATE animals SET color='노랑',special_mark='사람을 좋아함' WHERE id=1");
            jdbc.update("UPDATE shelters SET name='하늘 보호소' WHERE id=1");
            documents.animal(1);documents.shelter(1);
            });
            AnimalQueryService analyzed=analyzedQuery(analyzer);
            assertThat(ids(analyzed.searchAnimals(AnimalSearchRequest.builder().keyword("검정").build(),relevance()))).containsExactly(2L);
            AnimalSearchRequest combined=AnimalSearchRequest.builder().keyword("하늘 노랑").breed("말티즈").build();
            Page<AnimalResponse> before=analyzed.searchAnimals(combined,relevance());
            assertThat(ids(before)).containsExactly(1L);assertThat(before.getTotalElements()).isEqualTo(1);
            assertThat(ids(analyzed.searchAnimals(AnimalSearchRequest.builder().keyword("사람을 좋아").build(),relevance()))).containsExactly(1L);
            assertThat(jdbc.queryForObject("SELECT search_dirty FROM animals WHERE id=1",Boolean.class)).isFalse();
            assertThat(projector.refreshShelters(100)).isZero();assertThat(projector.refreshAnimals(100)).isZero();
            Page<AnimalResponse> after=analyzed.searchAnimals(combined,relevance());
            assertThat(ids(after)).isEqualTo(ids(before));assertThat(after.getTotalElements()).isEqualTo(before.getTotalElements());
        }
    }

    @Test
    void indexed_candidates_preserve_cross_document_terms_without_duplicates_or_partial_matches() throws Exception {
        jdbc.update("UPDATE shelters SET name='하늘 보호소' WHERE id=1");
        jdbc.update("UPDATE animals SET special_mark='하늘 검정',color='노랑' WHERE id=1");
        try(KoreanSearchAnalyzer analyzer=new KoreanSearchAnalyzer()) {
            PostgresqlSearchProjector projector=new PostgresqlSearchProjector(source,analyzer);
            projector.refreshAnimals(100);projector.refreshShelters(100);
            AnimalQueryService analyzed=analyzedQuery(analyzer);
            AnimalSearchRequest request=AnimalSearchRequest.builder().keyword("하늘 검정").build();
            PageRequest first=PageRequest.of(0,1,Sort.by("createdAt"));
            Page<AnimalResponse> page=analyzed.searchAnimals(request,first);
            assertThat(page.getTotalElements()).isEqualTo(2);assertThat(ids(page)).containsExactly(1L);
            assertThat(ids(analyzed.searchAnimals(request,first.next()))).containsExactly(2L);
            assertThat(ids(analyzed.searchAnimals(AnimalSearchRequest.builder().keyword("하늘 하늘 노랑").build(),relevance())))
                    .containsExactly(1L);
            assertThat(analyzed.searchAnimals(AnimalSearchRequest.builder().keyword("하늘 검정 없는단어").build(),relevance())).isEmpty();
            assertThat(analyzed.searchAnimals(AnimalSearchRequest.builder().keyword("하늘 검정").region("부산").build(),relevance())).isEmpty();
        }
    }

    @Test
    void initial_backfill_is_bounded_and_publishes_complete_counts_only_after_preparation() throws Exception {
        try(KoreanSearchAnalyzer analyzer=new KoreanSearchAnalyzer()) {
            PostgresqlSearchProjector projector=new PostgresqlSearchProjector(source,analyzer);
            projector.refreshAnimals(100);projector.refreshShelters(100);
            jdbc.execute("INSERT INTO animals(id,created_at,api_source,apms_notice_no,favorite_count,gender,neuter_status,species,status,shelter_id,breed,color,special_mark,notice_start_date,notice_end_date) "
                    +"SELECT n,NOW(),'APMS_ANIMAL','LIVE-'||n,0,'UNKNOWN','UNKNOWN','DOG','PROTECT',1,'믹스견','갈색','보완 검증',CURRENT_DATE,CURRENT_DATE+10 FROM generate_series(100,600) n");
            AnimalQueryService analyzed=analyzedQuery(analyzer);
            AnimalSearchRequest request=AnimalSearchRequest.builder().keyword("보완 검증").build();
            for(int page=0;page<5;page++) {
                assertThatThrownBy(()->analyzed.searchAnimals(request,relevance())).isInstanceOf(SearchProjectionPendingException.class);
                assertThat(projector.refreshAnimals(100)).isEqualTo(100);
            }
            assertThatThrownBy(()->analyzed.searchAnimals(request,relevance())).isInstanceOf(SearchProjectionPendingException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM animals WHERE search_dirty",Long.class)).isEqualTo(1);
            assertThat(projector.refreshAnimals(100)).isEqualTo(1);
            Page<AnimalResponse> last=analyzed.searchAnimals(request,PageRequest.of(25,20,Sort.by("createdAt")));
            assertThat(last.getTotalElements()).isEqualTo(501);assertThat(ids(last)).containsExactly(600L);
            assertThat(projector.refreshAnimals(100)).isZero();
        }
    }

    @Test
    void missing_or_outdated_documents_are_repaired_without_dirty_flags_and_other_regions_remain_searchable() throws Exception {
        try(KoreanSearchAnalyzer analyzer=new KoreanSearchAnalyzer()) {
            PostgresqlSearchProjector projector=new PostgresqlSearchProjector(source,analyzer);
            projector.refreshAnimals(100);projector.refreshShelters(100);
            jdbc.update("DELETE FROM animal_search_documents WHERE animal_id=1");
            jdbc.update("UPDATE animal_search_documents SET source_revision=0 WHERE animal_id=2");
            jdbc.update("UPDATE shelter_search_documents SET analyzer_version='old-version' WHERE shelter_id=1");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM animals WHERE search_dirty",Long.class)).isZero();
            AnimalQueryService analyzed=analyzedQuery(analyzer);
            // No request-time integrity scan: until audit, an out-of-band deletion can omit a match.
            assertThat(analyzed.searchAnimals(AnimalSearchRequest.builder().keyword("보호소").build(),relevance()).getTotalElements()).isEqualTo(3);
            assertThat(projector.refreshDirtyAnimals(100)).isZero();
            assertThat(projector.refreshDirtyShelters(100)).isZero();
            assertThat(ids(analyzed.searchAnimals(AnimalSearchRequest.builder().keyword("갈색").region("부산").build(),relevance())))
                    .containsExactly(5L);
            assertThat(projector.refreshAnimals(100)).isEqualTo(2);
            assertThat(projector.refreshShelters(100)).isEqualTo(1);
            assertThat(analyzed.searchAnimals(AnimalSearchRequest.builder().keyword("보호소").build(),relevance()).getTotalElements()).isEqualTo(4);
            assertThat(projector.refreshAnimals(100)).isZero();assertThat(projector.refreshShelters(100)).isZero();
        }
    }

    @Test
    void search_documents_and_source_fields_share_a_snapshot_across_an_independent_commit() throws Exception {
        try(KoreanSearchAnalyzer analyzer=new KoreanSearchAnalyzer()) {
            PostgresqlSearchProjector projector=new PostgresqlSearchProjector(source,analyzer);
            projector.refreshAnimals(100);projector.refreshShelters(100);
            SearchDocumentWriter documents=new SearchDocumentWriter(source,Optional.of(analyzer));
            AnimalQueryService analyzed=analyzedQuery(analyzer);
            TransactionTemplate snapshot=new TransactionTemplate(transaction.getTransactionManager());
            snapshot.setReadOnly(true);snapshot.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
            snapshot.executeWithoutResult(status->{
                AnimalSearchRequest black=AnimalSearchRequest.builder().keyword("검정").build();
                assertThat(ids(analyzed.searchAnimals(black,relevance()))).containsExactlyInAnyOrder(1L,2L);
                TransactionTemplate independent=new TransactionTemplate(transaction.getTransactionManager());
                independent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                independent.executeWithoutResult(write -> {
                    jdbc.update("UPDATE animals SET special_mark='노랑',color='노랑',status='ADOPTED' WHERE id=1");
                    documents.animal(1);
                });
                assertThat(ids(analyzed.searchAnimals(black,relevance()))).containsExactlyInAnyOrder(1L,2L);
            });
            assertThat(ids(analyzed.searchAnimals(AnimalSearchRequest.builder().keyword("검정").build(),relevance()))).containsExactly(2L);
            assertThat(source.getHikariPoolMXBean().getActiveConnections()).isZero();
        }
    }

    private AnimalQueryService analyzedQuery(KoreanSearchAnalyzer analyzer) {
        ProxyFactory proxy=new ProxyFactory(new PostgresqlAnimalQueryService(source,animals,new AnimalMapper(),java.util.Optional.of(analyzer)));
        TransactionInterceptor interceptor=new TransactionInterceptor();interceptor.setTransactionManager(transaction.getTransactionManager());
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());proxy.addAdvice(interceptor);
        return (AnimalQueryService)proxy.getProxy();
    }

    @Test
    void shelter_counts_include_closed_records_but_expiring_list_only_includes_protection() {
        assertThat(query.countBySpecies(Species.DOG)).isEqualTo(6);
        assertThat(query.countByStatus(AnimalStatus.ADOPTED)).isEqualTo(1);
        assertThat(query.countByShelterId(1L)).isEqualTo(3);
        assertThat(query.countBySpeciesAndStatus(Species.DOG,AnimalStatus.PROTECT)).isEqualTo(3);
        assertThat(ids(query.findByShelterId(1L,PageRequest.of(0,20)))).containsExactly(1L,2L,3L);
        assertThat(ids(query.findByShelterIdAndSpecies(1L,Species.CAT,PageRequest.of(0,20)))).isEmpty();
        assertThat(ids(query.findByShelterIdAndStatus(1L,AnimalStatus.ADOPTED,PageRequest.of(0,20)))).containsExactly(3L);
        jdbc.update("UPDATE animals SET notice_end_date=? WHERE id=5",today.minusDays(1));
        jdbc.update("UPDATE animals SET notice_end_date=? WHERE id=6",today.plusDays(4));
        assertThat(ids(query.findExpiringSoonAnimals(PageRequest.of(0,20)))).containsExactly(2L);
        assertThat(ids(query.searchAnimals(new AnimalSearchRequest(),PageRequest.of(0,20)))).contains(5L);
        assertThat(source.getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    @Test
    void existing_jpql_statistics_execute_on_postgresql_without_changing_date_meaning() {
        transaction.executeWithoutResult(status->{
            assertThat(stats.countRescuedToday(today)).isEqualTo(3);
            assertThat(stats.countAdoptedToday(today,AnimalStatus.ADOPTED)).isEqualTo(1);
            assertThat(stats.countByStatus(today.minusDays(29),today).stream().mapToLong(StatusStatsResponse::getCount).sum()).isEqualTo(5);
            assertThat(stats.countByShelterForRegional(today.minusDays(29),today)).hasSize(2);
            assertThat(adminStats.countDailyAnimals(today,today)).singleElement().satisfies(row->assertThat(row.getCount()).isEqualTo(6));
        });
        assertThat(source.getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    @Test
    void existing_http_route_uses_the_selected_query_backend_and_preserves_page_response() throws Exception {
        com.pawbridge.animalservice.facade.AnimalFacade facade=new com.pawbridge.animalservice.facade.AnimalFacade(
                org.mockito.Mockito.mock(com.pawbridge.animalservice.service.AnimalCommandService.class),query,animals,
                new AnimalMapper(),org.mockito.Mockito.mock(com.pawbridge.animalservice.service.AnimalRecommendationService.class));
        org.springframework.test.web.servlet.MockMvc mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new com.pawbridge.animalservice.controller.AnimalController(facade))
                .setControllerAdvice(new com.pawbridge.animalservice.exception.GlobalExceptionHandler())
                .setCustomArgumentResolvers(new org.springframework.data.web.PageableHandlerMethodArgumentResolver()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/animals")
                .param("keyword","푸들").param("sort","relevance,desc"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.content[0].id").value(5))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.totalElements").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.totalPages").value(1));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/animals").param("sort","unknown,asc"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        assertThat(source.getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    @Test
    void representative_volume_preserves_exact_totals_and_records_query_latency() {
        jdbc.execute("INSERT INTO animals(id,created_at,api_source,apms_desertion_no,apms_notice_no,favorite_count,gender,neuter_status,notice_start_date,notice_end_date,species,status,shelter_id,breed,color,special_mark) "
                +"SELECT n,CURRENT_TIMESTAMP,'APMS_ANIMAL','bulk-'||n,'BULK-'||n,0,'UNKNOWN','UNKNOWN',CURRENT_DATE,CURRENT_DATE+10,'DOG','PROTECT',1,'믹스견','갈색','보호 중' FROM generate_series(100,20099) n");
        jdbc.execute("ANALYZE animals");
        assertThat(query.searchAnimals(new AnimalSearchRequest(),PageRequest.of(0,20)).getTotalElements()).isEqualTo(20004);
        AnimalSearchRequest request=AnimalSearchRequest.builder().keyword("흰색 말티즈 오른쪽 귀 검정").build();
        for (int i=0;i<4;i++) {
            long started=System.nanoTime();
            Page<AnimalResponse> found=query.searchAnimals(request,relevance());
            long elapsed=(System.nanoTime()-started)/1_000_000;
            assertThat(ids(found)).containsExactly(1L);
            System.out.println("PG_TEXT_QUERY_SAMPLE rows=20006 iteration="+i+" elapsed_ms="+elapsed);
        }
        assertThat(source.getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    @AfterAll
    void close_resources() {
        if (factory!=null) factory.close();
        if (registry!=null) StandardServiceRegistryBuilder.destroy(registry);
        if (source!=null) source.close();
    }
}
