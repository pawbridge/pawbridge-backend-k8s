package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.pawbridge.backend.boot.configuration.ReadDatabaseSettings;
import jakarta.servlet.MultipartConfigElement;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.servlet.MultipartAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.util.unit.DataSize;

class PawBridgeApplicationTest {
    @Test
    void given__missing_settings__when__validating_startup__then__no_default_database_is_selected() {
        assertThatThrownBy(() -> ReadDatabaseSettings.fromEnvironment(Map.<String, String>of()::get))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("F2_JDBC_URL");
    }

    @Test
    void given__explicit_inputs__when__validating_startup__then__preserve_values_and_bound_pool() {
        Map<String, String> inputs = inputs();
        var settings = ReadDatabaseSettings.fromEnvironment(inputs::get);
        assertThat(settings.jdbcUrl()).isEqualTo(inputs.get("F2_JDBC_URL"));
        assertThat(settings.username()).isEqualTo(inputs.get("F2_DB_USER"));
        assertThat(settings.maximumPoolSize()).isEqualTo(2);
        assertThat(settings.toString()).doesNotContain(inputs.get("F2_DB_PASSWORD"));
    }

    @Test
    void given__invalid_endpoint_or_pool__when__validating_startup__then__reject_without_echoing_input() {
        for (String url : new String[]{"jdbc:h2:mem:ignored", "jdbc:postgresql://localhost/pawbridge_f2_ci?password=private-value",
                "jdbc:postgresql://private-value@localhost/pawbridge_f2_ci", "jdbc:postgresql://localhost:0/pawbridge_f2_ci"}) {
            Map<String, String> values = inputs(); values.put("F2_JDBC_URL", url);
            assertThatThrownBy(() -> ReadDatabaseSettings.fromEnvironment(values::get))
                    .hasMessage("Missing or invalid explicit setting: F2_JDBC_URL");
        }
        for (String size : new String[]{"0", "11", "invalid"}) {
            Map<String, String> values = inputs(); values.put("F2_POOL_MAX_SIZE", size);
            assertThatThrownBy(() -> ReadDatabaseSettings.fromEnvironment(values::get))
                    .hasMessage("Missing or invalid explicit setting: F2_POOL_MAX_SIZE");
        }
    }

    @Test
    void given__explicit_candidate_assembly__when__context_starts__then__only_explicit_read_and_write_dependencies_are_assembled() {
        var values = F3TestInputs.values();
        var settings = ReadDatabaseSettings.fromEnvironment(values::get);
        var writeSettings = com.pawbridge.backend.boot.configuration.PostWriteSettings.fromEnvironment(values::get,settings);
        new org.springframework.boot.test.context.runner.WebApplicationContextRunner()
                .withUserConfiguration(PawBridgeApplication.class)
                .withInitializer(context -> {
                    context.getBeanFactory().registerSingleton("readDatabaseSettings", settings);
                    context.getBeanFactory().registerSingleton("postWriteSettings", writeSettings);
                })
                .withPropertyValues("spring.sql.init.mode=never", "spring.jmx.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(javax.sql.DataSource.class)).hasSize(2);
                    assertThat(context.getBeansOfType(org.springframework.transaction.PlatformTransactionManager.class)).hasSize(2);
                    assertThat(context.getBeansOfType(org.springframework.scheduling.TaskScheduler.class)).isEmpty();
                    assertThat(context.getBeansOfType(org.springframework.scheduling.config.ScheduledTaskHolder.class)).isEmpty();
                    assertThat(context.getBeansOfType(com.pawbridge.backend.user.api.UserNicknameQuery.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.user.internal.profile.application.UserProfileQuery.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.user.internal.profile.web.UserProfileController.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.user.internal.profile.web.UserProfileExceptionHandler.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.user.internal.nickname.application.UserNicknameStore.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.user.internal.nickname.application.ChangeUserNickname.class)).hasSize(1);
                    assertThat(org.springframework.aop.support.AopUtils.isAopProxy(context.getBean(com.pawbridge.backend.user.internal.nickname.application.ChangeUserNickname.class))).isTrue();
                    assertThat(context.getBeansOfType(com.pawbridge.backend.user.internal.nickname.web.UserNicknameController.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.user.internal.nickname.web.UserNicknameExceptionHandler.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.comment.application.CommentListReader.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.comment.application.ListComments.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.comment.web.CommentReadController.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.comment.web.CommentReadExceptionHandler.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.comment.application.CommentWriter.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.comment.application.CreateComment.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.comment.web.CommentCreateController.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.comment.web.CommentCreateExceptionHandler.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.comment.application.CommentDeletion.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.comment.web.CommentDeleteController.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.comment.web.CommentDeleteExceptionHandler.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.comment.application.UpdateComment.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.comment.web.CommentUpdateController.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.comment.web.CommentUpdateExceptionHandler.class)).hasSize(1);
                    assertThat(context.getBean(com.pawbridge.backend.community.internal.comment.application.CommentReader.class))
                            .isSameAs(context.getBean(com.pawbridge.backend.community.internal.comment.application.CommentListReader.class));
                    assertThat(context.getBean(com.pawbridge.backend.community.internal.comment.application.CommentModification.class))
                            .isSameAs(context.getBean(com.pawbridge.backend.community.internal.comment.application.CommentWriter.class));
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.post.application.ListPosts.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.post.application.SearchPosts.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.post.application.PostSearchReader.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.post.web.PostSearchController.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.post.web.PostSearchExceptionHandler.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.post.application.PostReader.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.post.application.PostListReader.class)).hasSize(1);
                    assertThat(context.getBean(com.pawbridge.backend.community.internal.post.application.PostReader.class))
                            .isSameAs(context.getBean(com.pawbridge.backend.community.internal.post.application.PostListReader.class));
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.post.web.PostReadController.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.post.application.DeletePost.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.post.application.PostMediaRemover.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.post.application.PostDeletion.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.post.web.PostDeleteController.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.post.application.UpdatePost.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.post.application.PostModification.class)).hasSize(1);
                    assertThat(context.getBeansOfType(com.pawbridge.backend.community.internal.post.web.PostUpdateController.class)).hasSize(1);
                    var pools = context.getBeansOfType(com.zaxxer.hikari.HikariDataSource.class);
                    assertThat(pools.get("dataSource").isReadOnly()).isTrue();
                    assertThat(pools.get("dataSource").getMaximumPoolSize()).isEqualTo(2);
                    assertThat(pools.get("writeDataSource").isReadOnly()).isFalse();
                    assertThat(pools.get("writeDataSource").getMaximumPoolSize()).isEqualTo(1);
                    var readJdbc = context.getBean("readJdbc", org.springframework.jdbc.core.JdbcTemplate.class);
                    var searchJdbc = context.getBean("searchJdbc", org.springframework.jdbc.core.JdbcTemplate.class);
                    assertThat(readJdbc.getQueryTimeout()).isEqualTo(3);
                    assertThat(searchJdbc.getQueryTimeout()).isEqualTo(5);
                    assertThat(searchJdbc.getDataSource()).isSameAs(pools.get("dataSource"));
                    assertThat(readJdbc.getDataSource()).isSameAs(searchJdbc.getDataSource());
                    var manager = context.getBean("transactionManager", org.springframework.jdbc.support.JdbcTransactionManager.class);
                    assertThat(manager.isEnforceReadOnly()).isTrue();
                    var readTransaction = context.getBean("readOnlyTransaction", org.springframework.transaction.support.TransactionTemplate.class);
                    var searchTransaction = context.getBean("searchReadOnlyTransaction", org.springframework.transaction.support.TransactionTemplate.class);
                    assertThat(readTransaction.getTimeout()).isEqualTo(3);
                    assertThat(searchTransaction.getTimeout()).isEqualTo(5);
                    assertThat(readTransaction.isReadOnly()).isTrue();
                    assertThat(searchTransaction.isReadOnly()).isTrue();
                    assertThat(searchTransaction.getTransactionManager()).isSameAs(manager);
                    assertThat(readTransaction.getTransactionManager()).isSameAs(manager);
                    pools.values().forEach(pool -> {
                        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
                        assertThat(pool.getHikariPoolMXBean().getTotalConnections()).isZero();
                    });
                });
    }

    @Test
    void given__candidate_multipart_configuration__when__binding_servlet_configuration__then__preserve_file_and_request_limits() {
        new WebApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(MultipartAutoConfiguration.class))
                .withPropertyValues("spring.config.location=classpath:/application.properties")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(MultipartProperties.class);
                    assertThat(properties.getMaxFileSize()).isEqualTo(DataSize.ofMegabytes(100));
                    assertThat(properties.getMaxRequestSize()).isEqualTo(DataSize.ofMegabytes(200));
                    var servletConfig = context.getBean(MultipartConfigElement.class);
                    assertThat(servletConfig.getMaxFileSize()).isEqualTo(DataSize.ofMegabytes(100).toBytes());
                    assertThat(servletConfig.getMaxRequestSize()).isEqualTo(DataSize.ofMegabytes(200).toBytes());
                });
    }

    private static Map<String, String> inputs() {
        return new HashMap<>(Map.of("F2_JDBC_URL", "jdbc:postgresql://127.0.0.1:5432/pawbridge_f2_ci",
                "F2_DB_USER", "fixture-reader", "F2_DB_PASSWORD", "synthetic-only"));
    }
}
