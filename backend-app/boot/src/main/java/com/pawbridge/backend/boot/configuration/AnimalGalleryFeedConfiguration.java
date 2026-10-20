package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.gallery.infrastructure.LostGalleryFeed;
import com.pawbridge.backend.animal.internal.gallery.infrastructure.LostGallerySnapshots;
import com.pawbridge.backend.animal.internal.gallery.web.LostGalleryFeedController;
import com.pawbridge.backend.animal.internal.gallery.web.LostGalleryPagesController;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** The five existing internal feed endpoints; no gallery publishing, new pool or object transfer. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="lost-gallery-feed.enabled",havingValue="true")
public class AnimalGalleryFeedConfiguration {
    private final String internalKey,access,secret;
    private final URI endpoint;
    private final Path directory;

    public AnimalGalleryFeedConfiguration(@Value("${lost-gallery-feed.internal-api-key:}") String internalKey,
            @Value("${lost-gallery-feed.storage.endpoint:}") String address,
            @Value("${lost-gallery-feed.storage.access-key-id:}") String access,
            @Value("${lost-gallery-feed.storage.secret-access-key:}") String secret,
            @Value("${lost-gallery-feed.snapshot-directory:/tmp/pawbridge-gallery-snapshots}") String directory) {
        if(internalKey.isBlank())throw new IllegalArgumentException("Gallery feed authentication is required");
        URI parsed;
        try {parsed=URI.create(address);} catch(IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Dedicated R2 signing configuration is required");
        }
        if(!"https".equals(parsed.getScheme())||parsed.getHost()==null
                ||!parsed.getHost().matches("[a-z0-9-]+\\.r2\\.cloudflarestorage\\.com")
                ||parsed.getUserInfo()!=null||parsed.getQuery()!=null||parsed.getFragment()!=null
                ||(parsed.getPort()!=-1&&parsed.getPort()!=443)
                ||(!parsed.getPath().isEmpty()&&!"/".equals(parsed.getPath()))||access.isBlank()||secret.isBlank())
            throw new IllegalArgumentException("Dedicated R2 signing configuration is required");
        this.internalKey=internalKey;this.endpoint=parsed;this.access=access;this.secret=secret;
        try {this.directory=Path.of(directory);} catch(RuntimeException invalid) {
            throw new IllegalArgumentException("A local snapshot directory is required");
        }
    }

    @Bean(destroyMethod="close")
    S3Presigner animalGalleryPresigner() {
        return S3Presigner.builder().region(Region.of("auto")).endpointOverride(endpoint)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access,secret))).build();
    }

    @Bean
    LostGalleryFeed animalGalleryFeed(@Qualifier("readJdbc") JdbcTemplate jdbc,
            @Qualifier("dataSource") DataSource source,
            @Qualifier("transactionManager") JdbcTransactionManager manager,ObjectMapper mapper,
            @Qualifier("animalGalleryPresigner") S3Presigner signer) {
        if(jdbc.getDataSource()!=source||manager.getDataSource()!=source)
            throw new IllegalArgumentException("Gallery reads must use the existing reader DataSource and transaction manager");
        var reads=new TransactionTemplate(manager);reads.setReadOnly(true);reads.setTimeout(120);
        var streaming=new TransactionTemplate(manager,reads);
        streaming.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return new LostGalleryFeed(jdbc,mapper,photoSigner(signer),reads,streaming);
    }

    @Bean(destroyMethod="close")
    LostGallerySnapshots animalGallerySnapshots(ObjectMapper mapper,LostGalleryFeed feed,
            @Qualifier("animalGalleryPresigner") S3Presigner signer) throws java.io.IOException {
        return new LostGallerySnapshots(mapper,feed::streamEntries,photoSigner(signer),directory,Clock.systemUTC());
    }

    @Bean LostGalleryFeedController lostGalleryFeedController(LostGalleryFeed feed) {
        return new LostGalleryFeedController(feed,internalKey);
    }
    @Bean LostGalleryPagesController lostGalleryPagesController(LostGallerySnapshots snapshots) {
        return new LostGalleryPagesController(snapshots,internalKey);
    }
    private static Function<String,String> photoSigner(S3Presigner signer) {
        return key->signer.presignGetObject(request->request.signatureDuration(Duration.ofHours(1))
                .getObjectRequest(object->object.bucket("pawbridge-animal-originals").key(key))).url().toString();
    }
}
