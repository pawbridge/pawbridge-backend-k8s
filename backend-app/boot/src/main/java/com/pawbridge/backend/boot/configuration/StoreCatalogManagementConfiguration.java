package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms;
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogCategories;
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogOptions;
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogProducts;
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.S3ProductImages;
import com.pawbridge.backend.store.internal.catalogwrite.web.*;
import jakarta.persistence.EntityManager;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;

/** Opt-in original catalog writes; borrows the existing Store writer and Post-owned R2 client. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="pawbridge.store-catalog-management",name="enabled",havingValue="true")
@Import({StoreJpaWriterConfiguration.class,JpaAuditingConfiguration.class})
public class StoreCatalogManagementConfiguration {
    @Bean
    NamedParameterJdbcTemplate storeCatalogManagementSearchJdbc(@Qualifier("writeDataSource") DataSource writer) {
        var jdbc=new JdbcTemplate(writer);jdbc.setQueryTimeout(5);return new NamedParameterJdbcTemplate(jdbc);
    }

    @Bean(destroyMethod="close")
    KoreanSearchTerms storeCatalogManagementSearchTerms(){return new KoreanSearchTerms();}

    @Bean
    JpaCatalogProducts storeCatalogProducts(@Qualifier("storeDirectOrderEntityManager") EntityManager sharedWriter,
            @Qualifier("storeDirectOrderWrites") TransactionTemplate writes,
            @Qualifier("storeCatalogManagementSearchJdbc") NamedParameterJdbcTemplate search,
            @Qualifier("storeCatalogManagementSearchTerms") KoreanSearchTerms terms) {
        return new JpaCatalogProducts(sharedWriter,writes,search,terms);
    }

    @Bean
    JpaCatalogOptions storeCatalogOptions(@Qualifier("storeDirectOrderEntityManager") EntityManager sharedWriter,
            @Qualifier("storeDirectOrderWrites") TransactionTemplate writes,
            @Qualifier("storeCatalogManagementSearchJdbc") NamedParameterJdbcTemplate search,
            @Qualifier("storeCatalogManagementSearchTerms") KoreanSearchTerms terms) {
        return new JpaCatalogOptions(sharedWriter,writes,search,terms);
    }

    @Bean
    JpaCatalogCategories storeCatalogCategories(@Qualifier("storeDirectOrderEntityManager") EntityManager sharedWriter,
            @Qualifier("storeDirectOrderWrites") TransactionTemplate writes) {
        return new JpaCatalogCategories(sharedWriter,writes);
    }

    @Bean(destroyMethod="")
    S3ProductImages storeProductImages(@Qualifier("postS3Client") S3Client client,PostWriteSettings settings) {
        return new S3ProductImages(client,settings.bucket(),settings.publicBaseUrl());
    }

    @Bean ProductWriteController productWriteController(JpaCatalogProducts products){return new ProductWriteController(products);}
    @Bean OptionManagementController optionManagementController(JpaCatalogOptions options){return new OptionManagementController(options);}
    @Bean ProductImageController productImageController(S3ProductImages images){return new ProductImageController(images);}
    @Bean CategoryManagementController categoryManagementController(JpaCatalogCategories categories){return new CategoryManagementController(categories);}
    @Bean CatalogManagementExceptionHandler catalogManagementExceptionHandler(){return new CatalogManagementExceptionHandler();}
}
