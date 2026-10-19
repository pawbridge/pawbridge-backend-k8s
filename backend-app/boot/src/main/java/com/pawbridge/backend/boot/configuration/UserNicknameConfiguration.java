package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.user.internal.nickname.application.ChangeUserNickname;
import com.pawbridge.backend.user.internal.nickname.application.UserNicknameStore;
import com.pawbridge.backend.user.internal.nickname.infrastructure.JdbcUserNicknameStore;
import com.pawbridge.backend.user.internal.nickname.web.UserNicknameController;
import com.pawbridge.backend.user.internal.nickname.web.UserNicknameExceptionHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(proxyTargetClass = true)
public class UserNicknameConfiguration {
    @Bean
    UserNicknameStore userNicknameStore(@Qualifier("writeJdbc") JdbcTemplate jdbc) {
        return new JdbcUserNicknameStore(jdbc);
    }

    @Bean
    ChangeUserNickname changeUserNickname(UserNicknameStore users) { return new ChangeUserNickname(users); }

    @Bean
    UserNicknameController userNicknameController(ChangeUserNickname change) { return new UserNicknameController(change); }

    @Bean
    UserNicknameExceptionHandler userNicknameExceptionHandler() { return new UserNicknameExceptionHandler(); }
}
