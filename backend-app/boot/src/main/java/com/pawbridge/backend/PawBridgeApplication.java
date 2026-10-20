package com.pawbridge.backend;

import com.pawbridge.backend.boot.configuration.ReadConfiguration;
import com.pawbridge.backend.boot.configuration.ReadDatabaseSettings;
import com.pawbridge.backend.boot.configuration.PostWriteSettings;
import com.pawbridge.backend.boot.configuration.PostWriteConfiguration;
import com.pawbridge.backend.boot.configuration.CommentWriteConfiguration;
import com.pawbridge.backend.boot.configuration.UserProfileConfiguration;
import com.pawbridge.backend.boot.configuration.UserNicknameConfiguration;
import com.pawbridge.backend.boot.configuration.FavoriteStatusConfiguration;
import com.pawbridge.backend.boot.configuration.HomeVideoReadConfiguration;
import com.pawbridge.backend.boot.configuration.VisitReadConfiguration;
import com.pawbridge.backend.boot.configuration.NoteRecipientReadConfiguration;
import com.pawbridge.backend.boot.configuration.NoteBlockStatusReadConfiguration;
import com.pawbridge.backend.boot.configuration.TotalUserCountReadConfiguration;
import com.pawbridge.backend.boot.configuration.SignupStatisticsReadConfiguration;
import com.pawbridge.backend.boot.configuration.AdminUserUpdateConfiguration;
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
@EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
        UserDetailsServiceAutoConfiguration.class})
@Import({ReadConfiguration.class, PostWriteConfiguration.class, CommentWriteConfiguration.class, UserProfileConfiguration.class, UserNicknameConfiguration.class, FavoriteStatusConfiguration.class, HomeVideoReadConfiguration.class, VisitReadConfiguration.class, NoteRecipientReadConfiguration.class, NoteBlockStatusReadConfiguration.class, TotalUserCountReadConfiguration.class, SignupStatisticsReadConfiguration.class, AdminUserUpdateConfiguration.class, NoteBlockListReadConfiguration.class, NoteNotificationsReadConfiguration.class, PrivateNoteReadConfiguration.class, PrivateNoteListConfiguration.class, NoteBlockRemoveConfiguration.class, NoteBlockCreateConfiguration.class, NoteReadStreamConfiguration.class, NoteFavoriteConfiguration.class, NoteDeletionConfiguration.class, NoteSendConfiguration.class, NoteExpiryConfiguration.class, MemberCleanupConfiguration.class})
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
