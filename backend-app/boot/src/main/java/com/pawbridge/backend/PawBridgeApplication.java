package com.pawbridge.backend;

import com.pawbridge.backend.boot.configuration.ReadConfiguration;
import com.pawbridge.backend.boot.configuration.ReadDatabaseSettings;
import com.pawbridge.backend.boot.configuration.PostWriteSettings;
import com.pawbridge.backend.boot.configuration.PostWriteConfiguration;
import com.pawbridge.backend.boot.configuration.CommentWriteConfiguration;
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
@Import({ReadConfiguration.class, PostWriteConfiguration.class, CommentWriteConfiguration.class})
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
