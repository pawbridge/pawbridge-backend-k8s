package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.any;

import com.pawbridge.backend.boot.configuration.UserStoreMyPageConfiguration;
import com.pawbridge.backend.store.api.StoreMemberReads;
import com.pawbridge.backend.store.internal.memberread.infrastructure.JpaStoreMemberReads;
import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery;
import com.pawbridge.backend.user.internal.storemypage.application.StoreMyPage;
import com.pawbridge.backend.user.internal.storemypage.infrastructure.ModuleStoreMyPage;
import com.pawbridge.backend.user.internal.storemypage.web.StoreMyPageController;
import com.pawbridge.backend.user.internal.storemypage.web.StoreMyPageExceptionHandler;
import jakarta.persistence.EntityManagerFactory;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class UserStoreMyPageConfigurationTest {

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(UserStoreMyPageConfiguration.class, Inputs.class, ModuleInputs.class);
    }

    @Test
    void given__no_explicit_enablement__when__starting__then__create_no_store_reader_or_routes() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(StoreMyPage.class)).isEmpty();
            assertThat(context.getBeansOfType(StoreMyPageController.class)).isEmpty();
            assertThat(context.getBeansOfType(StoreMyPageExceptionHandler.class)).isEmpty();
            assertThat(context.getBeansOfType(StoreMemberReads.class)).isEmpty();
            assertThat(context.getBeansOfType(EntityManagerFactory.class)).isEmpty();
        });
    }

    @Test
    void given__explicit_enablement__when__assembling__then__reuse_reader_and_separate_repeatable_read_transaction() {
        runner()
                .withPropertyValues("pawbridge.user-store-mypage.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(StoreMemberReads.class);
                    assertThat(context.getBean(StoreMyPage.class)).isInstanceOf(ModuleStoreMyPage.class);
                    assertThat(context.getBeansOfType(StoreMyPageController.class)).hasSize(1);
                    assertThat(context.getBeansOfType(StoreMyPageExceptionHandler.class)).hasSize(1);

                    var service = context.getBean(StoreMyPage.class);
                    assertThat(ReflectionTestUtils.getField(service, "profiles"))
                            .isSameAs(context.getBean(UserProfileQuery.class));
                    assertThat(ReflectionTestUtils.getField(service, "read"))
                            .isSameAs(context.getBean("readOnlyTransaction"));
                    assertThat(ReflectionTestUtils.getField(service, "store"))
                            .isSameAs(context.getBean(StoreMemberReads.class));

                    var jdbc = context.getBean("userStoreModuleJdbc", JdbcTemplate.class);
                    assertThat(jdbc.getDataSource()).isSameAs(context.getBean("dataSource"));
                    assertThat(jdbc.getQueryTimeout()).isEqualTo(5);
                    var reads = context.getBean("userStoreModuleReadTransaction", TransactionTemplate.class);
                    assertThat(reads.getTransactionManager()).isSameAs(context.getBean("transactionManager"));
                    assertThat(reads.isReadOnly()).isTrue();
                    assertThat(reads.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                    assertThat(reads.getTimeout()).isEqualTo(5);
                    assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
                    assertThat(context.getBeansOfType(PlatformTransactionManager.class)).hasSize(2);

                    var storeReads = context.getBean(StoreMemberReads.class);
                    assertThat(storeReads).isInstanceOf(JpaStoreMemberReads.class);
                    var jpaReads = (TransactionTemplate) ReflectionTestUtils.getField(storeReads, "reads");
                    assertThat(jpaReads.getTransactionManager())
                            .isSameAs(context.getBean("storeMemberJpaTransactionManager"));
                    assertThat(jpaReads.isReadOnly()).isTrue();
                    assertThat(jpaReads.getIsolationLevel())
                            .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                    assertThat(jpaReads.getTimeout()).isEqualTo(5);

                    var factory = context.getBean("storeMemberEntityManagerFactory", EntityManagerFactory.class);
                    assertThat(factory.getMetamodel().getEntities()).hasSize(9);
                    assertThat(factory.getMetamodel().getEntities()).allSatisfy(entity ->
                            assertThat(entity.getJavaType().getName())
                                    .startsWith("com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model."));

                    verifyNoConnectionOrTransaction(context.getBean("dataSource", DataSource.class),
                            context.getBean("transactionManager", JdbcTransactionManager.class));
                });
    }

    private static void verifyNoConnectionOrTransaction(DataSource source, JdbcTransactionManager manager) {
        try {
            verify(source, never()).getConnection();
        } catch (SQLException impossible) {
            throw new AssertionError(impossible);
        }
        verify(manager, never()).getTransaction(any());
    }

    @Configuration(proxyBeanMethods = false)
    static class ModuleInputs {
        @Bean
        DataSource dataSource() {
            return mock(DataSource.class);
        }

        @Bean
        JdbcTransactionManager transactionManager() {
            return mock(JdbcTransactionManager.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Inputs {
        @Bean
        UserProfileQuery userProfileQuery() {
            return mock(UserProfileQuery.class);
        }

        @Bean
        TransactionTemplate readOnlyTransaction() {
            return mock(TransactionTemplate.class);
        }
    }
}
