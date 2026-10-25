package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.UserStoreMyPageConfiguration
import com.pawbridge.backend.store.api.StoreMemberReads
import com.pawbridge.backend.store.internal.memberread.infrastructure.JpaStoreMemberReads
import com.pawbridge.backend.support.anyValue
import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery
import com.pawbridge.backend.user.internal.storemypage.application.StoreMyPage
import com.pawbridge.backend.user.internal.storemypage.infrastructure.ModuleStoreMyPage
import com.pawbridge.backend.user.internal.storemypage.web.StoreMyPageController
import com.pawbridge.backend.user.internal.storemypage.web.StoreMyPageExceptionHandler
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource

class UserStoreMyPageConfigurationTest {

    private fun runner(): ApplicationContextRunner {
        return ApplicationContextRunner()
            .withUserConfiguration(UserStoreMyPageConfiguration::class.java, Inputs::class.java, ModuleInputs::class.java)
    }

    @Test
    fun given__no_explicit_enablement__when__starting__then__create_no_store_reader_or_routes() {
        runner().run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBeansOfType(StoreMyPage::class.java)).isEmpty()
            assertThat(context.getBeansOfType(StoreMyPageController::class.java)).isEmpty()
            assertThat(context.getBeansOfType(StoreMyPageExceptionHandler::class.java)).isEmpty()
            assertThat(context.getBeansOfType(StoreMemberReads::class.java)).isEmpty()
            assertThat(context.getBeansOfType(EntityManagerFactory::class.java)).isEmpty()
        }
    }

    @Test
    fun given__explicit_enablement__when__assembling__then__reuse_reader_and_separate_repeatable_read_transaction() {
        runner()
            .withPropertyValues("pawbridge.user-store-mypage.enabled=true")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(StoreMemberReads::class.java)
                assertThat(context.getBean(StoreMyPage::class.java)).isInstanceOf(ModuleStoreMyPage::class.java)
                assertThat(context.getBeansOfType(StoreMyPageController::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(StoreMyPageExceptionHandler::class.java)).hasSize(1)

                val service = context.getBean(StoreMyPage::class.java)
                assertThat(ReflectionTestUtils.getField(service, "profiles"))
                    .isSameAs(context.getBean(UserProfileQuery::class.java))
                assertThat(ReflectionTestUtils.getField(service, "read"))
                    .isSameAs(context.getBean("readOnlyTransaction"))
                assertThat(ReflectionTestUtils.getField(service, "store"))
                    .isSameAs(context.getBean(StoreMemberReads::class.java))

                val jdbc = context.getBean("userStoreModuleJdbc", JdbcTemplate::class.java)
                assertThat(jdbc.dataSource).isSameAs(context.getBean("dataSource"))
                assertThat(jdbc.queryTimeout).isEqualTo(5)
                val reads = context.getBean("userStoreModuleReadTransaction", TransactionTemplate::class.java)
                assertThat(reads.transactionManager).isSameAs(context.getBean("transactionManager"))
                assertThat(reads.isReadOnly).isTrue()
                assertThat(reads.isolationLevel).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ)
                assertThat(reads.timeout).isEqualTo(5)
                assertThat(context.getBeansOfType(DataSource::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(PlatformTransactionManager::class.java)).hasSize(2)

                val storeReads = context.getBean(StoreMemberReads::class.java)
                assertThat(storeReads).isInstanceOf(JpaStoreMemberReads::class.java)
                val jpaReads = ReflectionTestUtils.getField(storeReads, "reads") as TransactionTemplate
                assertThat(jpaReads.transactionManager).isSameAs(context.getBean("storeMemberJpaTransactionManager"))
                assertThat(jpaReads.isReadOnly).isTrue()
                assertThat(jpaReads.isolationLevel).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ)
                assertThat(jpaReads.timeout).isEqualTo(5)

                val factory = context.getBean("storeMemberEntityManagerFactory", EntityManagerFactory::class.java)
                assertThat(factory.metamodel.entities).hasSize(9)
                assertThat(factory.metamodel.entities).allSatisfy { entity ->
                    assertThat(entity.javaType.name)
                        .startsWith("com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.")
                }

                verifyNoConnectionOrTransaction(
                    context.getBean("dataSource", DataSource::class.java),
                    context.getBean("transactionManager", JdbcTransactionManager::class.java),
                )
            }
    }

    private fun verifyNoConnectionOrTransaction(source: DataSource, manager: JdbcTransactionManager) {
        verify(source, never()).getConnection()
        verify(manager, never()).getTransaction(anyValue<TransactionDefinition>())
    }

    @Configuration(proxyBeanMethods = false)
    class ModuleInputs {
        @Bean
        fun dataSource(): DataSource = mock(DataSource::class.java)

        @Bean
        fun transactionManager(): JdbcTransactionManager = mock(JdbcTransactionManager::class.java)
    }

    @Configuration(proxyBeanMethods = false)
    class Inputs {
        @Bean
        fun userProfileQuery(): UserProfileQuery = mock(UserProfileQuery::class.java)

        @Bean
        fun readOnlyTransaction(): TransactionTemplate = mock(TransactionTemplate::class.java)
    }
}
