package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery;
import com.pawbridge.backend.user.internal.storemypage.application.StoreMyPage;
import com.pawbridge.backend.user.internal.storemypage.infrastructure.HttpStoreMyPage;
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
    @Bean(destroyMethod = "close")
    HttpClient userStoreHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Bean
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
    StoreMyPageController storeMyPageController(StoreMyPage myPage) { return new StoreMyPageController(myPage); }

    @Bean
    StoreMyPageExceptionHandler storeMyPageExceptionHandler() { return new StoreMyPageExceptionHandler(); }
}
