package com.pawbridge.userservice.migration;

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
import com.pawbridge.userservice.entity.*;
import com.pawbridge.userservice.repository.UserRepository;
import com.pawbridge.userservice.shelter.ShelterApplication;

@Tag("postgresql")
class UserPostgresqlPersistenceTest {
    private HikariDataSource source;
    private SessionFactory factory;
    private StandardServiceRegistry registry;
    private JdbcTemplate jdbc;
    private static final LocalDateTime NOW = LocalDateTime.of(2026,9,20,12,0);

    @BeforeEach
    void prepare_guarded_database_and_validate_all_entities() throws Exception {
        String port = System.getenv("USER_PG_MIGRATION_TEST_PORT");
        if (port == null || !port.matches("[0-9]{1,5}")) throw new IllegalStateException("Missing disposable test port");
        String url = "jdbc:postgresql://127.0.0.1:" + port + "/pawbridge";
        Map<String,String> environment = UserPostgresqlMigrationTest.environment(url);
        environment.put("USER_PG_MIGRATION_CONFIRM_TARGET",url);
        UserPostgresqlMigration.Settings settings = UserPostgresqlMigration.Settings.from(environment);
        try (Connection connection = DriverManager.getConnection(url,settings.username(),settings.password());
                Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery("SELECT marker FROM migration_test_guard.guard")) {
                if (!rows.next() || !"services-pg-disposable".equals(rows.getString(1)) || rows.next())
                    throw new IllegalStateException("Missing disposable database guard");
            }
            statement.execute("DROP SCHEMA IF EXISTS pawbridge_user CASCADE");
            statement.execute("CREATE SCHEMA pawbridge_user");
        }
        UserPostgresqlMigration.execute("migrate",settings);
        // Bind the real opt-in YAML, so misspelled environment keys or driver/schema settings fail here.
        org.springframework.core.env.StandardEnvironment environmentProperties = new org.springframework.core.env.StandardEnvironment();
        environmentProperties.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("test-target",Map.of(
                "USER_POSTGRESQL_JDBC_URL",url,"USER_POSTGRESQL_USERNAME",settings.username(),
                "USER_POSTGRESQL_PASSWORD",settings.password(),"USER_POSTGRESQL_POOL_MAX","2")));
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
                .applySetting("hibernate.default_schema","pawbridge_user")
                .applySetting("hibernate.hbm2ddl.auto","validate")
                .applySetting("hibernate.physical_naming_strategy","org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                .build();
        MetadataSources metadata = new MetadataSources(registry);
        for (Class<?> entity : java.util.List.of(User.class,Favorite.class,OutboxEvent.class,ProcessedEvent.class,RefreshToken.class,ShelterApplication.class)) metadata.addAnnotatedClass(entity);
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
    void user_and_json_outbox_commit_and_rollback_together() throws Exception {
        User user = user("Owner@example.test","Owner");
        String payload = "{\"userId\":1,\"nickname\":\"보호자\",\"tags\":[\"개\",\"고양이\"]}";
        OutboxEvent outbox = event("event-1",payload);
        commit(session -> { session.persist(user); session.persist(outbox); });
        assertThat(user.getUserId()).isPositive();
        assertThat(jdbc.queryForObject("SELECT json_typeof(payload) FROM outbox_events",String.class)).isEqualTo("object");
        assertThat(jdbc.queryForObject("SELECT payload->>'nickname' FROM outbox_events",String.class)).isEqualTo("보호자");
        try (Session session = factory.openSession()) {
            assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(session.find(OutboxEvent.class,outbox.getOutboxEventId()).getPayload()))
                    .isEqualTo(new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload));
            Transaction tx = session.beginTransaction();
            session.persist(user("rollback@example.test","rollback"));
            session.persist(event("event-rollback",payload));
            session.flush();tx.rollback();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events",Integer.class)).isEqualTo(1);
        assertThat(source.getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    @Test
    void account_lookup_keeps_case_insensitivity_and_admin_optional_filters_work() {
        User owner = user("Owner@example.test","Owner");
        commit(session -> session.persist(owner));
        try (Session session = factory.openSession()) {
            UserRepository repository = new JpaRepositoryFactory(session).getRepository(UserRepository.class);
            assertThat(repository.findByEmailAndProvider("owner@EXAMPLE.test","local")).isPresent();
            assertThat(repository.existsByEmailAndProvider("OWNER@example.test","LOCAL")).isTrue();
            assertThat(repository.findByEmail("owner@example.test")).isPresent();
            assertThat(repository.findByNickname("owner")).isPresent();
            assertThat(repository.existsByNickname("OWNER")).isTrue();
            assertThat(repository.searchUsers(null,null,org.springframework.data.domain.PageRequest.of(0,10))).hasSize(1);
            assertThat(repository.searchUsers("own",Role.ROLE_ADMIN,org.springframework.data.domain.PageRequest.of(0,10))).isEmpty();
            assertThat(repository.countDailySignups(NOW.toLocalDate(),NOW.toLocalDate())).hasSize(1);
        }
        assertThatThrownBy(() -> commit(session -> session.persist(user("owner@example.test","different"))))
                .isInstanceOf(org.hibernate.exception.ConstraintViolationException.class);
        assertThatThrownBy(() -> commit(session -> session.persist(user("other@example.test","owner"))))
                .isInstanceOf(org.hibernate.exception.ConstraintViolationException.class);
    }

    @Test
    void pending_shelter_application_is_unique_but_rejected_user_can_request_again() {
        ShelterApplication first = ShelterApplication.request(7L,"보호소");
        commit(session -> session.persist(first));
        assertThatThrownBy(() -> commit(session -> session.persist(ShelterApplication.request(7L,"중복"))))
                .isInstanceOf(org.hibernate.exception.ConstraintViolationException.class);
        commit(session -> session.find(ShelterApplication.class,first.getId()).reject(1L,"재신청 안내"));
        commit(session -> session.persist(ShelterApplication.request(7L,"새 보호소")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter_applications WHERE status='PENDING'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter_applications",Integer.class)).isEqualTo(2);
    }

    @Test
    void favorite_unique_and_user_delete_restriction_survive_migration() {
        User owner=user("owner@example.test","owner");
        commit(session -> session.persist(owner));
        commit(session -> session.persist(Favorite.builder().user(session.find(User.class,owner.getUserId())).animalId(42L).createdAt(NOW).build()));
        assertThatThrownBy(() -> commit(session -> session.persist(Favorite.builder().user(session.find(User.class,owner.getUserId())).animalId(42L).createdAt(NOW).build())))
                .isInstanceOf(org.hibernate.exception.ConstraintViolationException.class);
        assertThatThrownBy(() -> commit(session -> session.remove(session.find(User.class,owner.getUserId()))))
                .isInstanceOf(org.hibernate.exception.ConstraintViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users",Integer.class)).isEqualTo(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void real_outbox_service_joins_business_transaction(boolean rollback) {
        jakarta.persistence.EntityManager shared = org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(factory);
        org.springframework.orm.jpa.JpaTransactionManager manager = new org.springframework.orm.jpa.JpaTransactionManager(factory);
        com.pawbridge.userservice.repository.OutboxEventRepository repository = org.mockito.Mockito.mock(
                com.pawbridge.userservice.repository.OutboxEventRepository.class);
        org.mockito.Mockito.when(repository.save(org.mockito.ArgumentMatchers.any(OutboxEvent.class))).thenAnswer(invocation -> {
            OutboxEvent event = invocation.getArgument(0);
            // This isolated SessionFactory has no Spring auditing context; supply only its timestamp.
            org.springframework.test.util.ReflectionTestUtils.setField(event, "createdAt", NOW);
            shared.persist(event);
            return event;
        });
        com.pawbridge.userservice.service.OutboxServiceImpl target = new com.pawbridge.userservice.service.OutboxServiceImpl(
                repository, new com.fasterxml.jackson.databind.ObjectMapper());
        org.springframework.aop.framework.ProxyFactory proxy = new org.springframework.aop.framework.ProxyFactory(target);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        com.pawbridge.userservice.service.OutboxService service = (com.pawbridge.userservice.service.OutboxService) proxy.getProxy();
        org.springframework.transaction.support.TransactionTemplate transaction = new org.springframework.transaction.support.TransactionTemplate(manager);
        transaction.executeWithoutResult(status -> {
            shared.persist(user("transaction@example.test", "transaction"));
            service.saveEvent("Favorite", "7", "FAVORITE_ADDED", "user.favorite.events",
                    Map.of("eventId", "favorite-transaction", "animalId", 42, "userId", 7));
            if (rollback) status.setRollbackOnly();
        });
        int expected = rollback ? 0 : 1;
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isEqualTo(expected);
        if (!rollback) {
            assertThat(jdbc.queryForObject("SELECT json_typeof(payload) FROM outbox_events", String.class)).isEqualTo("object");
        }
        assertThat(source.getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    private User user(String email,String nickname) {
        return User.builder().email(email).nickname(nickname).name("보호자").role(Role.ROLE_USER)
                .createdAt(NOW).updatedAt(NOW).build();
    }
    private OutboxEvent event(String id,String payload) {
        return OutboxEvent.builder().eventId(id).aggregateType("USER").aggregateId("1")
                .eventType("UserCreated").topic("user-events").payload(payload).createdAt(NOW).build();
    }

}
