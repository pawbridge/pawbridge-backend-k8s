package com.pawbridge.storeservice.domain.product.repository;

import com.pawbridge.storeservice.domain.product.entity.Category;
import com.pawbridge.storeservice.domain.product.entity.OptionGroup;
import com.pawbridge.storeservice.domain.product.entity.OptionValue;
import com.pawbridge.storeservice.domain.product.entity.Product;
import com.pawbridge.storeservice.domain.product.entity.ProductSKU;
import com.pawbridge.storeservice.domain.product.entity.SKUValue;
import jakarta.persistence.LockModeType;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ProductRepositoryQuerySyntaxTest {

    @Test
    void givenProductRepositoryQueries_whenHibernateParsesThem_thenAllJpqlIsValid() {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.MySQLDialect")
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
                .applySetting("hibernate.hbm2ddl.auto", "none")
                .build();

        try (SessionFactory sessionFactory = new MetadataSources(registry)
                .addAnnotatedClasses(
                        Product.class,
                        ProductSKU.class,
                        SKUValue.class,
                        OptionGroup.class,
                        OptionValue.class,
                        Category.class
                )
                .buildMetadata()
                .buildSessionFactory()) {
            var entityManager = sessionFactory.createEntityManager();
            try {
                List.of(
                                ProductRepository.class,
                                ProductSKURepository.class,
                                OptionGroupRepository.class,
                                OptionValueRepository.class
                        ).stream()
                        .flatMap(repository -> List.of(repository.getDeclaredMethods()).stream())
                        .map(Method::getDeclaredAnnotations)
                        .flatMap(annotations -> List.of(annotations).stream())
                        .filter(Query.class::isInstance)
                        .map(Query.class::cast)
                        .forEach(query -> assertDoesNotThrow(() -> entityManager.createQuery(query.value())));
            } finally {
                entityManager.close();
            }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    @Test
    void givenStoreLockQueries_whenInspectingAnnotations_thenAllUsePessimisticWrite() {
        List<Method> lockMethods = List.of(
                        ProductRepository.class,
                        ProductSKURepository.class,
                        OptionGroupRepository.class,
                        OptionValueRepository.class
                ).stream()
                .flatMap(repository -> List.of(repository.getDeclaredMethods()).stream())
                .filter(method -> method.getName().endsWith("WithLock"))
                .toList();

        assertEquals(6, lockMethods.size());
        lockMethods.forEach(method -> {
            Lock lock = method.getAnnotation(Lock.class);
            assertEquals(LockModeType.PESSIMISTIC_WRITE, lock.value(), method.toString());
        });
    }
}
