package com.pawbridge.backend.boot.configuration

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
import jakarta.persistence.EntityManager
import javax.sql.DataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import software.amazon.awssdk.services.s3.S3Client

/** Opt-in original catalog writes; borrows the existing Store writer and Post-owned R2 client. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.store-catalog-management", name = ["enabled"], havingValue = "true")
@Import(StoreJpaWriterConfiguration::class, JpaAuditingConfiguration::class)
class StoreCatalogManagementConfiguration {
    @Bean
    fun storeCatalogManagementSearchJdbc(
        @Qualifier("writeDataSource") writer: DataSource,
    ): NamedParameterJdbcTemplate {
        val jdbc = JdbcTemplate(writer)
        jdbc.queryTimeout = 5
        return NamedParameterJdbcTemplate(jdbc)
    }

    @Bean(destroyMethod = "close")
    fun storeCatalogManagementSearchTerms(): KoreanSearchTerms {
        return KoreanSearchTerms()
    }

    @Bean
    fun storeCatalogProducts(
        @Qualifier("storeDirectOrderEntityManager") sharedWriter: EntityManager,
        @Qualifier("storeDirectOrderWrites") writes: TransactionTemplate,
        @Qualifier("storeCatalogManagementSearchJdbc") search: NamedParameterJdbcTemplate,
        @Qualifier("storeCatalogManagementSearchTerms") terms: KoreanSearchTerms,
    ): JpaCatalogProducts {
        return JpaCatalogProducts(sharedWriter, writes, search, terms)
    }

    @Bean
    fun storeCatalogOptions(
        @Qualifier("storeDirectOrderEntityManager") sharedWriter: EntityManager,
        @Qualifier("storeDirectOrderWrites") writes: TransactionTemplate,
        @Qualifier("storeCatalogManagementSearchJdbc") search: NamedParameterJdbcTemplate,
        @Qualifier("storeCatalogManagementSearchTerms") terms: KoreanSearchTerms,
    ): JpaCatalogOptions {
        return JpaCatalogOptions(sharedWriter, writes, search, terms)
    }

    @Bean
    fun storeCatalogCategories(
        @Qualifier("storeDirectOrderEntityManager") sharedWriter: EntityManager,
        @Qualifier("storeDirectOrderWrites") writes: TransactionTemplate,
    ): JpaCatalogCategories {
        return JpaCatalogCategories(sharedWriter, writes)
    }

    @Bean(destroyMethod = "")
    fun storeProductImages(@Qualifier("postS3Client") client: S3Client, settings: PostWriteSettings): S3ProductImages {
        return S3ProductImages(client, settings.bucket(), settings.publicBaseUrl())
    }

    @Bean
    fun productWriteController(products: JpaCatalogProducts): ProductWriteController {
        return ProductWriteController(products)
    }

    @Bean
    fun optionManagementController(options: JpaCatalogOptions): OptionManagementController {
        return OptionManagementController(options)
    }

    @Bean
    fun productImageController(images: S3ProductImages): ProductImageController {
        return ProductImageController(images)
    }

    @Bean
    fun categoryManagementController(categories: JpaCatalogCategories): CategoryManagementController {
        return CategoryManagementController(categories)
    }

    @Bean
    fun catalogManagementExceptionHandler(): CatalogManagementExceptionHandler {
        return CatalogManagementExceptionHandler()
    }
}
