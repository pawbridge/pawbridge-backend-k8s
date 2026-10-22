package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.store.api.StoreMemberReads;
import com.pawbridge.backend.store.api.StoreOrderActors;
import com.pawbridge.backend.store.internal.catalog.application.StoreCatalog;
import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms;
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders;
import com.pawbridge.backend.store.internal.directorder.application.CartOrders;
import com.pawbridge.backend.store.internal.directorder.web.DirectOrderController;
import com.pawbridge.backend.store.internal.directorder.web.OrderReadController;
import com.pawbridge.backend.store.internal.directorder.web.CartOrderController;
import com.pawbridge.backend.store.internal.directorder.web.DirectOrderExceptionHandler;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StoreCartConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = {"absent", "false", "yes", "on", "1", "unknown"})
    void given__neither_writer_feature_is_explicitly_true__when__assembling_without_database_inputs__then__register_no_writer_or_cart_beans(String flag) {
        var runner = runner();
        if (!flag.equals("absent")) runner = runner.withPropertyValues(
                "pawbridge.store-carts.enabled=" + flag, "pawbridge.store-direct-orders.enabled=" + flag);
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(StoreJpaWriterConfiguration.class)
                .doesNotHaveBean(EntityManagerFactory.class).doesNotHaveBean(StoreOrderActors.class)
                .doesNotHaveBean(AuditingHandler.class).doesNotHaveBean(DirectOrders.class)
                .doesNotHaveBean(StoreOrderSupportConfiguration.class).doesNotHaveBean(OrderReadController.class)
                .doesNotHaveBean(DirectOrderController.class).doesNotHaveBean(DirectOrderExceptionHandler.class)
                .doesNotHaveBean(CartOrders.class).doesNotHaveBean(CartOrderController.class).doesNotHaveBean(KoreanSearchTerms.class)
                .doesNotHaveBean("storeCarts").doesNotHaveBean("cartController").doesNotHaveBean("cartExceptionHandler")
                .doesNotHaveBean(StoreMemberReads.class).doesNotHaveBean(StoreCatalog.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"cart", "both", "direct-with-cart-on"})
    void given__selected_store_writer_features__when__assembling__then__share_one_writer_and_auditor_without_enabling_the_other_api(String mode) {
        boolean carts = !mode.equals("direct-with-cart-on");
        boolean orders = !mode.equals("cart");
        DataSource writer = mock(DataSource.class);
        DataSource reader = mock(DataSource.class);
        PlatformTransactionManager readerManager = mock(PlatformTransactionManager.class);
        AtomicReference<EntityManagerFactory> unit = new AtomicReference<>();
        runner().withBean("writeDataSource", DataSource.class, () -> writer)
                .withBean("writeJdbc", JdbcTemplate.class, () -> new JdbcTemplate(writer))
                .withBean("dataSource", DataSource.class, () -> reader)
                .withBean("transactionManager", PlatformTransactionManager.class, () -> readerManager)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("pawbridge.store-carts.enabled=" + (carts ? "TrUe" : "on"),
                        "pawbridge.store-direct-orders.enabled=" + orders)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(StoreJpaWriterConfiguration.class)
                            .hasSingleBean(EntityManagerFactory.class).hasSingleBean(JpaTransactionManager.class)
                            .hasSingleBean(StoreOrderSupportConfiguration.class).hasSingleBean(OrderReadController.class)
                            .hasSingleBean(DirectOrderExceptionHandler.class)
                            .hasSingleBean(JpaAuditingConfiguration.class).hasSingleBean(AuditingHandler.class)
                            .hasSingleBean(StoreOrderActors.class).doesNotHaveBean(StoreMemberReads.class)
                            .doesNotHaveBean(StoreCatalog.class).doesNotHaveBean("storeMemberEntityManagerFactory");
                    unit.set(context.getBean("storeDirectOrderEntityManagerFactory", EntityManagerFactory.class));
                    var factory = context.getBean("&storeDirectOrderEntityManagerFactory", LocalContainerEntityManagerFactoryBean.class);
                    assertThat(factory.getDataSource()).isSameAs(writer).isNotSameAs(reader);
                    String model = "com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.";
                    var managed = new ArrayList<>(List.of(model + "BaseEntity", model + "Wishlist", model + "ProductSKU",
                            model + "Product", model + "Category", model + "SKUValue", model + "OptionValue", model + "OptionGroup",
                            model + "Order", model + "OrderItem"));
                    if (carts) managed.addAll(List.of("com.pawbridge.backend.store.internal.cart.infrastructure.jpa.model.Cart",
                            "com.pawbridge.backend.store.internal.cart.infrastructure.jpa.model.CartItem"));
                    assertThat(factory.getPersistenceUnitInfo().getManagedClassNames()).containsExactlyInAnyOrderElementsOf(managed);
                    assertThat(factory.getJpaPropertyMap()).containsEntry("hibernate.boot.allow_jdbc_metadata_access", false)
                            .containsEntry("hibernate.hbm2ddl.auto", "none")
                            .containsEntry("jakarta.persistence.schema-generation.database.action", "none")
                            .containsEntry("hibernate.default_schema", "pawbridge_store")
                            .containsEntry("hibernate.jdbc.time_zone", "UTC")
                            .containsEntry("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
                    var manager = context.getBean("storeDirectOrderJpaTransactionManager", JpaTransactionManager.class);
                    assertThat(manager.getDataSource()).isSameAs(writer);
                    assertThat(manager.getEntityManagerFactory()).isSameAs(unit.get());
                    assertThat(manager.getDefaultTimeout()).isEqualTo(15);
                    var writes = context.getBean("storeDirectOrderWrites", TransactionTemplate.class);
                    var reads = context.getBean("storeDirectOrderReads", TransactionTemplate.class);
                    for (var transaction : List.of(writes, reads)) {
                        assertThat(transaction.getTransactionManager()).isSameAs(manager).isNotSameAs(readerManager);
                        assertThat(transaction.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
                        assertThat(transaction.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT);
                        assertThat(transaction.getTimeout()).isEqualTo(TransactionDefinition.TIMEOUT_DEFAULT);
                    }
                    assertThat(writes.isReadOnly()).isFalse();
                    assertThat(reads.isReadOnly()).isTrue();
                    EntityManager shared = context.getBean("storeDirectOrderEntityManager", EntityManager.class);
                    assertThat(shared.getEntityManagerFactory()).isSameAs(unit.get());
                    var actorJdbc = (JdbcTemplate) ReflectionTestUtils.getField(context.getBean(StoreOrderActors.class), "jdbc");
                    assertThat(actorJdbc.getDataSource()).isSameAs(writer);
                    assertThat(context.containsBean("storeCarts")).isEqualTo(carts);
                    if (carts) {
                        Object cartService = context.getBean("storeCarts");
                        assertThat(ReflectionTestUtils.getField(cartService, "em")).isSameAs(shared);
                        assertThat(ReflectionTestUtils.getField(cartService, "writes")).isSameAs(writes);
                        assertThat(ReflectionTestUtils.getField(cartService, "reads")).isSameAs(reads);
                        assertThat(ReflectionTestUtils.getField(cartService, "actors")).isSameAs(context.getBean(StoreOrderActors.class));
                        Object repository = ReflectionTestUtils.getField(cartService, "carts");
                        Object target = AopTestUtils.getUltimateTargetObject(repository);
                        assertThat(ReflectionTestUtils.getField(target, "entityManager")).isSameAs(shared);
                        Object checkout=context.getBean(CartOrders.class);
                        assertThat(ReflectionTestUtils.getField(checkout,"writes")).isSameAs(writes);
                        assertThat(ReflectionTestUtils.getField(checkout,"actors")).isSameAs(context.getBean(StoreOrderActors.class));
                        for(String name:List.of("carts","orders","skus")) {
                            Object checkoutRepository=ReflectionTestUtils.getField(checkout,name);
                            Object checkoutTarget=AopTestUtils.getUltimateTargetObject(checkoutRepository);
                            assertThat(ReflectionTestUtils.getField(checkoutTarget,"entityManager")).isSameAs(shared);
                        }
                    }
                    assertThat(context.containsBean("cartController")).isEqualTo(carts);
                    assertThat(context.containsBean("cartExceptionHandler")).isEqualTo(carts);
                    assertThat(context.getBeansOfType(DirectOrders.class)).hasSize(1);
                    assertThat(context.getBeansOfType(CartOrders.class)).hasSize(carts ? 1 : 0);
                    assertThat(context.getBeansOfType(CartOrderController.class)).hasSize(carts ? 1 : 0);
                    assertThat(context.getBeansOfType(DirectOrderController.class)).hasSize(orders ? 1 : 0);
                    assertThat(context.getBeansOfType(KoreanSearchTerms.class)).hasSize(1);
                    assertThat(context.containsBean("storeDirectOrderSearchJdbc")).isTrue();
                    verifyNoInteractions(writer, reader, readerManager);
                });
        assertThat(unit.get().isOpen()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"reader-only", "mismatched-jdbc"})
    void given__no_consistent_writer_inputs__when__carts_are_enabled__then__reject_without_falling_back_to_reader_access(String mode) {
        DataSource writer = mock(DataSource.class);
        DataSource other = mock(DataSource.class);
        var runner = runner().withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("pawbridge.store-carts.enabled=true");
        if (mode.equals("reader-only")) runner = runner.withBean("dataSource", DataSource.class, () -> other);
        else runner = runner.withBean("writeDataSource", DataSource.class, () -> writer)
                .withBean("writeJdbc", JdbcTemplate.class, () -> new JdbcTemplate(other));
        runner.run(context -> {
            assertThat(context).hasFailed();
            if (mode.equals("mismatched-jdbc"))
                assertThat(context.getStartupFailure()).hasRootCauseMessage("Store order actors must use the same writer DataSource");
            verifyNoInteractions(writer, other);
        });
    }

    @ParameterizedTest
    @ValueSource(strings={"cart","direct","both"})
    void given__enabled_order_features__when__mapping_http__then__expose_one_shared_read_controller_and_only_the_owned_posts(String mode) {
        boolean carts=!mode.equals("direct");
        boolean direct=!mode.equals("cart");
        DataSource writer=mock(DataSource.class);
        runner().withBean("writeDataSource",DataSource.class,()->writer)
                .withBean("writeJdbc",JdbcTemplate.class,()->new JdbcTemplate(writer))
                .withBean(ObjectMapper.class,ObjectMapper::new)
                .withPropertyValues("pawbridge.store-carts.enabled="+carts,
                        "pawbridge.store-direct-orders.enabled="+direct)
                .run(context->{
                    assertThat(context).hasNotFailed().hasSingleBean(OrderReadController.class);
                    var controllers=context.getBeansWithAnnotation(RestController.class).values().toArray();
                    var advice=context.getBeansOfType(DirectOrderExceptionHandler.class).values().toArray();
                    SecurityContextHolder.clearContext();
                    try {
                        // No SecurityFilterChain is installed: this protects bean-to-handler exposure only.
                        // Missing principals/invalid bodies stop before any business SQL.
                        var mvc=MockMvcBuilders.standaloneSetup(controllers).setControllerAdvice(advice)
                                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
                        var read=mvc.perform(get("/api/v1/orders/7")).andReturn();
                        assertThat(read.getResponse().getStatus()).isEqualTo(401);
                        assertThat(((HandlerMethod)read.getHandler()).getBeanType()).isEqualTo(OrderReadController.class);
                        var directPost=mvc.perform(post("/api/v1/orders/direct")
                                .contentType("application/json").content("{}")).andReturn();
                        if(direct) {
                            assertThat(((HandlerMethod)directPost.getHandler()).getBeanType()).isEqualTo(DirectOrderController.class);
                            assertThat(directPost.getResponse().getStatus()).isIn(400,401);
                        } else {
                            // The shared GET /orders/{orderId} matches this path, but owns no POST.
                            assertThat(directPost.getResponse().getStatus()).isEqualTo(405);
                            assertThat(directPost.getHandler()).isNull();
                        }
                        var cartPost=mvc.perform(post("/api/v1/orders")
                                .contentType("application/json").content("{}")).andReturn();
                        if(carts) {
                            assertThat(((HandlerMethod)cartPost.getHandler()).getBeanType()).isEqualTo(CartOrderController.class);
                            assertThat(cartPost.getResponse().getStatus()).isEqualTo(401);
                        } else {
                            // Collection GET exists; standalone MVC rejects its unsupported POST as 405.
                            // The SecurityFilterChain separately denies this unowned POST before MVC.
                            assertThat(cartPost.getResponse().getStatus()).isEqualTo(405);
                            assertThat(cartPost.getHandler()).isNull();
                        }
                    } finally { SecurityContextHolder.clearContext(); }
                    verifyNoInteractions(writer);
                });
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(StoreDirectOrderConfiguration.class, StoreCartConfiguration.class,
                        StoreMemberJpaConfiguration.class, StoreCatalogConfiguration.class);
    }
}
