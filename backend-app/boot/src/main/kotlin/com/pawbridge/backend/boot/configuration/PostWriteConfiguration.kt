package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.boot.security.PostTokenAuthenticator
import com.pawbridge.backend.community.internal.post.application.CreatePost
import com.pawbridge.backend.community.internal.post.application.DeletePost
import com.pawbridge.backend.community.internal.post.application.PostDeletion
import com.pawbridge.backend.community.internal.post.application.PostMediaRemover
import com.pawbridge.backend.community.internal.post.application.PostMediaUploader
import com.pawbridge.backend.community.internal.post.application.PostReader
import com.pawbridge.backend.community.internal.post.application.UpdatePost
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostDeletion
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostWriter
import com.pawbridge.backend.community.internal.post.infrastructure.media.S3PostMediaRemover
import com.pawbridge.backend.community.internal.post.infrastructure.media.S3PostMediaUploader
import com.pawbridge.backend.community.internal.post.web.AdminPostDeleteController
import com.pawbridge.backend.community.internal.post.web.AdminPostUpdateController
import com.pawbridge.backend.community.internal.post.web.PostCreateController
import com.pawbridge.backend.community.internal.post.web.PostCreateExceptionHandler
import com.pawbridge.backend.community.internal.post.web.PostDeleteController
import com.pawbridge.backend.community.internal.post.web.PostDeleteExceptionHandler
import com.pawbridge.backend.community.internal.post.web.PostMultipartExceptionHandler
import com.pawbridge.backend.community.internal.post.web.PostUpdateController
import com.pawbridge.backend.community.internal.post.web.PostUpdateExceptionHandler
import com.pawbridge.backend.user.api.UserNicknameQuery
import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration

@Configuration(proxyBeanMethods = false)
class PostWriteConfiguration {
    @Bean
    fun postTokenAuthenticator(settings: PostWriteSettings): PostTokenAuthenticator {
        return PostTokenAuthenticator(settings.jwtKey())
    }

    @Bean
    fun writeJdbc(@Qualifier("writeDataSource") dataSource: HikariDataSource): JdbcTemplate {
        val jdbc = JdbcTemplate(dataSource)
        jdbc.queryTimeout = 5
        return jdbc
    }

    @Bean
    fun writeTransactionManager(@Qualifier("writeDataSource") dataSource: HikariDataSource): JdbcTransactionManager {
        return JdbcTransactionManager(dataSource)
    }

    @Bean
    fun writeTransaction(
        @Qualifier("writeTransactionManager") manager: JdbcTransactionManager,
    ): TransactionTemplate {
        val transaction = TransactionTemplate(manager)
        transaction.timeout = 10
        return transaction
    }

    @Bean(destroyMethod = "close")
    fun postS3Client(settings: PostWriteSettings): S3Client {
        return S3Client.builder()
            .endpointOverride(settings.endpoint())
            .region(Region.of(settings.region()))
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(settings.accessKey(), settings.secretKey())),
            )
            .serviceConfiguration(
                S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .chunkedEncodingEnabled(false)
                    .build(),
            )
            .build()
    }

    @Bean
    fun postMediaUploader(client: S3Client, settings: PostWriteSettings): PostMediaUploader {
        return S3PostMediaUploader(client, settings.bucket(), settings.publicBaseUrl(), settings.objectPrefix())
    }

    @Bean(destroyMethod = "close")
    fun postWriter(
        @Qualifier("writeJdbc") jdbc: JdbcTemplate,
        @Qualifier("writeTransaction") transaction: TransactionTemplate,
        mapper: ObjectMapper,
    ): JdbcPostWriter {
        return JdbcPostWriter(jdbc, transaction, mapper)
    }

    @Bean
    fun createPost(uploads: PostMediaUploader, users: UserNicknameQuery, writer: JdbcPostWriter): CreatePost {
        return CreatePost(uploads, users, writer)
    }

    @Bean
    fun postCreateController(createPost: CreatePost): PostCreateController {
        return PostCreateController(createPost)
    }

    @Bean
    fun postMediaRemover(@Qualifier("postS3Client") client: S3Client, settings: PostWriteSettings): PostMediaRemover {
        return S3PostMediaRemover(client, settings.bucket(), settings.publicBaseUrl(), settings.objectPrefix())
    }

    @Bean
    fun postDeletion(
        @Qualifier("writeJdbc") jdbc: JdbcTemplate,
        @Qualifier("writeTransaction") transaction: TransactionTemplate,
    ): PostDeletion {
        return JdbcPostDeletion(jdbc, transaction)
    }

    @Bean
    fun deletePost(reader: PostReader, media: PostMediaRemover, deletion: PostDeletion): DeletePost {
        return DeletePost(reader, media, deletion)
    }

    @Bean
    fun postDeleteController(deletePost: DeletePost): PostDeleteController {
        return PostDeleteController(deletePost)
    }

    @Bean
    fun postDeleteExceptionHandler(): PostDeleteExceptionHandler {
        return PostDeleteExceptionHandler()
    }

    @Bean
    fun updatePost(
        reader: PostReader,
        media: PostMediaRemover,
        uploader: PostMediaUploader,
        users: UserNicknameQuery,
        writer: JdbcPostWriter,
    ): UpdatePost {
        return UpdatePost(reader, media, uploader, users, writer)
    }

    @Bean
    fun postUpdateController(update: UpdatePost): PostUpdateController {
        return PostUpdateController(update)
    }

    @Bean
    fun adminPostUpdateController(update: UpdatePost): AdminPostUpdateController {
        return AdminPostUpdateController(update)
    }

    @Bean
    fun adminPostDeleteController(delete: DeletePost): AdminPostDeleteController {
        return AdminPostDeleteController(delete)
    }

    @Bean
    fun postUpdateExceptionHandler(): PostUpdateExceptionHandler {
        return PostUpdateExceptionHandler()
    }

    @Bean
    fun postMultipartExceptionHandler(): PostMultipartExceptionHandler {
        return PostMultipartExceptionHandler()
    }

    @Bean
    fun postCreateExceptionHandler(): PostCreateExceptionHandler {
        return PostCreateExceptionHandler()
    }
}
