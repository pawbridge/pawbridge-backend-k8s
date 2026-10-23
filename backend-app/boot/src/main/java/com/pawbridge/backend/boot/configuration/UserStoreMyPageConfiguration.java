package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.store.api.StoreMemberReads;
import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery;
import com.pawbridge.backend.user.internal.storemypage.application.StoreMyPage;
import com.pawbridge.backend.user.internal.storemypage.infrastructure.ModuleStoreMyPage;
import com.pawbridge.backend.user.internal.storemypage.web.StoreMyPageController;
import com.pawbridge.backend.user.internal.storemypage.web.StoreMyPageExceptionHandler;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "pawbridge.user-store-mypage",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false)
@Import(StoreMemberJpaConfiguration.class)
public class UserStoreMyPageConfiguration {

    @Bean
    JdbcTemplate userStoreModuleJdbc(@Qualifier("dataSource") DataSource source) {
        var jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(5);
        return jdbc;
    }

    @Bean
    TransactionTemplate userStoreModuleReadTransaction(
            @Qualifier("transactionManager") JdbcTransactionManager manager) {
        var reads = new TransactionTemplate(manager);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setTimeout(5);
        return reads;
    }

    @Bean
    StoreMyPage moduleStoreMyPage(
            UserProfileQuery profiles,
            @Qualifier("readOnlyTransaction") TransactionTemplate reads,
            StoreMemberReads store) {
        return new ModuleStoreMyPage(profiles, reads, store);
    }

    @Bean
    StoreMyPageController storeMyPageController(StoreMyPage myPage) {
        return new StoreMyPageController(myPage);
    }

    @Bean
    StoreMyPageExceptionHandler storeMyPageExceptionHandler() {
        return new StoreMyPageExceptionHandler();
    }
}
