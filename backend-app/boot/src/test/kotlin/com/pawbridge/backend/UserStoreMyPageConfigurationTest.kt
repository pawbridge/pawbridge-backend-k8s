package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.UserStoreMyPageConfiguration
import com.pawbridge.backend.store.api.StoreMemberReads
import com.pawbridge.backend.store.internal.memberread.infrastructure.JpaStoreMemberReads
import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery
import com.pawbridge.backend.user.internal.storemypage.application.StoreMyPage
import com.pawbridge.backend.user.internal.storemypage.infrastructure.ModuleStoreMyPage
import com.pawbridge.backend.user.internal.storemypage.web.StoreMyPageController
import com.pawbridge.backend.user.internal.storemypage.web.StoreMyPageExceptionHandler
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
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
    fun `명시적 활성화가 없을 때__시작하면__스토어 리더도 경로도 만들지 않는다`() {
        // given
        val runner = runner()

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
            assertThat(context.getBeansOfType(StoreMyPage::class.java)).isEmpty()
            assertThat(context.getBeansOfType(StoreMyPageController::class.java)).isEmpty()
            assertThat(context.getBeansOfType(StoreMyPageExceptionHandler::class.java)).isEmpty()
            assertThat(context.getBeansOfType(StoreMemberReads::class.java)).isEmpty()
            assertThat(context.getBeansOfType(EntityManagerFactory::class.java)).isEmpty()
        }
    }

    @Test
    fun `명시적 활성화가 주어졌을 때__조립하면__리더를 재사용하고 반복 읽기 트랜잭션을 분리한다`() {
        // given
        val runner = runner().withPropertyValues("pawbridge.user-store-mypage.enabled=true")

        // when
        runner
            .run { context ->
                // then
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
        verify(exactly = 0) { source.connection }
        verify(exactly = 0) { manager.getTransaction(any()) }
    }

    @Configuration(proxyBeanMethods = false)
    class ModuleInputs {
        @Bean
        fun dataSource(): DataSource = mockk<DataSource>()

        @Bean
        fun transactionManager(): JdbcTransactionManager {
            val manager = mockk<JdbcTransactionManager>()
            // Spring runs this lifecycle callback when the bean is created; nothing else touches the manager.
            justRun { manager.afterPropertiesSet() }
            return manager
        }
    }

    @Configuration(proxyBeanMethods = false)
    class Inputs {
        @Bean
        fun userProfileQuery(): UserProfileQuery = mockk<UserProfileQuery>()

        @Bean
        fun readOnlyTransaction(): TransactionTemplate {
            val template = mockk<TransactionTemplate>()
            // Spring runs this lifecycle callback when the bean is created; nothing else touches the template.
            justRun { template.afterPropertiesSet() }
            return template
        }
    }
}
