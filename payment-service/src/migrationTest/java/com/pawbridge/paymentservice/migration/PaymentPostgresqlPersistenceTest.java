package com.pawbridge.paymentservice.migration;

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
import com.pawbridge.paymentservice.domain.payment.entity.*;
import com.pawbridge.paymentservice.domain.payment.repository.PaymentRepository;
import com.pawbridge.paymentservice.common.entity.Outbox;

@Tag("postgresql")
class PaymentPostgresqlPersistenceTest {
    private HikariDataSource source;
    private SessionFactory factory;
    private StandardServiceRegistry registry;
    private JdbcTemplate jdbc;
    private static final LocalDateTime NOW = LocalDateTime.of(2026,9,20,12,0);

    @BeforeEach
    void prepare_guarded_database_and_validate_all_entities() throws Exception {
        String port = System.getenv("PAYMENT_PG_MIGRATION_TEST_PORT");
        if (port == null || !port.matches("[0-9]{1,5}")) throw new IllegalStateException("Missing disposable test port");
        String url = "jdbc:postgresql://127.0.0.1:" + port + "/pawbridge";
        Map<String,String> environment = PaymentPostgresqlMigrationTest.environment(url);
        environment.put("PAYMENT_PG_MIGRATION_CONFIRM_TARGET",url);
        PaymentPostgresqlMigration.Settings settings = PaymentPostgresqlMigration.Settings.from(environment);
        try (Connection connection = DriverManager.getConnection(url,settings.username(),settings.password());
                Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery("SELECT marker FROM migration_test_guard.guard")) {
                if (!rows.next() || !"services-pg-disposable".equals(rows.getString(1)) || rows.next())
                    throw new IllegalStateException("Missing disposable database guard");
            }
            statement.execute("DROP SCHEMA IF EXISTS pawbridge_payment CASCADE");
            statement.execute("CREATE SCHEMA pawbridge_payment");
        }
        PaymentPostgresqlMigration.execute("migrate",settings);
        // Bind the real opt-in YAML, so misspelled environment keys or driver/schema settings fail here.
        org.springframework.core.env.StandardEnvironment environmentProperties = new org.springframework.core.env.StandardEnvironment();
        environmentProperties.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("test-target",Map.of(
                "PAYMENT_POSTGRESQL_JDBC_URL",url,"PAYMENT_POSTGRESQL_USERNAME",settings.username(),
                "PAYMENT_POSTGRESQL_PASSWORD",settings.password(),"PAYMENT_POSTGRESQL_POOL_MAX","2")));
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
                .applySetting("hibernate.default_schema","pawbridge_payment")
                .applySetting("hibernate.hbm2ddl.auto","validate")
                .applySetting("hibernate.physical_naming_strategy","org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                .build();
        MetadataSources metadata = new MetadataSources(registry);
        for (Class<?> entity : java.util.List.of(Payment.class,Outbox.class)) metadata.addAnnotatedClass(entity);
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
    void payment_and_outbox_preserve_numeric_json_and_status_in_one_transaction() throws Exception {
        Payment payment = payment("payment-test");
        String payload = "{\"orderId\":\"order-1\",\"amount\":12345,\"message\":\"승인\"}";
        Outbox outbox = Outbox.builder().aggregateType("PAYMENT").aggregateId("payment-test")
                .eventType("PaymentCompleted").payload(payload).build();
        commit(session -> { session.persist(payment); payment.approve(NOW); session.persist(outbox); });
        assertThat(payment.getId()).isPositive();
        assertThat(jdbc.queryForObject("SELECT json_typeof(payload->'amount') FROM outbox",String.class)).isEqualTo("number");
        try (Session session = factory.openSession()) {
            PaymentRepository repository = new JpaRepositoryFactory(session).getRepository(PaymentRepository.class);
            assertThat(repository.findByPaymentKey("payment-test").orElseThrow().getStatus()).isEqualTo(PaymentStatus.DONE);
            assertThat(repository.findByOrderId("order-1").orElseThrow().getApprovedAt()).isEqualTo(NOW);
            assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(session.find(Outbox.class,outbox.getId()).getPayload()))
                    .isEqualTo(new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload));
        }
        commit(session -> session.find(Payment.class,payment.getId()).cancel());
        assertThat(jdbc.queryForObject("SELECT status FROM payments",String.class)).isEqualTo("CANCELED");
    }

    @Test
    void failed_outbox_insert_rolls_back_payment_approval() {
        Payment payment=payment("payment-test");
        commit(session -> session.persist(payment));
        assertThatThrownBy(() -> commit(session -> {
            session.find(Payment.class,payment.getId()).approve(NOW);
            session.flush();
            session.persist(Outbox.builder().aggregateType("PAYMENT").aggregateId("payment-test")
                    .eventType("PaymentCompleted").payload("invalid-json").build());
        })).isInstanceOf(org.hibernate.exception.DataException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM payments",String.class)).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox",Integer.class)).isZero();
        assertThat(source.getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    @Test
    void payment_key_uniqueness_prevents_duplicate_payment_records() {
        commit(session -> session.persist(payment("duplicate-key")));
        assertThatThrownBy(() -> commit(session -> session.persist(payment("duplicate-key"))))
                .isInstanceOf(org.hibernate.exception.ConstraintViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments",Integer.class)).isEqualTo(1);
    }

    private Payment payment(String key) {
        return Payment.builder().paymentKey(key).orderId("order-1").userId(7L).amount(12345L)
                .method("카드").requestedAt(NOW).build();
    }

}
