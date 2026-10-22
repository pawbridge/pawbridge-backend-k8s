package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.animal.internal.image.infrastructure.AnimalImageService;
import com.pawbridge.backend.animal.internal.image.infrastructure.S3AnimalImages;
import com.pawbridge.backend.animal.internal.image.web.AnimalImageController;
import com.pawbridge.backend.animal.internal.image.web.AnimalImageExceptionHandler;
import com.pawbridge.backend.user.internal.animalcommand.infrastructure.JdbcAnimalCommandActors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;

/** Opt-in original image routes; borrows the Post-owned storage client and the existing writer for actor lookup. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="pawbridge.animal-images",name="enabled",havingValue="true")
public class AnimalImageConfiguration {
    @Bean(destroyMethod="")
    S3AnimalImages animalImages(@Qualifier("postS3Client") S3Client client,PostWriteSettings settings) {
        return new S3AnimalImages(client,settings.bucket(),settings.publicBaseUrl());
    }

    @Bean
    AnimalImageService animalImageService(S3AnimalImages images,@Qualifier("writeJdbc") JdbcTemplate jdbc,
            @Qualifier("writeTransactionManager") JdbcTransactionManager manager) {
        if(jdbc.getDataSource()!=manager.getDataSource())
            throw new IllegalArgumentException("Animal image actors must use the writer transaction DataSource");
        var writes=new TransactionTemplate(manager);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        writes.setTimeout(10);
        // Not a bean: Animal commands already expose the single AnimalCommandActors bean when enabled.
        return new AnimalImageService(images,new JdbcAnimalCommandActors(jdbc),writes);
    }

    @Bean AnimalImageController animalImageController(AnimalImageService images) { return new AnimalImageController(images); }
    @Bean AnimalImageExceptionHandler animalImageExceptionHandler() { return new AnimalImageExceptionHandler(); }
}
