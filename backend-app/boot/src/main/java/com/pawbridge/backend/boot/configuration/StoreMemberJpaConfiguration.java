package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.store.api.StoreMemberReads;
import com.pawbridge.backend.store.internal.memberread.infrastructure.JdbcStoreMemberReads;
import com.pawbridge.backend.store.internal.memberread.infrastructure.JpaStoreMemberReads;
import jakarta.persistence.EntityManagerFactory;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Imported only through the enabled MyPage feature; no global entity or repository scan. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.user-store-mypage", name = "backend", havingValue = "module")
class StoreMemberJpaConfiguration {
    static final String MODEL = "com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.";
    static final String[] MANAGED_TYPES = {MODEL + "BaseEntity", MODEL + "Wishlist", MODEL + "ProductSKU",
            MODEL + "Product", MODEL + "Category", MODEL + "SKUValue", MODEL + "OptionValue", MODEL + "OptionGroup",
            MODEL + "Order", MODEL + "OrderItem"};

    @Bean
    LocalContainerEntityManagerFactoryBean storeMemberEntityManagerFactory(@Qualifier("dataSource") DataSource source) {
        return newFactory(source, "pawbridge-store-member-reader", MANAGED_TYPES);
    }

    static LocalContainerEntityManagerFactoryBean newFactory(DataSource source, String unitName, String... managedClassNames) {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(source);
        factory.setPersistenceUnitName(unitName);
        factory.setManagedTypes(PersistenceManagedTypes.of(managedClassNames));
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of(
                "hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect",
                "hibernate.boot.allow_jdbc_metadata_access", false,
                "hibernate.hbm2ddl.auto", "none",
                "jakarta.persistence.schema-generation.database.action", "none",
                "hibernate.default_schema", "pawbridge_store",
                "hibernate.jdbc.time_zone", "UTC",
                "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
        return factory;
    }

    @Bean
    JpaTransactionManager storeMemberJpaTransactionManager(
            @Qualifier("storeMemberEntityManagerFactory") EntityManagerFactory factory,
            @Qualifier("dataSource") DataSource source) {
        var manager = new JpaTransactionManager(factory);
        manager.setDataSource(source);
        return manager;
    }

    @Bean
    StoreMemberReads storeMemberReads(@Qualifier("storeMemberEntityManagerFactory") EntityManagerFactory factory,
            @Qualifier("storeMemberJpaTransactionManager") JpaTransactionManager manager,
            @Qualifier("userStoreModuleJdbc") JdbcTemplate jdbc,
            @Qualifier("userStoreModuleReadTransaction") TransactionTemplate cartReads) {
        var reads = new TransactionTemplate(manager);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setTimeout(5);
        return new JpaStoreMemberReads(SharedEntityManagerCreator.createSharedEntityManager(factory), reads,
                new JdbcStoreMemberReads(new NamedParameterJdbcTemplate(jdbc), cartReads));
    }
}
