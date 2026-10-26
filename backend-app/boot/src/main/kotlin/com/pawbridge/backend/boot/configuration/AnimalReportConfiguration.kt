package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.community.internal.post.application.PostMediaRemover
import com.pawbridge.backend.community.internal.report.application.AnimalReports
import com.pawbridge.backend.community.internal.report.infrastructure.JdbcAnimalReports
import com.pawbridge.backend.community.internal.report.infrastructure.S3AnimalReportMediaUploader
import com.pawbridge.backend.community.internal.report.web.AnimalReportController
import com.pawbridge.backend.community.internal.report.web.AnimalReportExceptionHandler
import com.pawbridge.backend.user.api.UserNicknameQuery
import java.time.Clock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import software.amazon.awssdk.services.s3.S3Client

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.animal-reports", name = ["enabled"], havingValue = "true", matchIfMissing = false)
class AnimalReportConfiguration {
    @Bean
    fun animalReports(
        @Qualifier("readJdbc") reader: JdbcTemplate,
        @Qualifier("writeJdbc") writer: JdbcTemplate,
        @Qualifier("readOnlyTransaction") read: TransactionTemplate,
        @Qualifier("writeTransaction") write: TransactionTemplate,
        users: UserNicknameQuery,
        @Qualifier("postS3Client") client: S3Client,
        settings: PostWriteSettings,
        remover: PostMediaRemover,
        mapper: ObjectMapper,
        @Qualifier("readClock") clock: Clock,
    ): AnimalReports {
        val uploader = S3AnimalReportMediaUploader(
            client,
            settings.bucket(),
            settings.publicBaseUrl(),
            settings.objectPrefix(),
            remover,
        )
        return JdbcAnimalReports(reader, writer, read, write, users, uploader, remover, mapper, clock)
    }

    @Bean
    fun animalReportController(reports: AnimalReports): AnimalReportController {
        return AnimalReportController(reports)
    }

    @Bean
    fun animalReportExceptionHandler(): AnimalReportExceptionHandler {
        return AnimalReportExceptionHandler()
    }
}
