package com.pawbridge.backend;

import com.pawbridge.backend.boot.configuration.ReadConfiguration;
import com.pawbridge.backend.boot.configuration.AnimalDiscoveryConfiguration;
import com.pawbridge.backend.boot.configuration.AnimalCommandConfiguration;
import com.pawbridge.backend.boot.configuration.AnimalRecommendationsConfiguration;
import com.pawbridge.backend.boot.configuration.MemberChatConfiguration;
import com.pawbridge.backend.boot.configuration.GoogleOAuthConfiguration;
import com.pawbridge.backend.boot.configuration.AdminUserDirectoryConfiguration;
import com.pawbridge.backend.boot.configuration.AdminPostStatisticsConfiguration;
import com.pawbridge.backend.boot.configuration.AdminPostReadConfiguration;
import com.pawbridge.backend.boot.configuration.ReadDatabaseSettings;
import com.pawbridge.backend.boot.configuration.PostWriteSettings;
import com.pawbridge.backend.boot.configuration.PostWriteConfiguration;
import com.pawbridge.backend.boot.configuration.CommentWriteConfiguration;
import com.pawbridge.backend.boot.configuration.UserProfileConfiguration;
import com.pawbridge.backend.boot.configuration.UserNicknameConfiguration;
import com.pawbridge.backend.boot.configuration.FavoriteStatusConfiguration;
import com.pawbridge.backend.boot.configuration.HomeVideoReadConfiguration;
import com.pawbridge.backend.boot.configuration.HomeVideoManagementConfiguration;
import com.pawbridge.backend.boot.configuration.AnimalReportConfiguration;
import com.pawbridge.backend.boot.configuration.VisitReadConfiguration;
import com.pawbridge.backend.boot.configuration.VisitCollectionConfiguration;
import com.pawbridge.backend.boot.configuration.NoteRecipientReadConfiguration;
import com.pawbridge.backend.boot.configuration.NoteBlockStatusReadConfiguration;
import com.pawbridge.backend.boot.configuration.TotalUserCountReadConfiguration;
import com.pawbridge.backend.boot.configuration.SignupStatisticsReadConfiguration;
import com.pawbridge.backend.boot.configuration.AdminUserUpdateConfiguration;
import com.pawbridge.backend.boot.configuration.AuthSessionConfiguration;
import com.pawbridge.backend.boot.configuration.NoteBlockListReadConfiguration;
import com.pawbridge.backend.boot.configuration.NoteNotificationsReadConfiguration;
import com.pawbridge.backend.boot.configuration.PrivateNoteReadConfiguration;
import com.pawbridge.backend.boot.configuration.PrivateNoteListConfiguration;
import com.pawbridge.backend.boot.configuration.NoteBlockRemoveConfiguration;
import com.pawbridge.backend.boot.configuration.NoteBlockCreateConfiguration;
import com.pawbridge.backend.boot.configuration.NoteReadStreamConfiguration;
import com.pawbridge.backend.boot.configuration.NoteFavoriteConfiguration;
import com.pawbridge.backend.boot.configuration.NoteDeletionConfiguration;
import com.pawbridge.backend.boot.configuration.NoteSendConfiguration;
import com.pawbridge.backend.boot.configuration.NoteExpiryConfiguration;
import com.pawbridge.backend.boot.configuration.MemberCleanupConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootConfiguration
@EnableAutoConfiguration(excludeName = {"org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration",
        "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "org.springframework.boot.autoconfigure.batch.BatchAutoConfiguration"}, exclude = {DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
        UserDetailsServiceAutoConfiguration.class,
        org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration.class,
        org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration.class,
        org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration.class})
@Import({com.pawbridge.backend.boot.configuration.ShelterDirectoryConfiguration.class, com.pawbridge.backend.boot.configuration.UserPasswordConfiguration.class, com.pawbridge.backend.boot.configuration.AnimalImageConfiguration.class, com.pawbridge.backend.boot.configuration.LocalLoginConfiguration.class, com.pawbridge.backend.boot.configuration.PaymentConfirmationConfiguration.class, com.pawbridge.backend.boot.configuration.StorePaymentEventsConfiguration.class, com.pawbridge.backend.boot.configuration.StoreCatalogManagementConfiguration.class,com.pawbridge.backend.boot.configuration.ShelterObservationConfiguration.class, com.pawbridge.backend.boot.configuration.AnimalChatbotConfiguration.class,com.pawbridge.backend.boot.configuration.AnimalSearchProjectionConfiguration.class, com.pawbridge.backend.boot.configuration.AnimalPhotoArchiveConfiguration.class, com.pawbridge.backend.boot.configuration.AnimalApmsCollectionConfiguration.class, com.pawbridge.backend.boot.configuration.AnimalTravelCollectionConfiguration.class, com.pawbridge.backend.boot.configuration.AnimalGalleryFeedConfiguration.class, com.pawbridge.backend.boot.configuration.ShelterCommandConfiguration.class, com.pawbridge.backend.boot.configuration.StoreOrderAdministrationConfiguration.class, com.pawbridge.backend.boot.configuration.AnimalTravelConfiguration.class, com.pawbridge.backend.boot.configuration.FavoriteDeliveryConfiguration.class, com.pawbridge.backend.boot.configuration.UserMaintenanceConfiguration.class, com.pawbridge.backend.boot.configuration.StoreWishlistConfiguration.class, com.pawbridge.backend.boot.configuration.AnimalStatisticsConfiguration.class, com.pawbridge.backend.boot.configuration.AnimalShelterConfiguration.class, AnimalDiscoveryConfiguration.class, AnimalCommandConfiguration.class, com.pawbridge.backend.boot.configuration.AnimalLostSearchConfiguration.class, com.pawbridge.backend.boot.configuration.StoreDirectOrderConfiguration.class, com.pawbridge.backend.boot.configuration.StoreCartConfiguration.class, AnimalRecommendationsConfiguration.class, com.pawbridge.backend.boot.configuration.StoreCatalogConfiguration.class, com.pawbridge.backend.boot.configuration.CommunityMaintenanceConfiguration.class, com.pawbridge.backend.boot.configuration.StoreMaintenanceConfiguration.class, com.pawbridge.backend.boot.configuration.UserStoreMyPageConfiguration.class, HomeVideoManagementConfiguration.class, AnimalReportConfiguration.class, com.pawbridge.backend.boot.configuration.UserAnimalActivitiesConfiguration.class, com.pawbridge.backend.boot.configuration.ShelterMembershipConfiguration.class, AdminUserDirectoryConfiguration.class, GoogleOAuthConfiguration.class, MemberChatConfiguration.class, ReadConfiguration.class, AdminPostReadConfiguration.class, AdminPostStatisticsConfiguration.class, PostWriteConfiguration.class, CommentWriteConfiguration.class, UserProfileConfiguration.class, UserNicknameConfiguration.class, FavoriteStatusConfiguration.class, HomeVideoReadConfiguration.class, VisitReadConfiguration.class, VisitCollectionConfiguration.class, NoteRecipientReadConfiguration.class, NoteBlockStatusReadConfiguration.class, TotalUserCountReadConfiguration.class, SignupStatisticsReadConfiguration.class, AdminUserUpdateConfiguration.class, AuthSessionConfiguration.class, NoteBlockListReadConfiguration.class, NoteNotificationsReadConfiguration.class, PrivateNoteReadConfiguration.class, PrivateNoteListConfiguration.class, NoteBlockRemoveConfiguration.class, NoteBlockCreateConfiguration.class, NoteReadStreamConfiguration.class, NoteFavoriteConfiguration.class, NoteDeletionConfiguration.class, NoteSendConfiguration.class, NoteExpiryConfiguration.class, MemberCleanupConfiguration.class})
public class PawBridgeApplication {
    public static void main(String[] args) {
        // Validate explicit inputs before Spring can open a listener or a database connection.
        ReadDatabaseSettings settings = ReadDatabaseSettings.fromEnvironment(System::getenv);
        PostWriteSettings writeSettings = PostWriteSettings.fromEnvironment(System::getenv, settings);
        SpringApplication application = new SpringApplication(PawBridgeApplication.class);
        application.addInitializers(context -> {
            context.getBeanFactory().registerSingleton("readDatabaseSettings", settings);
            context.getBeanFactory().registerSingleton("postWriteSettings", writeSettings);
        });
        application.run(args);
    }
}
