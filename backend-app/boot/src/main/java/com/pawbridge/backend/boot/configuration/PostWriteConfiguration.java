package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.boot.security.PostTokenAuthenticator;
import com.pawbridge.backend.community.internal.post.application.CreatePost;
import com.pawbridge.backend.community.internal.post.application.UpdatePost;
import com.pawbridge.backend.community.internal.post.web.PostUpdateController;
import com.pawbridge.backend.community.internal.post.web.PostUpdateExceptionHandler;
import com.pawbridge.backend.community.internal.post.web.AdminPostUpdateController;
import com.pawbridge.backend.community.internal.post.web.AdminPostDeleteController;
import com.pawbridge.backend.community.internal.post.web.PostMultipartExceptionHandler;
import com.pawbridge.backend.community.internal.post.application.DeletePost;
import com.pawbridge.backend.community.internal.post.application.PostDeletion;
import com.pawbridge.backend.community.internal.post.application.PostMediaRemover;
import com.pawbridge.backend.community.internal.post.application.PostReader;
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostDeletion;
import com.pawbridge.backend.community.internal.post.infrastructure.media.S3PostMediaRemover;
import com.pawbridge.backend.community.internal.post.web.PostDeleteController;
import com.pawbridge.backend.community.internal.post.web.PostDeleteExceptionHandler;
import com.pawbridge.backend.community.internal.post.application.PostMediaUploader;
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostWriter;
import com.pawbridge.backend.community.internal.post.infrastructure.media.S3PostMediaUploader;
import com.pawbridge.backend.community.internal.post.web.PostCreateController;
import com.pawbridge.backend.community.internal.post.web.PostCreateExceptionHandler;
import com.pawbridge.backend.user.api.UserNicknameQuery;
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
    PostMediaRemover postMediaRemover(@Qualifier("postS3Client") S3Client client, PostWriteSettings settings) {
        return new S3PostMediaRemover(client, settings.bucket(), settings.publicBaseUrl(), settings.objectPrefix());
    }

    @Bean
    PostDeletion postDeletion(@Qualifier("writeJdbc") JdbcTemplate jdbc,
            @Qualifier("writeTransaction") TransactionTemplate transaction) {
        return new JdbcPostDeletion(jdbc, transaction);
    }

    @Bean
    DeletePost deletePost(PostReader reader, PostMediaRemover media, PostDeletion deletion) {
        return new DeletePost(reader, media, deletion);
    }

    @Bean
    PostDeleteController postDeleteController(DeletePost deletePost) { return new PostDeleteController(deletePost); }

    @Bean
    PostDeleteExceptionHandler postDeleteExceptionHandler() { return new PostDeleteExceptionHandler(); }

    @Bean
    UpdatePost updatePost(PostReader reader, PostMediaRemover media, PostMediaUploader uploader,
            UserNicknameQuery users, JdbcPostWriter writer) {
        return new UpdatePost(reader,media,uploader,users,writer);
    }

    @Bean
    PostUpdateController postUpdateController(UpdatePost update) { return new PostUpdateController(update); }

    @Bean
    AdminPostUpdateController adminPostUpdateController(UpdatePost update) {
        return new AdminPostUpdateController(update);
    }

    @Bean
    AdminPostDeleteController adminPostDeleteController(DeletePost delete) {
        return new AdminPostDeleteController(delete);
    }

    @Bean
    PostUpdateExceptionHandler postUpdateExceptionHandler() { return new PostUpdateExceptionHandler(); }

    @Bean
    PostMultipartExceptionHandler postMultipartExceptionHandler() { return new PostMultipartExceptionHandler(); }

    @Bean
    PostCreateExceptionHandler postCreateExceptionHandler() { return new PostCreateExceptionHandler(); }
}
