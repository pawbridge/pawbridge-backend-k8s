package com.pawbridge.animalservice.service;

import com.pawbridge.animalservice.mapper.AnimalDocumentMapper;
import com.pawbridge.animalservice.mapper.AnimalMapper;
import com.pawbridge.animalservice.repository.AnimalDocumentRepository;
import com.pawbridge.animalservice.repository.AnimalRepository;
import com.pawbridge.animalservice.search.KoreanSearchAnalyzer;
import com.pawbridge.animalservice.search.SearchDocumentWriter;
import com.pawbridge.animalservice.search.PostgresqlSearchConfiguration;
import com.pawbridge.animalservice.search.PostgresqlSearchProjector;
import com.pawbridge.animalservice.search.PostgresqlSearchProjectionSchedule;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnimalQueryBackendSelectionTest {
    private final DataSource source=mock(DataSource.class);
    private final ApplicationContextRunner context=new ApplicationContextRunner()
            .withUserConfiguration(AnimalElasticsearchService.class,PostgresqlAnimalQueryService.class,
                    PostgresqlSearchConfiguration.class,PostgresqlSearchProjectionSchedule.class,SearchDocumentWriter.class)
            .withBean(DataSource.class,()->source)
            .withBean(AnimalRepository.class,()->mock(AnimalRepository.class))
            .withBean(AnimalMapper.class,AnimalMapper::new)
            .withBean(ElasticsearchOperations.class,()->mock(ElasticsearchOperations.class))
            .withBean(AnimalDocumentRepository.class,()->mock(AnimalDocumentRepository.class))
            .withBean(AnimalDocumentMapper.class,AnimalDocumentMapper::new);
    @Test
    void existing_default_still_uses_only_elasticsearch() {
        context.run(app->{
            assertThat(app).hasSingleBean(AnimalQueryService.class);
            assertThat(app.getBean(AnimalQueryService.class)).isInstanceOf(AnimalElasticsearchService.class);
            assertThat(app).doesNotHaveBean(KoreanSearchAnalyzer.class);
            assertThat(app.getBean(SearchDocumentWriter.class).enabled()).isFalse();
        });
        verifyNoInteractions(source);
    }
    @Test
    void explicit_postgresql_selection_replaces_only_the_query_backend_without_opening_a_connection() {
        context.withPropertyValues("pawbridge.animal-query.backend=postgresql").run(app->{
            assertThat(app).hasSingleBean(AnimalQueryService.class);
            assertThat(app.getBean(AnimalQueryService.class)).isInstanceOf(PostgresqlAnimalQueryService.class);
            assertThat(app).doesNotHaveBean(KoreanSearchAnalyzer.class);
            assertThat(app.getBean(SearchDocumentWriter.class).enabled()).isFalse();
        });
        verifyNoInteractions(source);
    }

    @Test
    void analyzed_text_requires_explicit_selection_and_does_not_start_backfill_on_initialization() {
        context.withPropertyValues("pawbridge.animal-query.backend=postgresql","pawbridge.animal-query.analyzed-text=true")
                .run(app -> {
                    assertThat(app).hasSingleBean(KoreanSearchAnalyzer.class).hasSingleBean(PostgresqlSearchProjector.class);
                    assertThat(app.getBean(SearchDocumentWriter.class).enabled()).isTrue();
                    assertThat(app).doesNotHaveBean(PostgresqlSearchProjectionSchedule.class);
                });
        verifyNoInteractions(source);
    }
}
