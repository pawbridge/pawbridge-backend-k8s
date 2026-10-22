package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.community.internal.comment.application.CommentListReader;
import com.pawbridge.backend.community.internal.comment.application.ListComments;
import com.pawbridge.backend.community.internal.comment.infrastructure.JdbcCommentReader;
import com.pawbridge.backend.community.internal.comment.web.CommentReadController;
import com.pawbridge.backend.community.internal.comment.web.CommentReadExceptionHandler;
import com.pawbridge.backend.community.internal.post.application.ListPosts;
import com.pawbridge.backend.community.internal.post.application.PostListReader;
import com.pawbridge.backend.community.internal.post.application.PostReader;
import com.pawbridge.backend.community.internal.post.application.PostSearchReader;
import com.pawbridge.backend.community.internal.post.application.ReadPost;
import com.pawbridge.backend.community.internal.post.application.SearchPosts;
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostReader;
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostSearchReader;
import com.pawbridge.backend.community.internal.post.web.PostReadController;
import com.pawbridge.backend.community.internal.post.web.PostReadExceptionHandler;
import com.pawbridge.backend.community.internal.post.web.PostSearchController;
import com.pawbridge.backend.community.internal.post.web.PostSearchExceptionHandler;
import com.pawbridge.backend.user.api.UserNicknameQuery;
import com.pawbridge.backend.user.internal.nickname.infrastructure.JdbcUserNicknameQuery;
import com.zaxxer.hikari.HikariConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@Import(ReadSecurityConfiguration.class)
public class ReadConfiguration {
    /**
     * The monolith's only connection pool. The historical reader/writer bean names stay as aliases so
     * every JdbcTemplate, transaction manager and EntityManagerFactory resolves this same instance.
     * Read-only protection is applied per transaction (readOnly templates + enforceReadOnly), not per pool.
     */
    @Bean(name = {"dataSource", "writeDataSource", "storeWishlistWriteDataSource"}, destroyMethod = "close")
    HikariDataSource dataSource(ReadDatabaseSettings settings) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(settings.jdbcUrl());
        config.setUsername(settings.username());
        config.setPassword(settings.password());
        config.setPoolName("pawbridge-db");
        config.setMaximumPoolSize(settings.maximumPoolSize());
        config.setMinimumIdle(Math.min(2, settings.maximumPoolSize()));
        config.setConnectionTimeout(5000);
        config.setValidationTimeout(1000);
        config.setInitializationFailTimeout(-1);
        config.setReadOnly(false);
        return new HikariDataSource(config);
    }

    @Bean
    JdbcTemplate readJdbc(@Qualifier("dataSource") DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(3);
        return jdbc;
    }

    @Bean
    JdbcTransactionManager transactionManager(@Qualifier("dataSource") DataSource dataSource) {
        JdbcTransactionManager manager = new JdbcTransactionManager(dataSource);
        manager.setEnforceReadOnly(true);
        return manager;
    }

    @Bean
    TransactionTemplate readOnlyTransaction(@Qualifier("transactionManager") JdbcTransactionManager manager) {
        TransactionTemplate transaction = new TransactionTemplate(manager);
        transaction.setReadOnly(true);
        transaction.setTimeout(3);
        return transaction;
    }

    @Bean
    JdbcTemplate searchJdbc(@Qualifier("dataSource") DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(5);
        return jdbc;
    }

    @Bean
    TransactionTemplate searchReadOnlyTransaction(@Qualifier("transactionManager") JdbcTransactionManager manager) {
        TransactionTemplate transaction = new TransactionTemplate(manager);
        transaction.setReadOnly(true);
        transaction.setTimeout(5);
        return transaction;
    }

    @Bean
    UserNicknameQuery userNicknameQuery(@Qualifier("readJdbc") JdbcTemplate readJdbc, @Qualifier("readOnlyTransaction") TransactionTemplate readOnlyTransaction) {
        return new JdbcUserNicknameQuery(readJdbc, readOnlyTransaction);
    }

    @Bean
    JdbcPostReader postReader(@Qualifier("readJdbc") JdbcTemplate readJdbc, @Qualifier("readOnlyTransaction") TransactionTemplate readOnlyTransaction, ObjectMapper mapper) {
        return new JdbcPostReader(readJdbc, readOnlyTransaction, mapper);
    }

    @Bean
    ReadPost readPost(PostReader posts, UserNicknameQuery users) { return new ReadPost(posts, users); }

    @Bean
    ListPosts listPosts(PostListReader posts, UserNicknameQuery users) { return new ListPosts(posts, users); }

    @Bean
    PostReadController postReadController(ReadPost readPost, ListPosts listPosts) { return new PostReadController(readPost, listPosts); }

    @Bean
    PostReadExceptionHandler postReadExceptionHandler() { return new PostReadExceptionHandler(); }

    @Bean
    JdbcCommentReader commentReader(@Qualifier("readJdbc") JdbcTemplate readJdbc,
                                   @Qualifier("readOnlyTransaction") TransactionTemplate readOnlyTransaction) {
        return new JdbcCommentReader(readJdbc, readOnlyTransaction);
    }

    @Bean
    ListComments listComments(CommentListReader comments, UserNicknameQuery users) { return new ListComments(comments, users); }

    @Bean
    CommentReadController commentReadController(ListComments listComments) { return new CommentReadController(listComments); }

    @Bean
    CommentReadExceptionHandler commentReadExceptionHandler() { return new CommentReadExceptionHandler(); }

    @Bean(destroyMethod = "close")
    JdbcPostSearchReader postSearchReader(@Qualifier("searchJdbc") JdbcTemplate searchJdbc,
                                        @Qualifier("searchReadOnlyTransaction") TransactionTemplate searchReadOnlyTransaction,
                                        ObjectMapper mapper) {
        return new JdbcPostSearchReader(searchJdbc, searchReadOnlyTransaction, mapper);
    }

    @Bean
    SearchPosts searchPosts(PostSearchReader posts, UserNicknameQuery users) { return new SearchPosts(posts, users); }

    @Bean
    PostSearchController postSearchController(SearchPosts searchPosts) { return new PostSearchController(searchPosts); }

    @Bean
    PostSearchExceptionHandler postSearchExceptionHandler() { return new PostSearchExceptionHandler(); }
}
