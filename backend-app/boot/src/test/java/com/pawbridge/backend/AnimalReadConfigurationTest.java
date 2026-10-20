package com.pawbridge.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery;
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Details;
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Species;
import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer;
import com.pawbridge.backend.animal.internal.discovery.web.AnimalDiscoveryController;
import com.pawbridge.backend.animal.internal.discovery.web.AnimalDiscoveryExceptionHandler;
import com.pawbridge.backend.animal.internal.recommendation.application.AnimalRecommendations;
import com.pawbridge.backend.animal.internal.recommendation.application.RecommendationUnavailableException;
import com.pawbridge.backend.animal.internal.recommendation.infrastructure.PythonAnimalRecommendations;
import com.pawbridge.backend.animal.internal.recommendation.web.AnimalRecommendationController;
import com.pawbridge.backend.animal.internal.recommendation.web.AnimalRecommendationExceptionHandler;
import com.pawbridge.backend.boot.configuration.AnimalDiscoveryConfiguration;
import com.pawbridge.backend.boot.configuration.AnimalRecommendationsConfiguration;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnimalReadConfigurationTest {
    @ParameterizedTest @ValueSource(strings = {"absent", "false"})
    void given__features_not_enabled_and_no_ai_or_database_inputs__when__assembling__then__create_no_animal_beans(String flag) {
        var runner = new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(AnimalDiscoveryConfiguration.class, AnimalRecommendationsConfiguration.class);
        if (flag.equals("false")) runner = runner.withPropertyValues("pawbridge.animal-discovery.enabled=false", "pawbridge.animal-recommendations.enabled=false");
        runner.run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(AnimalDiscovery.class).doesNotHaveBean(KoreanSearchAnalyzer.class)
                .doesNotHaveBean(AnimalDiscoveryController.class).doesNotHaveBean(AnimalDiscoveryExceptionHandler.class)
                .doesNotHaveBean(AnimalRecommendations.class).doesNotHaveBean(PythonAnimalRecommendations.class)
                .doesNotHaveBean(AnimalRecommendationController.class).doesNotHaveBean(AnimalRecommendationExceptionHandler.class));
    }

    @Test
    void given__discovery_enabled__when__assembling__then__reuse_reader_inputs_and_close_the_single_owned_analyzer() {
        DataSource source = mock(DataSource.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        AtomicReference<KoreanSearchAnalyzer> owned = new AtomicReference<>();
        inputs(source, manager).withPropertyValues("pawbridge.animal-discovery.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            AnimalDiscovery animals = context.getBean(AnimalDiscovery.class);
            var jdbc = (JdbcTemplate) ((NamedParameterJdbcTemplate) ReflectionTestUtils.getField(animals, "jdbc")).getJdbcTemplate();
            assertThat(jdbc.getDataSource()).isSameAs(source);
            assertThat(jdbc.getQueryTimeout()).isEqualTo(5);
            var transaction = (TransactionTemplate) ReflectionTestUtils.getField(animals, "transactions");
            assertThat(transaction.getTransactionManager()).isSameAs(manager);
            assertThat(transaction.isReadOnly()).isTrue();
            assertThat(transaction.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            assertThat(transaction.getTimeout()).isEqualTo(5);
            assertThat(context.getBeansOfType(KoreanSearchAnalyzer.class)).hasSize(1);
            owned.set(context.getBean(KoreanSearchAnalyzer.class));
            assertThat(ReflectionTestUtils.getField(animals, "analyzer")).isSameAs(owned.get());
            assertThat(owned.get().tokens("말티즈")).contains("말티즈");
            assertThat(context.getBeansOfType(AnimalDiscoveryController.class)).hasSize(1);
            assertThat(context.getBeansOfType(AnimalDiscoveryExceptionHandler.class)).hasSize(1);
            assertThat(context).doesNotHaveBean(AnimalRecommendations.class);
            assertThat(PawBridgeApplication.class.getAnnotation(Import.class).value())
                    .contains(AnimalDiscoveryConfiguration.class, AnimalRecommendationsConfiguration.class);
            verifyNoInteractions(source, manager);
        });
        assertThatThrownBy(() -> owned.get().tokens("말티즈")).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void given__recommendations_only_with_explicit_urls__when__assembling__then__reuse_core_reader_without_discovery_routes_and_preserve_url_precedence(boolean preferred) {
        DataSource source = mock(DataSource.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        var runner = inputs(source, manager).withPropertyValues("pawbridge.animal-recommendations.enabled=true",
                "python-ai-service.url=https://fallback.example.invalid");
        if (preferred) runner = runner.withPropertyValues("lost-search.python-url=https://preferred.example.invalid");
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(AnimalDiscovery.class)).hasSize(1);
            assertThat(context.getBeansOfType(KoreanSearchAnalyzer.class)).hasSize(1);
            assertThat(context).doesNotHaveBean(AnimalDiscoveryController.class).doesNotHaveBean(AnimalDiscoveryExceptionHandler.class);
            AnimalRecommendations recommendations = context.getBean(AnimalRecommendations.class);
            assertThat(ReflectionTestUtils.getField(recommendations, "animals")).isSameAs(context.getBean(AnimalDiscovery.class));
            var jdbc = (JdbcTemplate) ((NamedParameterJdbcTemplate) ReflectionTestUtils.getField(recommendations, "jdbc")).getJdbcTemplate();
            assertThat(jdbc.getDataSource()).isSameAs(source);
            var reads = (TransactionTemplate) ReflectionTestUtils.getField(recommendations, "reads");
            assertThat(reads.getTransactionManager()).isSameAs(manager);
            assertThat(reads.isReadOnly()).isTrue();
            assertThat(reads.getTimeout()).isEqualTo(5);
            assertThat(AopUtils.isAopProxy(recommendations)).isFalse();
            assertThat(AopUtils.isAopProxy(context.getBean(AnimalRecommendationController.class))).isFalse();
            String target = String.valueOf(ReflectionTestUtils.getField(context.getBean(PythonAnimalRecommendations.class), "endpoint"));
            assertThat(target).contains(preferred ? "https://preferred.example.invalid" : "https://fallback.example.invalid");
            if (preferred) assertThat(target).doesNotContain("https://fallback.example.invalid");
            verifyNoInteractions(source, manager);
        });
    }

    @Test
    void given__recommendations_enabled_without_either_source_url__when__assembling__then__fail_configuration_instead_of_creating_a_fallback() {
        inputs(mock(DataSource.class), mock(PlatformTransactionManager.class))
                .withPropertyValues("pawbridge.animal-recommendations.enabled=true").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void given__missing_key_with_explicit_url__when__calling_the_assembled_controller__then__keep_etc_empty_and_dog_unavailable_without_outer_transaction() {
        AnimalDiscovery animals = mock(AnimalDiscovery.class);
        DataSource source = mock(DataSource.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(AnimalRecommendationsConfiguration.class)
                .withBean(AnimalDiscovery.class, () -> animals)
                .withBean("dataSource", DataSource.class, () -> source)
                .withBean("transactionManager", PlatformTransactionManager.class, () -> manager)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("pawbridge.animal-recommendations.enabled=true", "python-ai-service.url=https://fixture.example.invalid")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(ReflectionTestUtils.getField(context.getBean(PythonAnimalRecommendations.class), "internalKey")).isEqualTo("");
                    var controller = context.getBean(AnimalRecommendationController.class);
                    for (Species species : new Species[]{Species.ETC, Species.DOG}) {
                        doAnswer(call -> {
                            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                            return source(species);
                        }).when(animals).findById(42);
                        if (species == Species.ETC) assertThat(controller.recommend(42L)).isEmpty();
                        else assertThatThrownBy(() -> controller.recommend(42L)).isInstanceOf(RecommendationUnavailableException.class);
                    }
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    verifyNoInteractions(source, manager);
                });
    }

    private static ApplicationContextRunner inputs(DataSource source, PlatformTransactionManager manager) {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(AnimalDiscoveryConfiguration.class, AnimalRecommendationsConfiguration.class)
                .withBean("dataSource", DataSource.class, () -> source)
                .withBean("transactionManager", PlatformTransactionManager.class, () -> manager)
                .withBean(ObjectMapper.class, ObjectMapper::new);
    }

    private static Details source(Species species) {
        return new Details(42L, null, species, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
