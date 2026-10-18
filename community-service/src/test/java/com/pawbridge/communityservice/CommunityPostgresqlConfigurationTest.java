package com.pawbridge.communityservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class CommunityPostgresqlConfigurationTest {
    private StandardEnvironment environment;

    @BeforeEach
    void loadBaseConfiguration() throws IOException {
        environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("test-credentials", Map.of(
                "COMMUNITY_POSTGRESQL_JDBC_URL", "jdbc:postgresql://127.0.0.1:15439/pawbridge",
                "COMMUNITY_POSTGRESQL_USERNAME", "configuration_test",
                "COMMUNITY_POSTGRESQL_PASSWORD", "synthetic_test_only")));
        for (var source : new YamlPropertySourceLoader().load("application",
                new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
    }

    @Test
    void givenBaseConfiguration_whenDatasourceBound_thenPostgresqlUsesExistingCredentialKeys() {
        var datasource = Binder.get(environment)
                .bind("spring.datasource", Bindable.of(DataSourceProperties.class)).get();

        assertThat(datasource.getDriverClassName()).isEqualTo("org.postgresql.Driver");
        assertThat(datasource.getUrl()).isEqualTo("jdbc:postgresql://127.0.0.1:15439/pawbridge");
        assertThat(datasource.getUsername()).isEqualTo("configuration_test");
        assertThat(datasource.getPassword()).isEqualTo("synthetic_test_only");
        assertThat(environment.getProperty("spring.profiles.active")).isNull();
    }

    @Test
    void givenBaseConfiguration_whenPersistenceSettingsRead_thenValidateExistingSchemaWithoutDdlOrEsStartup() {
        assertThat(environment.getProperty("spring.datasource.hikari.schema")).isEqualTo("pawbridge_community");
        assertThat(environment.getProperty("spring.datasource.hikari.connection-init-sql"))
                .isEqualTo("SET TIME ZONE 'UTC'");
        assertThat(environment.getProperty("spring.datasource.hikari.maximum-pool-size", Integer.class)).isEqualTo(5);
        assertThat(environment.getProperty("spring.datasource.hikari.minimum-idle", Integer.class)).isZero();
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(environment.getProperty("spring.jpa.open-in-view", Boolean.class)).isFalse();
        assertThat(environment.getProperty("spring.jpa.properties.hibernate.default_schema"))
                .isEqualTo("pawbridge_community");
        assertThat(environment.getProperty("spring.sql.init.mode")).isEqualTo("never");
        assertThat(environment.getProperty("pawbridge.search.backend")).isEqualTo("postgresql");
        assertThat(environment.getProperty("spring.autoconfigure.exclude[0]"))
                .isEqualTo("org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration");
    }
}
