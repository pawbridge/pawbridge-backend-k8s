package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms;
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogOptions;
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogProducts;
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.S3ProductImages;
import com.pawbridge.backend.store.internal.catalogwrite.web.*;
import com.pawbridge.backend.store.internal.directorder.web.DirectOrderController;
import com.pawbridge.backend.store.internal.directorder.web.OrderReadController;
import com.pawbridge.backend.store.internal.cart.web.CartController;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** No real DB/storage client: assembly identity, fail-closed opt-in and owned-close contracts only. */
class StoreCatalogManagementConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings={"absent","false","yes","on","1","unknown"})
    void given__catalog_not_explicitly_true__when__assembling_without_inputs__then__create_no_catalog_writer_or_storage(String flag) {
        var runner=runner();if(!flag.equals("absent"))runner=runner.withPropertyValues("pawbridge.store-catalog-management.enabled="+flag);
        runner.run(context->assertThat(context).hasNotFailed().doesNotHaveBean(EntityManagerFactory.class)
                .doesNotHaveBean(JpaCatalogProducts.class).doesNotHaveBean(JpaCatalogOptions.class).doesNotHaveBean(S3ProductImages.class)
                .doesNotHaveBean(ProductWriteController.class).doesNotHaveBean(OptionManagementController.class)
                .doesNotHaveBean(ProductImageController.class).doesNotHaveBean(CatalogManagementExceptionHandler.class)
                .doesNotHaveBean(KoreanSearchTerms.class).doesNotHaveBean(AuditingHandler.class).doesNotHaveBean(S3Client.class));
    }

    @ParameterizedTest
    @ValueSource(strings={"catalog","catalog-direct","catalog-cart","catalog-both"})
    void given__enabled_catalog_and_existing_features__when__assembling__then__share_one_writer_and_borrow_storage_until_owner_closes(String mode) {
        DataSource writer=mock(DataSource.class);S3Client storage=mock(S3Client.class);PostWriteSettings settings=settings();
        var unit=new AtomicReference<EntityManagerFactory>();var terms=new AtomicReference<KoreanSearchTerms>();
        writerInputs(writer).withBean("postS3Client",S3Client.class,()->storage,definition->definition.setDestroyMethodName("close"))
                .withBean(PostWriteSettings.class,()->settings)
                .withPropertyValues("pawbridge.store-catalog-management.enabled=TrUe",
                        "pawbridge.store-direct-orders.enabled="+(mode.equals("catalog-direct")||mode.equals("catalog-both")),
                        "pawbridge.store-carts.enabled="+(mode.equals("catalog-cart")||mode.equals("catalog-both")))
                .run(context->{
                    assertThat(context).hasNotFailed().hasSingleBean(StoreJpaWriterConfiguration.class).hasSingleBean(EntityManagerFactory.class)
                            .hasSingleBean(JpaTransactionManager.class).hasSingleBean(JpaAuditingConfiguration.class).hasSingleBean(AuditingHandler.class)
                            .hasSingleBean(JpaCatalogProducts.class).hasSingleBean(JpaCatalogOptions.class).hasSingleBean(S3ProductImages.class)
                            .hasSingleBean(ProductWriteController.class).hasSingleBean(OptionManagementController.class)
                            .hasSingleBean(ProductImageController.class).hasSingleBean(CatalogManagementExceptionHandler.class).hasSingleBean(S3Client.class);
                    unit.set(context.getBean("storeDirectOrderEntityManagerFactory",EntityManagerFactory.class));
                    var factory=context.getBean("&storeDirectOrderEntityManagerFactory",LocalContainerEntityManagerFactoryBean.class);
                    assertThat(factory.getDataSource()).isSameAs(writer);
                    String model="com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.";
                    assertThat(factory.getPersistenceUnitInfo().getManagedClassNames()).containsExactlyInAnyOrder(
                            model+"BaseEntity",model+"Wishlist",model+"ProductSKU",model+"Product",model+"Category",model+"SKUValue",
                            model+"OptionValue",model+"OptionGroup",model+"Order",model+"OrderItem",
                            "com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.model.Outbox",
                            "com.pawbridge.backend.store.internal.cart.infrastructure.jpa.model.Cart",
                            "com.pawbridge.backend.store.internal.cart.infrastructure.jpa.model.CartItem");
                    assertThat(factory.getJpaPropertyMap()).containsEntry("hibernate.boot.allow_jdbc_metadata_access",false)
                            .containsEntry("hibernate.hbm2ddl.auto","none").containsEntry("jakarta.persistence.schema-generation.database.action","none")
                            .containsEntry("hibernate.default_schema","pawbridge_store");
                    var manager=context.getBean("storeDirectOrderJpaTransactionManager",JpaTransactionManager.class);
                    assertThat(manager.getEntityManagerFactory()).isSameAs(unit.get());assertThat(manager.getDataSource()).isSameAs(writer);
                    var writes=context.getBean("storeDirectOrderWrites",TransactionTemplate.class);
                    var reads=context.getBean("storeDirectOrderReads",TransactionTemplate.class);
                    assertThat(writes.getTransactionManager()).isSameAs(manager);assertThat(reads.getTransactionManager()).isSameAs(manager);
                    assertThat(writes.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
                    assertThat(writes.isReadOnly()).isFalse();assertThat(reads.isReadOnly()).isTrue();
                    EntityManager shared=context.getBean("storeDirectOrderEntityManager",EntityManager.class);assertThat(shared.getEntityManagerFactory()).isSameAs(unit.get());
                    Object products=context.getBean(JpaCatalogProducts.class),options=context.getBean(JpaCatalogOptions.class);
                    assertThat(ReflectionTestUtils.getField(products,"writes")).isSameAs(writes);assertThat(ReflectionTestUtils.getField(options,"writes")).isSameAs(writes);
                    for(String field:List.of("products","categories","skus","cartItems"))sameRepositoryWriter(ReflectionTestUtils.getField(products,field),shared);
                    for(String field:List.of("groups","values","products","skus"))sameRepositoryWriter(ReflectionTestUtils.getField(options,field),shared);
                    var jdbc=context.getBean("storeCatalogManagementSearchJdbc",NamedParameterJdbcTemplate.class);
                    assertThat(jdbc.getJdbcTemplate().getDataSource()).isSameAs(writer);assertThat(jdbc.getJdbcTemplate().getQueryTimeout()).isEqualTo(5);
                    terms.set(context.getBean("storeCatalogManagementSearchTerms",KoreanSearchTerms.class));assertThat(terms.get().document("말티즈")).isNotBlank();
                    for(Object service:List.of(products,options)) {
                        Object snapshot=ReflectionTestUtils.getField(service,"snapshots");sameRepositoryWriter(ReflectionTestUtils.getField(snapshot,"outbox"),shared);
                        Object documents=ReflectionTestUtils.getField(snapshot,"documents");assertThat(ReflectionTestUtils.getField(documents,"jdbc")).isSameAs(jdbc);
                    }
                    S3ProductImages images=context.getBean(S3ProductImages.class);
                    assertThat(ReflectionTestUtils.getField(images,"s3Client")).isSameAs(storage);
                    assertThat(ReflectionTestUtils.getField(images,"bucketName")).isEqualTo(settings.bucket());
                    assertThat(ReflectionTestUtils.getField(images,"publicBaseUrl")).isEqualTo(settings.publicBaseUrl());
                    assertThat(ReflectionTestUtils.getField(context.getBean(ProductWriteController.class),"products")).isSameAs(products);
                    assertThat(ReflectionTestUtils.getField(context.getBean(OptionManagementController.class),"options")).isSameAs(options);
                    assertThat(ReflectionTestUtils.getField(context.getBean(ProductImageController.class),"images")).isSameAs(images);
                    if(mode.equals("catalog"))assertThat(context).doesNotHaveBean(DirectOrderController.class).doesNotHaveBean(OrderReadController.class).doesNotHaveBean(CartController.class);
                    verifyNoInteractions(writer,storage);
                });
        assertThat(unit.get().isOpen()).isFalse();assertThatThrownBy(()->terms.get().document("말티즈")).isInstanceOf(IllegalStateException.class);
        verify(storage,times(1)).close();verifyNoMoreInteractions(storage);verifyNoInteractions(writer);
    }

    @ParameterizedTest
    @ValueSource(strings={"reader-only","missing-storage"})
    void given__missing_owned_writer_or_existing_storage__when__catalog_enabled__then__fail_without_fallback_or_external_access(String mode) {
        DataSource source=mock(DataSource.class);S3Client storage=mock(S3Client.class);
        var runner=runner().withBean(ObjectMapper.class,ObjectMapper::new).withBean(PostWriteSettings.class,StoreCatalogManagementConfigurationTest::settings)
                .withPropertyValues("pawbridge.store-catalog-management.enabled=true");
        if(mode.equals("reader-only"))runner=runner.withBean("dataSource",DataSource.class,()->source).withBean("postS3Client",S3Client.class,()->storage,definition->definition.setDestroyMethodName(""));
        else runner=runner.withBean("writeDataSource",DataSource.class,()->source).withBean("writeJdbc",JdbcTemplate.class,()->new JdbcTemplate(source));
        runner.run(context->{
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class)
                    .hasStackTraceContaining(mode.equals("reader-only")?"writeDataSource":"postS3Client");
            verifyNoInteractions(source,storage);
        });
    }

    private static void sameRepositoryWriter(Object repository,EntityManager shared){
        Object target=AopTestUtils.getUltimateTargetObject(repository);assertThat(ReflectionTestUtils.getField(target,"entityManager")).isSameAs(shared);
    }
    private static ApplicationContextRunner runner(){
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(StoreCatalogManagementConfiguration.class,StoreDirectOrderConfiguration.class,StoreCartConfiguration.class);
    }
    private static ApplicationContextRunner writerInputs(DataSource source){return runner().withBean("writeDataSource",DataSource.class,()->source)
            .withBean("writeJdbc",JdbcTemplate.class,()->new JdbcTemplate(source)).withBean(ObjectMapper.class,ObjectMapper::new);}
    private static PostWriteSettings settings(){
        Map<String,String> readerValues=Map.of("F2_JDBC_URL","jdbc:postgresql://127.0.0.1:1/catalog_unit",
                "F2_DB_USER","catalog_reader","F2_DB_PASSWORD","synthetic-reader");
        var reader=ReadDatabaseSettings.fromEnvironment(readerValues::get);
        Map<String,String> values=Map.ofEntries(Map.entry("F3_DB_WRITER_USER","catalog_writer"),Map.entry("F3_DB_WRITER_PASSWORD","synthetic-writer"),
                Map.entry("F3_JWT_SECRET","01234567890123456789012345678901"),Map.entry("F3_STORAGE_ENDPOINT","https://r2.invalid"),
                Map.entry("F3_STORAGE_BUCKET","catalog-fixture"),Map.entry("F3_STORAGE_PUBLIC_BASE_URL","https://assets.invalid"),Map.entry("F3_STORAGE_OBJECT_PREFIX","posts"),
                Map.entry("F3_STORAGE_ACCESS_KEY_ID","synthetic-access"),Map.entry("F3_STORAGE_SECRET_ACCESS_KEY","synthetic-secret"),Map.entry("F3_STORAGE_REGION","auto"));
        return PostWriteSettings.fromEnvironment(values::get,reader);
    }
}
