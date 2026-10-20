package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.command.infrastructure.service.AnimalCommandService;
import com.pawbridge.backend.store.api.StoreMemberReads;
import com.pawbridge.backend.store.api.StoreOrderActors;
import com.pawbridge.backend.store.internal.catalog.application.StoreCatalog;
import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms;
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders;
import com.pawbridge.backend.store.internal.directorder.web.DirectOrderController;
import com.pawbridge.backend.store.internal.directorder.web.DirectOrderExceptionHandler;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StoreDirectOrderConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = {"absent", "false", "yes", "on", "1", "unknown"})
    void given__orders_not_explicitly_true__when__assembling_without_database_inputs__then__create_no_order_auditing_or_writer_beans(String flag) {
        var runner = runner();
        if (!flag.equals("absent")) runner = runner.withPropertyValues("pawbridge.store-direct-orders.enabled=" + flag);
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(EntityManagerFactory.class)
                .doesNotHaveBean(DirectOrders.class).doesNotHaveBean(StoreOrderActors.class)
                .doesNotHaveBean(DirectOrderController.class).doesNotHaveBean(DirectOrderExceptionHandler.class)
                .doesNotHaveBean(KoreanSearchTerms.class).doesNotHaveBean(AuditingHandler.class)
                .doesNotHaveBean(StoreMemberReads.class).doesNotHaveBean(StoreCatalog.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"store", "animal", "both"})
    void given__selected_writer_features__when__assembling__then__share_one_auditing_registration_and_keep_store_on_its_single_writer_unit(String mode) {
        DataSource writer = mock(DataSource.class);
        DataSource reader = mock(DataSource.class);
        PlatformTransactionManager readerManager = mock(PlatformTransactionManager.class);
        AtomicReference<List<EntityManagerFactory>> units = new AtomicReference<>();
        AtomicReference<KoreanSearchTerms> terms = new AtomicReference<>();
        var runner = writerInputs(writer).withBean("dataSource", DataSource.class, () -> reader)
                .withBean("transactionManager", PlatformTransactionManager.class, () -> readerManager);
        if (!mode.equals("animal")) runner = runner.withPropertyValues("pawbridge.store-direct-orders.enabled=TrUe");
        if (!mode.equals("store")) runner = runner.withPropertyValues("pawbridge.animal-commands.enabled=true");
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(JpaAuditingConfiguration.class).hasSingleBean(AuditingHandler.class)
                    .doesNotHaveBean(StoreMemberReads.class).doesNotHaveBean(StoreCatalog.class);
            units.set(List.copyOf(context.getBeansOfType(EntityManagerFactory.class).values()));
            assertThat(units.get()).hasSize(mode.equals("both") ? 2 : 1);
            assertThat(context.containsBean("animalCommandService")).isEqualTo(!mode.equals("store"));
            if (mode.equals("animal")) {
                assertThat(context).doesNotHaveBean(DirectOrders.class).doesNotHaveBean(KoreanSearchTerms.class);
            } else {
                var emf = context.getBean("storeDirectOrderEntityManagerFactory", EntityManagerFactory.class);
                var factory = context.getBean("&storeDirectOrderEntityManagerFactory", LocalContainerEntityManagerFactoryBean.class);
                assertThat(factory.getDataSource()).isSameAs(writer);
                String model = "com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.";
                assertThat(factory.getPersistenceUnitInfo().getManagedClassNames()).containsExactlyInAnyOrder(
                        model + "BaseEntity", model + "Wishlist", model + "ProductSKU", model + "Product",
                        model + "Category", model + "SKUValue", model + "OptionValue", model + "OptionGroup",
                        model + "Order", model + "OrderItem",
                        "com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.model.Outbox");
                assertThat(factory.getJpaPropertyMap()).containsEntry("hibernate.boot.allow_jdbc_metadata_access", false)
                        .containsEntry("hibernate.hbm2ddl.auto", "none")
                        .containsEntry("jakarta.persistence.schema-generation.database.action", "none")
                        .containsEntry("hibernate.default_schema", "pawbridge_store")
                        .containsEntry("hibernate.jdbc.time_zone", "UTC")
                        .containsEntry("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
                var manager = context.getBean("storeDirectOrderJpaTransactionManager", JpaTransactionManager.class);
                assertThat(manager.getEntityManagerFactory()).isSameAs(emf);
                assertThat(manager.getDataSource()).isSameAs(writer);
                assertThat(manager.getDefaultTimeout()).isEqualTo(15);
                var orders = context.getBean(DirectOrders.class);
                var writes = context.getBean("storeDirectOrderWrites", TransactionTemplate.class);
                var reads = context.getBean("storeDirectOrderReads", TransactionTemplate.class);
                assertThat(ReflectionTestUtils.getField(orders, "writes")).isSameAs(writes);
                assertThat(ReflectionTestUtils.getField(orders, "reads")).isSameAs(reads);
                for (TransactionTemplate transaction : List.of(writes, reads)) {
                    assertThat(transaction.getTransactionManager()).isSameAs(manager).isNotSameAs(readerManager);
                    assertThat(transaction.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
                    assertThat(transaction.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT);
                    assertThat(transaction.getTimeout()).isEqualTo(TransactionDefinition.TIMEOUT_DEFAULT);
                }
                assertThat(writes.isReadOnly()).isFalse();
                assertThat(reads.isReadOnly()).isTrue();
                EntityManager shared = context.getBean("storeDirectOrderEntityManager", EntityManager.class);
                assertThat(shared.getEntityManagerFactory()).isSameAs(emf);
                for (String repository : List.of("writerOrders", "readerOrders")) {
                    Object proxy = ReflectionTestUtils.getField(orders, repository);
                    Object target = AopTestUtils.getUltimateTargetObject(proxy);
                    assertThat(ReflectionTestUtils.getField(target, "entityManager")).isSameAs(shared);
                }
                var actorJdbc = (JdbcTemplate) ReflectionTestUtils.getField(context.getBean(StoreOrderActors.class), "jdbc");
                assertThat(actorJdbc.getDataSource()).isSameAs(writer);
                var search = context.getBean("storeDirectOrderSearchJdbc", NamedParameterJdbcTemplate.class);
                assertThat(search.getJdbcTemplate().getDataSource()).isSameAs(writer);
                assertThat(search.getJdbcTemplate().getQueryTimeout()).isEqualTo(5);
                terms.set(context.getBean(KoreanSearchTerms.class));
                assertThat(terms.get().document("말티즈")).isNotBlank();
                assertThat(context).hasSingleBean(DirectOrderController.class).hasSingleBean(DirectOrderExceptionHandler.class);
            }
            if (mode.equals("store")) assertThat(context).doesNotHaveBean(AnimalCommandService.class);
            verifyNoInteractions(writer, reader, readerManager);
        });
        assertThat(units.get()).allSatisfy(factory -> assertThat(factory.isOpen()).isFalse());
        if (terms.get() != null) assertThatThrownBy(() -> terms.get().document("말티즈")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void given__user_actor_jdbc_uses_another_source__when__orders_enabled__then__reject_the_mismatched_writer_without_database_access() {
        DataSource writer = mock(DataSource.class);
        DataSource other = mock(DataSource.class);
        runner().withBean("writeDataSource", DataSource.class, () -> writer)
                .withBean("writeJdbc", JdbcTemplate.class, () -> new JdbcTemplate(other))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("pawbridge.store-direct-orders.enabled=true").run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage("Store order actors must use the same writer DataSource");
                    verifyNoInteractions(writer, other);
                });
    }

    @Test
    void given__only_reader_inputs__when__orders_enabled__then__fail_instead_of_using_a_reader_for_orders() {
        DataSource reader = mock(DataSource.class);
        runner().withBean("dataSource", DataSource.class, () -> reader)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("pawbridge.store-direct-orders.enabled=true").run(context -> {
                    assertThat(context).hasFailed();
                    verifyNoInteractions(reader);
                });
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(StoreDirectOrderConfiguration.class, AnimalCommandConfiguration.class,
                        StoreMemberJpaConfiguration.class, StoreCatalogConfiguration.class);
    }

    private static ApplicationContextRunner writerInputs(DataSource writer) {
        return runner().withBean("writeDataSource", DataSource.class, () -> writer)
                .withBean("writeJdbc", JdbcTemplate.class, () -> new JdbcTemplate(writer))
                .withBean(ObjectMapper.class, ObjectMapper::new);
    }
}
