package com.pawbridge.storeservice.migration;

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
import com.pawbridge.storeservice.domain.product.entity.*;
import com.pawbridge.storeservice.domain.product.repository.ProductSKURepository;
import com.pawbridge.storeservice.domain.order.entity.*;
import com.pawbridge.storeservice.domain.cart.entity.*;
import com.pawbridge.storeservice.common.entity.Outbox;

@Tag("postgresql")
class StorePostgresqlPersistenceTest {
    private HikariDataSource source;
    private SessionFactory factory;
    private StandardServiceRegistry registry;
    private JdbcTemplate jdbc;
    private static final LocalDateTime NOW = LocalDateTime.of(2026,9,20,12,0);

    @BeforeEach
    void prepare_guarded_database_and_validate_all_entities() throws Exception {
        String port = System.getenv("STORE_PG_MIGRATION_TEST_PORT");
        if (port == null || !port.matches("[0-9]{1,5}")) throw new IllegalStateException("Missing disposable test port");
        String url = "jdbc:postgresql://127.0.0.1:" + port + "/pawbridge";
        Map<String,String> environment = StorePostgresqlMigrationTest.environment(url);
        environment.put("STORE_PG_MIGRATION_CONFIRM_TARGET",url);
        StorePostgresqlMigration.Settings settings = StorePostgresqlMigration.Settings.from(environment);
        try (Connection connection = DriverManager.getConnection(url,settings.username(),settings.password());
                Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery("SELECT marker FROM migration_test_guard.guard")) {
                if (!rows.next() || !"services-pg-disposable".equals(rows.getString(1)) || rows.next())
                    throw new IllegalStateException("Missing disposable database guard");
            }
            statement.execute("DROP SCHEMA IF EXISTS pawbridge_store CASCADE");
            statement.execute("CREATE SCHEMA pawbridge_store");
        }
        StorePostgresqlMigration.execute("migrate",settings);
        // Bind the real opt-in YAML, so misspelled environment keys or driver/schema settings fail here.
        org.springframework.core.env.StandardEnvironment environmentProperties = new org.springframework.core.env.StandardEnvironment();
        environmentProperties.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("test-target",Map.of(
                "STORE_POSTGRESQL_JDBC_URL",url,"STORE_POSTGRESQL_USERNAME",settings.username(),
                "STORE_POSTGRESQL_PASSWORD",settings.password(),"STORE_POSTGRESQL_POOL_MAX","2")));
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
                .applySetting("hibernate.default_schema","pawbridge_store")
                .applySetting("hibernate.hbm2ddl.auto","validate")
                .applySetting("hibernate.physical_naming_strategy","org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                .build();
        MetadataSources metadata = new MetadataSources(registry);
        for (Class<?> entity : java.util.List.of(Product.class,ProductSKU.class,Category.class,OptionGroup.class,OptionValue.class,SKUValue.class,Wishlist.class,Cart.class,CartItem.class,Order.class,OrderItem.class,Outbox.class)) metadata.addAnnotatedClass(entity);
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
    void order_stock_and_json_event_commit_together_and_failed_event_rolls_back() {
        Product product=Product.builder().name("사료").status(ProductStatus.ACTIVE).build();
        ProductSKU sku=ProductSKU.builder().product(product).skuCode("FOOD-1").price(1000L).stockQuantity(5).build();
        commit(session -> { session.persist(product);session.persist(sku); });
        Order order=Order.builder().orderUuid("order-1").userId(7L).totalAmount(2000L)
                .deliveryAddress("테스트 주소").receiverName("보호자").receiverPhone("000-0000-0000").build();
        commit(session -> {
            ProductSKURepository repository = new JpaRepositoryFactory(session).getRepository(ProductSKURepository.class);
            ProductSKU locked=repository.findAllByProductIdWithLock(product.getId()).get(0);
            locked.decreaseStock(2);session.persist(order);
            session.persist(OrderItem.builder().order(order).productSKU(locked).productName("사료").skuCode("FOOD-1").price(1000L).quantity(2).build());
            session.persist(event("{\"orderId\":\"order-1\",\"amount\":2000}"));
        });
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product_skus",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT json_typeof(payload->'amount') FROM outbox",String.class)).isEqualTo("number");
        assertThatThrownBy(() -> commit(session -> {
            session.find(ProductSKU.class,sku.getId()).decreaseStock(1);
            session.find(Order.class,order.getId()).paid();session.flush();session.persist(event("not-json"));
        })).isInstanceOf(org.hibernate.exception.DataException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM orders",String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product_skus",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox",Integer.class)).isEqualTo(1);
    }

    @Test
    void cart_keeps_both_sku_columns_and_orphan_removal() {
        Cart cart=Cart.builder().userId(7L).build();
        cart.addItem(CartItem.builder().skuId(99L).quantity(2).build());
        commit(session -> session.persist(cart));
        assertThat(jdbc.queryForObject("SELECT sku_id=product_sku_id FROM cart_items",Boolean.class)).isTrue();
        commit(session -> session.find(Cart.class,cart.getId()).clearItems());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cart_items",Integer.class)).isZero();
        assertThatThrownBy(() -> commit(session -> session.persist(Cart.builder().userId(7L).build())))
                .isInstanceOf(org.hibernate.exception.ConstraintViolationException.class);
    }

    @Test
    void stock_row_lock_excludes_another_writer_until_rollback() {
        Product product=Product.builder().name("사료").status(ProductStatus.ACTIVE).build();
        ProductSKU sku=ProductSKU.builder().product(product).skuCode("FOOD-1").price(1000L).stockQuantity(5).build();
        commit(session -> { session.persist(product);session.persist(sku); });
        try (Session first=factory.openSession();Session second=factory.openSession()) {
            Transaction a=first.beginTransaction();Transaction b=second.beginTransaction();
            try {
                new JpaRepositoryFactory(first).getRepository(ProductSKURepository.class).findAllByProductIdWithLock(product.getId());
                second.createNativeMutationQuery("SET LOCAL lock_timeout='150ms'").executeUpdate();
                assertThatThrownBy(() -> new JpaRepositoryFactory(second).getRepository(ProductSKURepository.class).findAllByProductIdWithLock(product.getId()))
                        .rootCause().isInstanceOf(java.sql.SQLException.class)
                        .satisfies(cause -> assertThat(((java.sql.SQLException) cause).getSQLState()).isEqualTo("55P03"));
            } finally { if(b.isActive()) b.rollback(); if(a.isActive()) a.rollback(); }
        }
        commit(session -> new JpaRepositoryFactory(session).getRepository(ProductSKURepository.class)
                .findAllByProductIdWithLock(product.getId()).get(0).decreaseStock(1));
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product_skus",Integer.class)).isEqualTo(4);
    }

    private Outbox event(String payload) {
        return Outbox.builder().aggregateType("ORDER").aggregateId("order-1").eventType("OrderCreated").payload(payload).build();
    }

    @Test
    void product_search_uses_lowest_price_then_id_live_total_stock_and_stable_pagination() {
        jdbc.update("INSERT INTO products(id,name,status,view_count) VALUES (1,'강아지 사료','ACTIVE',0),(2,'고양이 사료','ACTIVE',0),(3,'숨긴 사료','HIDDEN',0)");
        jdbc.update("INSERT INTO product_skus(id,product_id,sku_code,price,stock_quantity) VALUES (10,1,'A',2000,5),(11,1,'B',1000,0),(12,1,'C',1000,2),(20,2,'D',3000,0),(30,3,'E',500,9)");
        com.pawbridge.storeservice.search.KoreanSearchTerms terms=new com.pawbridge.storeservice.search.KoreanSearchTerms();
        try {
            com.pawbridge.storeservice.search.PostgresqlSearchDocuments documents=new com.pawbridge.storeservice.search.PostgresqlSearchDocuments(source,terms);
            org.springframework.transaction.support.TransactionTemplate tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
            com.pawbridge.storeservice.domain.product.service.PostgresqlProductSearchService search=new com.pawbridge.storeservice.domain.product.service.PostgresqlProductSearchService(source,terms);
            com.pawbridge.storeservice.domain.product.dto.ProductSearchRequest request=com.pawbridge.storeservice.domain.product.dto.ProductSearchRequest.builder().keyword("사료를").sortBy("price").sortOrder("asc").size(1).build();
            assertThatThrownBy(() -> search.searchProducts(request)).isInstanceOf(com.pawbridge.storeservice.search.SearchUnavailableException.class);
            Integer rebuilt = tx.execute(status -> documents.rebuildPage(100));
            assertThat(rebuilt).isEqualTo(2);
            com.pawbridge.storeservice.domain.product.dto.ProductSearchResponse result=search.searchProducts(request);
            assertThat(result.getTotalCount()).isEqualTo(2);assertThat(result.getTotalPages()).isEqualTo(2);assertThat(result.getHasNext()).isTrue();
            assertThat(result.getItems()).extracting(com.pawbridge.storeservice.domain.product.dto.ProductSearchItem::getSkuId).containsExactly(11L);
            assertThat(result.getItems().get(0).getTotalStock()).isEqualTo(7);
            assertThat(search.searchProducts(com.pawbridge.storeservice.domain.product.dto.ProductSearchRequest.builder().inStockOnly(true).minPrice(1000L).maxPrice(1000L).build()).getItems()).hasSize(1);
            assertThat(search.searchProducts(com.pawbridge.storeservice.domain.product.dto.ProductSearchRequest.builder().keyword("사료").page(9).size(1).build()).getTotalCount()).isEqualTo(2);
            jdbc.update("UPDATE product_skus SET stock_quantity=0 WHERE product_id=1");
            assertThat(search.searchProducts(com.pawbridge.storeservice.domain.product.dto.ProductSearchRequest.builder().inStockOnly(true).build()).getItems()).isEmpty();
            jdbc.update("UPDATE product_skus SET price=500 WHERE id=10");
            assertThatThrownBy(() -> search.searchProducts(request)).isInstanceOf(com.pawbridge.storeservice.search.SearchUnavailableException.class);
            tx.executeWithoutResult(status -> documents.rebuildPage(100));
            assertThat(search.searchProducts(request).getItems().get(0).getSkuId()).isEqualTo(10L);
            assertThatThrownBy(() -> search.searchProducts(com.pawbridge.storeservice.domain.product.dto.ProductSearchRequest.builder().sortBy("price;DROP TABLE products").build())).isInstanceOf(IllegalArgumentException.class);
        } finally { terms.close(); }
    }

    @Test
    void option_rename_requires_recovery_but_category_and_visibility_are_read_live() {
        jdbc.update("INSERT INTO categories(id,name) VALUES (1,'강아지'),(2,'고양이')");
        jdbc.update("INSERT INTO products(id,name,status,view_count,category_id) VALUES (1,'목줄','ACTIVE',0,1)");
        jdbc.update("INSERT INTO product_skus(id,product_id,sku_code,price,stock_quantity) VALUES (10,1,'RED',1000,3)");
        jdbc.update("INSERT INTO option_groups(id,name) VALUES (1,'색상')");
        jdbc.update("INSERT INTO option_values(id,option_group_id,name) VALUES (1,1,'빨강')");
        jdbc.update("INSERT INTO sku_values(product_sku_id,option_value_id) VALUES (10,1)");
        com.pawbridge.storeservice.search.KoreanSearchTerms terms = new com.pawbridge.storeservice.search.KoreanSearchTerms();
        try {
            com.pawbridge.storeservice.search.PostgresqlSearchDocuments documents = new com.pawbridge.storeservice.search.PostgresqlSearchDocuments(source,terms);
            org.springframework.transaction.support.TransactionTemplate tx = new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
            com.pawbridge.storeservice.domain.product.service.PostgresqlProductSearchService search = new com.pawbridge.storeservice.domain.product.service.PostgresqlProductSearchService(source,terms);
            tx.executeWithoutResult(status -> documents.rebuildPage(100));
            com.pawbridge.storeservice.domain.product.dto.ProductSearchRequest red = com.pawbridge.storeservice.domain.product.dto.ProductSearchRequest.builder().keyword("빨강").categoryId(1L).build();
            assertThat(search.searchProducts(red).getItems()).extracting(com.pawbridge.storeservice.domain.product.dto.ProductSearchItem::getOptionName).containsExactly("색상: 빨강");
            jdbc.update("UPDATE option_values SET name='파랑' WHERE id=1");
            assertThatThrownBy(() -> search.searchProducts(red)).isInstanceOf(com.pawbridge.storeservice.search.SearchUnavailableException.class);
            tx.executeWithoutResult(status -> documents.rebuildPage(100));
            assertThat(search.searchProducts(red).getItems()).isEmpty();
            com.pawbridge.storeservice.domain.product.dto.ProductSearchRequest blue = com.pawbridge.storeservice.domain.product.dto.ProductSearchRequest.builder().keyword("파랑").categoryId(1L).build();
            assertThat(search.searchProducts(blue).getItems()).hasSize(1);
            jdbc.update("UPDATE products SET category_id=2 WHERE id=1");
            assertThat(search.searchProducts(blue).getItems()).isEmpty();
            assertThat(search.searchProducts(com.pawbridge.storeservice.domain.product.dto.ProductSearchRequest.builder().keyword("파랑").categoryId(2L).build()).getItems()).hasSize(1);
            jdbc.update("UPDATE products SET status='DELETED' WHERE id=1");
            assertThat(search.searchProducts(com.pawbridge.storeservice.domain.product.dto.ProductSearchRequest.builder().keyword("파랑").build()).getItems()).isEmpty();
        } finally { terms.close(); }
    }

    @Test
    void actual_product_outbox_path_updates_search_terms_and_failure_rolls_back_rename() {
        Product product=Product.builder().name("강아지 사료").status(ProductStatus.ACTIVE).build();
        ProductSKU sku=ProductSKU.builder().product(product).skuCode("S1").price(1000L).stockQuantity(2).build();
        commit(session -> { session.persist(product);session.persist(sku); });
        com.pawbridge.storeservice.search.KoreanSearchTerms terms=new com.pawbridge.storeservice.search.KoreanSearchTerms();
        try {
            com.pawbridge.storeservice.search.PostgresqlSearchDocuments documents=new com.pawbridge.storeservice.search.PostgresqlSearchDocuments(source,terms);
            org.springframework.orm.jpa.JpaTransactionManager manager=new org.springframework.orm.jpa.JpaTransactionManager(factory);
            manager.setDataSource(source);manager.setJpaDialect(new org.springframework.orm.jpa.vendor.HibernateJpaDialect());manager.afterPropertiesSet();
            jakarta.persistence.EntityManager em=org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(factory);
            com.pawbridge.storeservice.common.repository.OutboxRepository events=new JpaRepositoryFactory(em).getRepository(com.pawbridge.storeservice.common.repository.OutboxRepository.class);
            com.pawbridge.storeservice.domain.product.service.ProductSKUService skus=new com.pawbridge.storeservice.domain.product.service.ProductSKUService(
                    org.mockito.Mockito.mock(com.pawbridge.storeservice.domain.product.repository.ProductSKURepository.class),
                    org.mockito.Mockito.mock(com.pawbridge.storeservice.domain.product.repository.SKUValueRepository.class),
                    org.mockito.Mockito.mock(com.pawbridge.storeservice.domain.product.repository.OptionValueRepository.class));
            com.pawbridge.storeservice.domain.product.service.ProductOutboxService outbox=new com.pawbridge.storeservice.domain.product.service.ProductOutboxService(events,new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(),skus,java.util.Optional.of(documents));
            org.springframework.transaction.support.TransactionTemplate tx=new org.springframework.transaction.support.TransactionTemplate(manager);
            tx.executeWithoutResult(status -> outbox.publishProductSnapshot(em.find(Product.class,product.getId())));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM product_search_documents",Integer.class)).isEqualTo(1);
            jdbc.execute("ALTER TABLE product_search_documents ADD CONSTRAINT reject_terms CHECK(false) NOT VALID");
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                Product current=em.find(Product.class,product.getId());current.updateName("고양이 사료");outbox.publishProductSnapshot(current);
            })).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(jdbc.queryForObject("SELECT name FROM products",String.class)).isEqualTo("강아지 사료");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox",Integer.class)).isEqualTo(1);
        } finally { terms.close(); }
    }

}
