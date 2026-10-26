package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.store.internal.cart.web.CartController
import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogCategories
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogOptions
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogProducts
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.S3ProductImages
import com.pawbridge.backend.store.internal.catalogwrite.web.CatalogManagementExceptionHandler
import com.pawbridge.backend.store.internal.catalogwrite.web.CategoryManagementController
import com.pawbridge.backend.store.internal.catalogwrite.web.OptionManagementController
import com.pawbridge.backend.store.internal.catalogwrite.web.ProductImageController
import com.pawbridge.backend.store.internal.catalogwrite.web.ProductWriteController
import com.pawbridge.backend.store.internal.directorder.web.DirectOrderController
import com.pawbridge.backend.store.internal.directorder.web.OrderReadController
import io.mockk.confirmVerified
import io.mockk.excludeRecords
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.auditing.AuditingHandler
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import software.amazon.awssdk.services.s3.S3Client
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Function
import java.util.function.Supplier
import javax.sql.DataSource

/** No real DB/storage client: assembly identity, fail-closed opt-in and owned-close contracts only. */
class StoreCatalogManagementConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = ["absent", "false", "yes", "on", "1", "unknown"])
    fun `카탈로그가 명시적 true가 아닐 때__입력 없이 조립하면__카탈로그 쓰기와 스토리지를 만들지 않는다`(
        flag: String,
    ) {
        // given
        var runner = runner()
        if (flag != "absent") {
            runner = runner.withPropertyValues("pawbridge.store-catalog-management.enabled=$flag")
        }

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
                .doesNotHaveBean(EntityManagerFactory::class.java)
                .doesNotHaveBean(JpaCatalogProducts::class.java)
                .doesNotHaveBean(JpaCatalogOptions::class.java)
                .doesNotHaveBean(S3ProductImages::class.java)
                .doesNotHaveBean(ProductWriteController::class.java)
                .doesNotHaveBean(OptionManagementController::class.java)
                .doesNotHaveBean(ProductImageController::class.java)
                .doesNotHaveBean(CatalogManagementExceptionHandler::class.java)
                .doesNotHaveBean(JpaCatalogCategories::class.java)
                .doesNotHaveBean(CategoryManagementController::class.java)
                .doesNotHaveBean(KoreanSearchTerms::class.java)
                .doesNotHaveBean(AuditingHandler::class.java)
                .doesNotHaveBean(S3Client::class.java)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["catalog", "catalog-direct", "catalog-cart", "catalog-both"])
    fun `카탈로그가 켜졌을 때__조립하면__스토리지를 한 번만 닫고 읽기·쓰기 트랜잭션을 구분한다`(
        mode: String,
    ) {
        // given
        val writer = mockk<DataSource>()
        val storage = mockk<S3Client>()

        excludeRecords {
            writer.hashCode()
            storage.hashCode()
        }

        val settings: PostWriteSettings = settings()
        val unit = AtomicReference<EntityManagerFactory>()
        val terms = AtomicReference<KoreanSearchTerms>()

        justRun { storage.close() }

        // when
        writerInputs(writer)
            .withBean(
                "postS3Client",
                S3Client::class.java,
                Supplier { storage },
                BeanDefinitionCustomizer { definition: BeanDefinition -> definition.setDestroyMethodName("close") },
            )
            .withBean(PostWriteSettings::class.java, Supplier { settings })
            .withPropertyValues(
                "pawbridge.store-catalog-management.enabled=TrUe",
                "pawbridge.store-direct-orders.enabled=" + (mode == "catalog-direct" || mode == "catalog-both"),
                "pawbridge.store-carts.enabled=" + (mode == "catalog-cart" || mode == "catalog-both"),
            )
            .run { context ->
                // then
                assertThat(context).hasNotFailed()
                    .hasSingleBean(StoreJpaWriterConfiguration::class.java)
                    .hasSingleBean(EntityManagerFactory::class.java)
                    .hasSingleBean(JpaTransactionManager::class.java)
                    .hasSingleBean(JpaAuditingConfiguration::class.java)
                    .hasSingleBean(AuditingHandler::class.java)
                    .hasSingleBean(JpaCatalogProducts::class.java)
                    .hasSingleBean(JpaCatalogOptions::class.java)
                    .hasSingleBean(S3ProductImages::class.java)
                    .hasSingleBean(ProductWriteController::class.java)
                    .hasSingleBean(OptionManagementController::class.java)
                    .hasSingleBean(ProductImageController::class.java)
                    .hasSingleBean(CatalogManagementExceptionHandler::class.java)
                    .hasSingleBean(S3Client::class.java)
                    .hasSingleBean(JpaCatalogCategories::class.java)
                    .hasSingleBean(CategoryManagementController::class.java)
                unit.set(context.getBean("storeDirectOrderEntityManagerFactory", EntityManagerFactory::class.java))
                val factory = context.getBean(
                    "&storeDirectOrderEntityManagerFactory",
                    LocalContainerEntityManagerFactoryBean::class.java,
                )
                val model = "com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model."
                assertThat(factory.persistenceUnitInfo!!.managedClassNames).containsExactlyInAnyOrder(
                    model + "BaseEntity", model + "Wishlist", model + "ProductSKU", model + "Product",
                    model + "Category", model + "SKUValue", model + "OptionValue", model + "OptionGroup",
                    model + "Order", model + "OrderItem",
                    "com.pawbridge.backend.store.internal.cart.infrastructure.jpa.model.Cart",
                    "com.pawbridge.backend.store.internal.cart.infrastructure.jpa.model.CartItem",
                )
                assertThat(factory.jpaPropertyMap)
                    .containsEntry("hibernate.boot.allow_jdbc_metadata_access", false)
                    .containsEntry("hibernate.hbm2ddl.auto", "none")
                    .containsEntry("jakarta.persistence.schema-generation.database.action", "none")
                    .containsEntry("hibernate.default_schema", "pawbridge_store")
                val writes = context.getBean("storeDirectOrderWrites", TransactionTemplate::class.java)
                val reads = context.getBean("storeDirectOrderReads", TransactionTemplate::class.java)
                assertThat(writes.propagationBehavior).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED)
                assertThat(writes.isReadOnly).isFalse()
                assertThat(reads.isReadOnly).isTrue()
                val jdbc = context.getBean("storeCatalogManagementSearchJdbc", NamedParameterJdbcTemplate::class.java)
                assertThat(jdbc.jdbcTemplate.queryTimeout).isEqualTo(5)
                terms.set(context.getBean("storeCatalogManagementSearchTerms", KoreanSearchTerms::class.java))
                assertThat(terms.get().document("말티즈")).isNotBlank()
                if (mode == "catalog") {
                    assertThat(context)
                        .doesNotHaveBean(DirectOrderController::class.java)
                        .doesNotHaveBean(OrderReadController::class.java)
                        .doesNotHaveBean(CartController::class.java)
                }
                confirmVerified(writer, storage)
            }
        assertThat(unit.get().isOpen).isFalse()
        assertThatThrownBy { terms.get().document("말티즈") }.isInstanceOf(IllegalStateException::class.java)
        verify(exactly = 1) { storage.close() }
        confirmVerified(storage)
        confirmVerified(writer)
    }

    @ParameterizedTest
    @ValueSource(strings = ["reader-only", "missing-storage"])
    fun `소유 쓰기 DB나 기존 스토리지가 없을 때__카탈로그를 켜면__대체 경로나 외부 접근 없이 실패한다`(
        mode: String,
    ) {
        // given
        val source = mockk<DataSource>()
        val storage = mockk<S3Client>()

        excludeRecords {
            source.hashCode()
            storage.hashCode()
        }

        var runner = runner()
            .withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
            .withBean(PostWriteSettings::class.java, Supplier { settings() })
            .withPropertyValues("pawbridge.store-catalog-management.enabled=true")
        runner = if (mode == "reader-only") {
            runner.withBean("dataSource", DataSource::class.java, Supplier { source })
                .withBean(
                    "postS3Client",
                    S3Client::class.java,
                    Supplier { storage },
                    BeanDefinitionCustomizer { definition: BeanDefinition -> definition.setDestroyMethodName("") },
                )
        } else {
            runner.withBean("writeDataSource", DataSource::class.java, Supplier { source })
                .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { JdbcTemplate(source) })
        }

        // when
        runner.run { context ->
            // then
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasRootCauseInstanceOf(NoSuchBeanDefinitionException::class.java)
                .hasStackTraceContaining(if (mode == "reader-only") "writeDataSource" else "postS3Client")
            confirmVerified(source, storage)
        }
    }

    private fun runner(): ApplicationContextRunner {
        return ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration::class.java))
            .withUserConfiguration(
                StoreCatalogManagementConfiguration::class.java,
                StoreDirectOrderConfiguration::class.java,
                StoreCartConfiguration::class.java,
            )
    }

    private fun writerInputs(source: DataSource): ApplicationContextRunner {
        return runner().withBean("writeDataSource", DataSource::class.java, Supplier { source })
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { JdbcTemplate(source) })
            .withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
    }

    private fun settings(): PostWriteSettings {
        val readerValues = mapOf(
            "PAWBRIDGE_DB_URL" to "jdbc:postgresql://127.0.0.1:1/catalog_unit",
            "PAWBRIDGE_DB_USER" to "catalog_reader",
            "PAWBRIDGE_DB_PASSWORD" to "synthetic-reader",
        )
        ReadDatabaseSettings.fromEnvironment(Function { key -> readerValues[key] })
        val values = mapOf(
            "PAWBRIDGE_JWT_SECRET" to "01234567890123456789012345678901",
            "PAWBRIDGE_STORAGE_ENDPOINT" to "https://r2.invalid",
            "PAWBRIDGE_STORAGE_BUCKET" to "catalog-fixture",
            "PAWBRIDGE_STORAGE_PUBLIC_BASE_URL" to "https://assets.invalid",
            "PAWBRIDGE_STORAGE_OBJECT_PREFIX" to "posts",
            "PAWBRIDGE_STORAGE_ACCESS_KEY_ID" to "synthetic-access",
            "PAWBRIDGE_STORAGE_SECRET_ACCESS_KEY" to "synthetic-secret",
            "PAWBRIDGE_STORAGE_REGION" to "auto",
        )
        return PostWriteSettings.fromEnvironment(Function { key -> values[key] })
    }
}
