package com.pawbridge.backend;

import com.pawbridge.backend.boot.configuration.ReadConfiguration;
import com.pawbridge.backend.boot.configuration.ReadDatabaseSettings;
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
@Import(ReadConfiguration.class)
public class PawBridgeApplication {
    public static void main(String[] args) {
        // Validate explicit inputs before Spring can open a listener or a database connection.
        ReadDatabaseSettings settings = ReadDatabaseSettings.fromEnvironment(System::getenv);
        SpringApplication application = new SpringApplication(PawBridgeApplication.class);
        application.addInitializers(context -> context.getBeanFactory()
                .registerSingleton("readDatabaseSettings", settings));
        application.run(args);
    }
}
