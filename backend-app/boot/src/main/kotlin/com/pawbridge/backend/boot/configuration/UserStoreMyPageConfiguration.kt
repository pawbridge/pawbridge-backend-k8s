package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.store.api.StoreMemberReads
import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery
import com.pawbridge.backend.user.internal.storemypage.application.StoreMyPage
import com.pawbridge.backend.user.internal.storemypage.infrastructure.ModuleStoreMyPage
import com.pawbridge.backend.user.internal.storemypage.web.StoreMyPageController
import com.pawbridge.backend.user.internal.storemypage.web.StoreMyPageExceptionHandler
import javax.sql.DataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "pawbridge.user-store-mypage",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
@Import(StoreMemberJpaConfiguration::class)
class UserStoreMyPageConfiguration {
    @Bean
    fun userStoreModuleJdbc(@Qualifier("dataSource") source: DataSource): JdbcTemplate {
        val jdbc = JdbcTemplate(source)
        jdbc.queryTimeout = 5
        return jdbc
    }

    @Bean
    fun userStoreModuleReadTransaction(
        @Qualifier("transactionManager") manager: JdbcTransactionManager,
    ): TransactionTemplate {
        val reads = TransactionTemplate(manager)
        reads.isReadOnly = true
        reads.isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        reads.timeout = 5
        return reads
    }

    @Bean
    fun moduleStoreMyPage(
        profiles: UserProfileQuery,
        @Qualifier("readOnlyTransaction") reads: TransactionTemplate,
        store: StoreMemberReads,
    ): StoreMyPage {
        return ModuleStoreMyPage(profiles, reads, store)
    }

    @Bean
    fun storeMyPageController(myPage: StoreMyPage): StoreMyPageController {
        return StoreMyPageController(myPage)
    }

    @Bean
    fun storeMyPageExceptionHandler(): StoreMyPageExceptionHandler {
        return StoreMyPageExceptionHandler()
    }
}
