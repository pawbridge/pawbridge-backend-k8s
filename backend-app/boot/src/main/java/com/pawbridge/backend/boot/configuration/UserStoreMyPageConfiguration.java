package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery;
import com.pawbridge.backend.user.internal.storemypage.application.StoreMyPage;
import com.pawbridge.backend.user.internal.storemypage.infrastructure.HttpStoreMyPage;
import com.pawbridge.backend.user.internal.storemypage.infrastructure.ModuleStoreMyPage;
import com.pawbridge.backend.store.api.StoreMemberReads;
import com.pawbridge.backend.store.internal.memberread.infrastructure.JdbcStoreMemberReads;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import com.pawbridge.backend.user.internal.storemypage.web.StoreMyPageController;
import com.pawbridge.backend.user.internal.storemypage.web.StoreMyPageExceptionHandler;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.user-store-mypage", name = "enabled", havingValue = "true", matchIfMissing = false)
public class UserStoreMyPageConfiguration {
    public UserStoreMyPageConfiguration(@Value("${pawbridge.user-store-mypage.backend:http}") String backend) {
        if (!backend.equals("http") && !backend.equals("module")) {
            throw new IllegalArgumentException("Unknown User Store MyPage backend");
        }
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "pawbridge.user-store-mypage", name = "backend", havingValue = "http", matchIfMissing = true)
    HttpClient userStoreHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "pawbridge.user-store-mypage", name = "backend", havingValue = "http", matchIfMissing = true)
    StoreMyPage storeMyPage(UserProfileQuery profiles,
            @Qualifier("readOnlyTransaction") TransactionTemplate transaction,
            @Qualifier("userStoreHttpClient") HttpClient client,
            @Value("${service.store.url:}") String address) {
        URI origin;
        try {
            origin = URI.create(address);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("An explicit Store HTTP origin is required");
        }
        if (!origin.isAbsolute() || !("http".equals(origin.getScheme()) || "https".equals(origin.getScheme()))
                || origin.getHost() == null || origin.getUserInfo() != null || origin.getQuery() != null
                || origin.getFragment() != null || (!origin.getPath().isEmpty() && !origin.getPath().equals("/"))) {
            throw new IllegalArgumentException("An explicit Store HTTP origin is required");
        }
        var requests = new JdkClientHttpRequestFactory(client);
        requests.setReadTimeout(Duration.ofSeconds(60));
        return new HttpStoreMyPage(profiles, transaction,
                RestClient.builder().baseUrl(origin.toString()).requestFactory(requests).build());
    }

    @Bean
    @ConditionalOnProperty(prefix = "pawbridge.user-store-mypage", name = "backend", havingValue = "module")
    JdbcTemplate userStoreModuleJdbc(@Qualifier("dataSource") DataSource source) {
        var jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(5);
        return jdbc;
    }

    @Bean
    @ConditionalOnProperty(prefix = "pawbridge.user-store-mypage", name = "backend", havingValue = "module")
    TransactionTemplate userStoreModuleReadTransaction(@Qualifier("transactionManager") JdbcTransactionManager manager) {
        var reads = new TransactionTemplate(manager);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setTimeout(5);
        return reads;
    }

    @Bean
    @ConditionalOnProperty(prefix = "pawbridge.user-store-mypage", name = "backend", havingValue = "module")
    StoreMemberReads storeMemberReads(@Qualifier("userStoreModuleJdbc") JdbcTemplate jdbc,
            @Qualifier("userStoreModuleReadTransaction") TransactionTemplate reads) {
        return new JdbcStoreMemberReads(new NamedParameterJdbcTemplate(jdbc), reads);
    }

    @Bean
    @ConditionalOnProperty(prefix = "pawbridge.user-store-mypage", name = "backend", havingValue = "module")
    StoreMyPage moduleStoreMyPage(UserProfileQuery profiles,
            @Qualifier("readOnlyTransaction") TransactionTemplate reads, StoreMemberReads store) {
        return new ModuleStoreMyPage(profiles, reads, store);
    }

    @Bean
    StoreMyPageController storeMyPageController(StoreMyPage myPage) { return new StoreMyPageController(myPage); }

    @Bean
    StoreMyPageExceptionHandler storeMyPageExceptionHandler() { return new StoreMyPageExceptionHandler(); }
}
