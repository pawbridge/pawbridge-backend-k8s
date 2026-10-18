package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.boot.security.PostTokenAuthenticator;
import com.pawbridge.backend.community.internal.post.application.CreatePost;
import com.pawbridge.backend.community.internal.post.application.PostMediaUploader;
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostWriter;
import com.pawbridge.backend.community.internal.post.infrastructure.media.S3PostMediaUploader;
import com.pawbridge.backend.community.internal.post.web.PostCreateController;
import com.pawbridge.backend.community.internal.post.web.PostCreateExceptionHandler;
import com.pawbridge.backend.user.api.UserNicknameQuery;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Configuration(proxyBeanMethods = false)
public class PostWriteConfiguration {
    @Bean
    PostTokenAuthenticator postTokenAuthenticator(PostWriteSettings settings) {
        return new PostTokenAuthenticator(settings.jwtKey());
    }

    @Bean(destroyMethod = "close")
    HikariDataSource writeDataSource(ReadDatabaseSettings reader, PostWriteSettings writer) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(reader.jdbcUrl()); config.setUsername(writer.username()); config.setPassword(writer.password());
        config.setPoolName("pawbridge-post-write"); config.setMaximumPoolSize(1); config.setMinimumIdle(0);
        config.setConnectionTimeout(2000); config.setValidationTimeout(1000); config.setInitializationFailTimeout(-1);
        config.setReadOnly(false);
        return new HikariDataSource(config);
    }

    @Bean
    JdbcTemplate writeJdbc(@Qualifier("writeDataSource") HikariDataSource dataSource) {
        var jdbc = new JdbcTemplate(dataSource); jdbc.setQueryTimeout(5); return jdbc;
    }

    @Bean
    JdbcTransactionManager writeTransactionManager(@Qualifier("writeDataSource") HikariDataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }

    @Bean
    TransactionTemplate writeTransaction(@Qualifier("writeTransactionManager") JdbcTransactionManager manager) {
        var transaction = new TransactionTemplate(manager); transaction.setTimeout(10); return transaction;
    }

    @Bean(destroyMethod = "close")
    S3Client postS3Client(PostWriteSettings settings) {
        return S3Client.builder().endpointOverride(settings.endpoint()).region(Region.of(settings.region()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(settings.accessKey(), settings.secretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).chunkedEncodingEnabled(false).build())
                .build();
    }

    @Bean
    PostMediaUploader postMediaUploader(S3Client client, PostWriteSettings settings) {
        return new S3PostMediaUploader(client, settings.bucket(), settings.publicBaseUrl(), settings.objectPrefix());
    }

    @Bean(destroyMethod = "close")
    JdbcPostWriter postWriter(@Qualifier("writeJdbc") JdbcTemplate jdbc,
            @Qualifier("writeTransaction") TransactionTemplate transaction, ObjectMapper mapper) {
        return new JdbcPostWriter(jdbc, transaction, mapper);
    }

    @Bean
    CreatePost createPost(PostMediaUploader uploads, UserNicknameQuery users, JdbcPostWriter writer) {
        return new CreatePost(uploads, users, writer);
    }

    @Bean
    PostCreateController postCreateController(CreatePost createPost) { return new PostCreateController(createPost); }

    @Bean
    PostCreateExceptionHandler postCreateExceptionHandler() { return new PostCreateExceptionHandler(); }
}
