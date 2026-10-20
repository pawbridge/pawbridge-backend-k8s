package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.community.internal.report.application.AnimalReports;
import com.pawbridge.backend.community.internal.report.infrastructure.JdbcAnimalReports;
import com.pawbridge.backend.community.internal.report.infrastructure.S3AnimalReportMediaUploader;
import com.pawbridge.backend.community.internal.report.web.AnimalReportController;
import com.pawbridge.backend.community.internal.report.web.AnimalReportExceptionHandler;
import com.pawbridge.backend.community.internal.post.application.PostMediaRemover;
import com.pawbridge.backend.user.api.UserNicknameQuery;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration(proxyBeanMethods=false)
public class AnimalReportConfiguration {
    @Bean AnimalReports animalReports(@Qualifier("readJdbc") JdbcTemplate reader,
            @Qualifier("writeJdbc") JdbcTemplate writer,@Qualifier("readOnlyTransaction") TransactionTemplate read,
            @Qualifier("writeTransaction") TransactionTemplate write,UserNicknameQuery users,
            @Qualifier("postS3Client") S3Client client,PostWriteSettings settings,PostMediaRemover remover,
            ObjectMapper mapper,@Qualifier("readClock") Clock clock){
        var uploader=new S3AnimalReportMediaUploader(client,settings.bucket(),settings.publicBaseUrl(),settings.objectPrefix(),remover);
        return new JdbcAnimalReports(reader,writer,read,write,users,uploader,remover,mapper,clock);
    }
    @Bean AnimalReportController animalReportController(AnimalReports reports){return new AnimalReportController(reports);}
    @Bean AnimalReportExceptionHandler animalReportExceptionHandler(){return new AnimalReportExceptionHandler();}
}
