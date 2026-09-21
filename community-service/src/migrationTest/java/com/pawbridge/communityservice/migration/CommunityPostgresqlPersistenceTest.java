package com.pawbridge.communityservice.migration;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Map;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import static org.assertj.core.api.Assertions.*;
import com.pawbridge.communityservice.domain.entity.*;
import com.pawbridge.communityservice.domain.repository.PostRepository;

@Tag("postgresql")
class CommunityPostgresqlPersistenceTest {
    private HikariDataSource source;
    private SessionFactory factory;
    private StandardServiceRegistry registry;
    private JdbcTemplate jdbc;
    private static final LocalDateTime NOW = LocalDateTime.of(2026,9,20,12,0);

    @BeforeEach
    void prepare_guarded_database_and_validate_all_entities() throws Exception {
        String port = System.getenv("COMMUNITY_PG_MIGRATION_TEST_PORT");
        if (port == null || !port.matches("[0-9]{1,5}")) throw new IllegalStateException("Missing disposable test port");
        String url = "jdbc:postgresql://127.0.0.1:" + port + "/pawbridge";
        Map<String,String> environment = CommunityPostgresqlMigrationTest.environment(url);
        environment.put("COMMUNITY_PG_MIGRATION_CONFIRM_TARGET",url);
        CommunityPostgresqlMigration.Settings settings = CommunityPostgresqlMigration.Settings.from(environment);
        try (Connection connection = DriverManager.getConnection(url,settings.username(),settings.password());
                Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery("SELECT marker FROM migration_test_guard.guard")) {
                if (!rows.next() || !"services-pg-disposable".equals(rows.getString(1)) || rows.next())
                    throw new IllegalStateException("Missing disposable database guard");
            }
            statement.execute("DROP SCHEMA IF EXISTS pawbridge_community CASCADE");
            statement.execute("CREATE SCHEMA pawbridge_community");
        }
        CommunityPostgresqlMigration.execute("migrate",settings);
        // Bind the real opt-in YAML, so misspelled environment keys or driver/schema settings fail here.
        org.springframework.core.env.StandardEnvironment environmentProperties = new org.springframework.core.env.StandardEnvironment();
        environmentProperties.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("test-target",Map.of(
                "COMMUNITY_POSTGRESQL_JDBC_URL",url,"COMMUNITY_POSTGRESQL_USERNAME",settings.username(),
                "COMMUNITY_POSTGRESQL_PASSWORD",settings.password(),"COMMUNITY_POSTGRESQL_POOL_MAX","2")));
        for (org.springframework.core.env.PropertySource<?> propertySource : new org.springframework.boot.env.YamlPropertySourceLoader()
                .load("postgresql",new org.springframework.core.io.ClassPathResource("application-postgresql.yml"))) {
            environmentProperties.getPropertySources().addLast(propertySource);
        }
        org.springframework.boot.context.properties.bind.Binder binder = org.springframework.boot.context.properties.bind.Binder.get(environmentProperties);
        org.springframework.boot.autoconfigure.jdbc.DataSourceProperties properties = binder.bind("spring.datasource",
                org.springframework.boot.context.properties.bind.Bindable.of(org.springframework.boot.autoconfigure.jdbc.DataSourceProperties.class)).get();
        source = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        binder.bind("spring.datasource.hikari",org.springframework.boot.context.properties.bind.Bindable.ofInstance(source));
        jdbc = new JdbcTemplate(source);
        registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.datasource",source)
                .applySetting("hibernate.default_schema","pawbridge_community")
                .applySetting("hibernate.hbm2ddl.auto","validate")
                .applySetting("hibernate.physical_naming_strategy","org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                .build();
        MetadataSources metadata = new MetadataSources(registry);
        for (Class<?> entity : java.util.List.of(Post.class,Comment.class,OutboxEvent.class,ProcessedEvent.class)) metadata.addAnnotatedClass(entity);
        factory = metadata.buildMetadata().buildSessionFactory();
    }

    @AfterEach
    void close_resources() {
        if (factory != null) factory.close();
        if (registry != null) StandardServiceRegistryBuilder.destroy(registry);
        if (source != null) source.close();
    }

    private void commit(java.util.function.Consumer<Session> work) {
        try (Session session = factory.openSession()) {
            Transaction transaction = session.beginTransaction();
            try { work.accept(session); transaction.commit(); }
            catch (RuntimeException failure) { if (transaction.isActive()) transaction.rollback(); throw failure; }
        }
        assertThat(source.getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    @Test
    void image_list_is_json_array_and_post_outbox_commit_together() throws Exception {
        Post post = post();
        OutboxEvent event = event("event-1","{\"postId\":1,\"title\":\"찾습니다\"}");
        commit(session -> { session.persist(post); session.persist(event); });
        assertThat(jdbc.queryForObject("SELECT json_typeof(image_urls) FROM posts",String.class)).isEqualTo("array");
        assertThat(jdbc.queryForObject("SELECT image_urls->>0 FROM posts",String.class)).isEqualTo("https://example.test/강아지.jpg");
        assertThat(jdbc.queryForObject("SELECT json_typeof(payload) FROM outbox_events",String.class)).isEqualTo("object");
        try (Session session = factory.openSession()) {
            assertThat(session.find(Post.class,post.getPostId()).getImageUrls()).containsExactly("https://example.test/강아지.jpg","https://example.test/cat.jpg");
            assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(session.find(OutboxEvent.class,event.getOutboxId()).getPayload()).path("title").asText()).isEqualTo("찾습니다");
        }
        commit(session -> session.find(Post.class,post.getPostId()).update(null,null,java.util.List.of()));
        assertThat(jdbc.queryForObject("SELECT json_array_length(image_urls) FROM posts",Integer.class)).isZero();
    }

    @Test
    void failed_event_write_rolls_back_post_and_successful_soft_delete_is_hidden() {
        assertThatThrownBy(() -> commit(session -> {
            session.persist(post());session.persist(event("bad-event","bad-json"));
        })).isInstanceOf(org.hibernate.exception.DataException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM posts",Integer.class)).isZero();
        Post post=post();commit(session -> session.persist(post));
        commit(session -> session.find(Post.class,post.getPostId()).delete());
        try (Session session = factory.openSession()) {
            PostRepository repository = new JpaRepositoryFactory(session).getRepository(PostRepository.class);
            assertThat(repository.findByPostIdAndDeletedAtIsNull(post.getPostId())).isEmpty();
            assertThat(repository.findByDeletedAtIsNull(org.springframework.data.domain.PageRequest.of(0,10))).isEmpty();
        }
    }

    private Post post() {
        return Post.builder().authorId(7L).title("실종 강아지").content("서울에서 찾습니다").boardType(BoardType.MISSING)
                .imageUrls(java.util.List.of("https://example.test/강아지.jpg","https://example.test/cat.jpg"))
                .createdAt(NOW).updatedAt(NOW).build();
    }
    private OutboxEvent event(String id,String payload) {
        return OutboxEvent.builder().eventId(id).aggregateType("Post").aggregateId("1")
                .type("POST_CREATED").payload(payload).createdAt(NOW).build();
    }

    @Test
    void korean_search_recovers_missing_documents_and_returns_connections_before_nickname_lookup() {
        Post post=Post.builder().authorId(7L).title("지산이를 찾습니다").content("서울에서 보호 중")
                .boardType(BoardType.MISSING).createdAt(NOW).updatedAt(NOW).build();
        commit(session -> session.persist(post));
        com.pawbridge.communityservice.search.KoreanSearchTerms terms=new com.pawbridge.communityservice.search.KoreanSearchTerms();
        try {
            com.pawbridge.communityservice.search.PostgresqlSearchDocuments documents=new com.pawbridge.communityservice.search.PostgresqlSearchDocuments(source,terms);
            com.pawbridge.communityservice.client.UserServiceClient users=org.mockito.Mockito.mock(com.pawbridge.communityservice.client.UserServiceClient.class);
            org.mockito.Mockito.when(users.getUserNickname(7L)).thenAnswer(invocation -> {
                assertThat(source.getHikariPoolMXBean().getActiveConnections()).isZero();return "보호자";
            });
            com.pawbridge.communityservice.service.PostgresqlSearchService search=new com.pawbridge.communityservice.service.PostgresqlSearchService(source,terms,users,new com.fasterxml.jackson.databind.ObjectMapper());
            assertThatThrownBy(() -> search.searchPosts("지산")).isInstanceOf(com.pawbridge.communityservice.exception.SearchServiceUnavailableException.class);
            org.springframework.transaction.support.TransactionTemplate tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
            Integer rebuilt = tx.execute(status -> documents.rebuildPage(100));
            assertThat(rebuilt).isEqualTo(1);
            assertThat(search.searchPosts("지산이를")).extracting(com.pawbridge.communityservice.dto.response.PostResponse::postId).containsExactly(post.getPostId());
            assertThat(search.searchPosts("지산")).extracting(com.pawbridge.communityservice.dto.response.PostResponse::postId).containsExactly(post.getPostId());
            assertThat(search.searchPosts("없는친구")).isEmpty();
            jdbc.update("UPDATE posts SET title='새 이름',content='부산' WHERE post_id=?",post.getPostId());
            assertThatThrownBy(() -> search.searchPosts("지산")).isInstanceOf(com.pawbridge.communityservice.exception.SearchServiceUnavailableException.class);
            tx.executeWithoutResult(status -> documents.rebuildPage(100));
            assertThat(search.searchPosts("지산")).isEmpty();
            assertThat(search.searchPosts("부산")).hasSize(1);
            jdbc.update("UPDATE posts SET deleted_at=CURRENT_TIMESTAMP WHERE post_id=?",post.getPostId());
            assertThat(search.searchPosts("부산")).isEmpty();
        } finally { terms.close(); }
    }

    @Test
    void actual_post_outbox_path_stores_terms_and_projection_failure_rolls_back_source_and_event() {
        Post post=post();commit(session -> session.persist(post));
        com.pawbridge.communityservice.search.KoreanSearchTerms terms=new com.pawbridge.communityservice.search.KoreanSearchTerms();
        try {
            com.pawbridge.communityservice.search.PostgresqlSearchDocuments documents=new com.pawbridge.communityservice.search.PostgresqlSearchDocuments(source,terms);
            org.springframework.orm.jpa.JpaTransactionManager manager=new org.springframework.orm.jpa.JpaTransactionManager(factory);
            manager.setDataSource(source);manager.setJpaDialect(new org.springframework.orm.jpa.vendor.HibernateJpaDialect());manager.afterPropertiesSet();
            jakarta.persistence.EntityManager em=org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(factory);
            com.pawbridge.communityservice.domain.repository.OutboxEventRepository events=new JpaRepositoryFactory(em).getRepository(com.pawbridge.communityservice.domain.repository.OutboxEventRepository.class);
            com.pawbridge.communityservice.service.OutboxServiceImpl outbox=new com.pawbridge.communityservice.service.OutboxServiceImpl(events,new com.fasterxml.jackson.databind.ObjectMapper(),java.util.Optional.of(documents));
            org.springframework.transaction.support.TransactionTemplate tx=new org.springframework.transaction.support.TransactionTemplate(manager);
            tx.executeWithoutResult(status -> outbox.saveEvent("Post",post.getPostId().toString(),"POST_UPDATED",Map.of("title",post.getTitle(),"content",post.getContent())));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM post_search_documents",Integer.class)).isEqualTo(1);
            jdbc.execute("ALTER TABLE post_search_documents ADD CONSTRAINT reject_terms CHECK(false) NOT VALID");
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                em.find(Post.class,post.getPostId()).update("변경 실패",null,null);
                outbox.saveEvent("Post",post.getPostId().toString(),"POST_UPDATED",Map.of("title","변경 실패","content",post.getContent()));
            })).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(jdbc.queryForObject("SELECT title FROM posts",String.class)).isEqualTo(post.getTitle());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events",Integer.class)).isEqualTo(1);
            assertThat(source.getHikariPoolMXBean().getActiveConnections()).isZero();
        } finally { terms.close(); }
    }

}
