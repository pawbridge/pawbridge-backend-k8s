package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.command.infrastructure.mapper.AnimalMapper;
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository;
import com.pawbridge.backend.animal.internal.lostsearch.infrastructure.LostSearchService;
import com.pawbridge.backend.animal.internal.lostsearch.infrastructure.PythonLostSearchClient;
import com.pawbridge.backend.animal.internal.lostsearch.web.LostSearchController;
import jakarta.persistence.EntityManagerFactory;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/** Opt-in read-only persistence; inference completes before the service opens its short read transaction.
 * Public activation additionally requires the bounded upload admission documented in the runbook. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="pawbridge.animal-lost-search",name="enabled",havingValue="true")
public class AnimalLostSearchConfiguration {
    private static final String MODEL="com.pawbridge.backend.animal.internal.command.infrastructure.entity.";
    @Bean
    LocalContainerEntityManagerFactoryBean animalLostSearchEntityManagerFactory(@Qualifier("dataSource") DataSource source) {
        var factory=new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(source);
        factory.setPersistenceUnitName("pawbridge-animal-lost-search-reader");
        factory.setManagedTypes(PersistenceManagedTypes.of(MODEL+"Animal",MODEL+"Shelter",MODEL+"BaseTimeEntity"));
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.dialect","org.hibernate.dialect.PostgreSQLDialect",
                "hibernate.boot.allow_jdbc_metadata_access",false,"hibernate.hbm2ddl.auto","none",
                "jakarta.persistence.schema-generation.database.action","none","hibernate.default_schema","pawbridge_animal",
                "hibernate.jdbc.time_zone","UTC","hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
        return factory;
    }
    @Bean
    JpaTransactionManager animalLostSearchTransactions(@Qualifier("animalLostSearchEntityManagerFactory") EntityManagerFactory factory,
            @Qualifier("dataSource") DataSource source) {
        var transactions=new JpaTransactionManager(factory);
        transactions.setDataSource(source);transactions.setDefaultTimeout(5);
        return transactions;
    }
    @Bean
    PythonLostSearchClient animalLostSearchPython(@Value("${lost-search.python-url:${python-ai-service.url}}") String url,ObjectMapper mapper) {
        return new PythonLostSearchClient(url,mapper);
    }
    @Bean
    LostSearchService animalLostSearchService(@Qualifier("animalLostSearchPython") PythonLostSearchClient client,
            @Qualifier("animalLostSearchEntityManagerFactory") EntityManagerFactory factory,
            @Value("${python-ai-service.internal-api-key:}") String key,
            @Qualifier("animalLostSearchTransactions") JpaTransactionManager transactions) {
        var animals=new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory)).getRepository(AnimalRepository.class);
        return new LostSearchService(client,animals,new AnimalMapper(),key,transactions);
    }
    @Bean
    LostSearchController animalLostSearchController(LostSearchService service) { return new LostSearchController(service); }
}
