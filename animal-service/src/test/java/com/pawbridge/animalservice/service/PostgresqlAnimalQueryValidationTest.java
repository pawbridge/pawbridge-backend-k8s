package com.pawbridge.animalservice.service;

import com.pawbridge.animalservice.dto.request.AnimalSearchRequest;
import com.pawbridge.animalservice.mapper.AnimalMapper;
import com.pawbridge.animalservice.repository.AnimalRepository;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostgresqlAnimalQueryValidationTest {
    private final DataSource source=mock(DataSource.class);
    private final PostgresqlAnimalQueryService service=new PostgresqlAnimalQueryService(source,mock(AnimalRepository.class),new AnimalMapper());

    @Test
    void unsupported_sort_and_unbounded_pages_are_rejected_before_database_access() {
        for (Pageable page: new Pageable[]{Pageable.unpaged(),PageRequest.of(0,101),PageRequest.of(500,20),
                PageRequest.of(0,20,Sort.by("unknown")),PageRequest.of(0,20,Sort.by("createdAt","id")),
                PageRequest.of(0,20,Sort.by("relevance"))}) {
            assertThatThrownBy(()->service.searchAnimals(new AnimalSearchRequest(),page)).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(source);
    }
    @Test
    void expensive_text_input_is_rejected_before_database_access() {
        for (String keyword:new String[]{"가".repeat(201),"a b c d e f g h i"})
            assertThatThrownBy(()->service.searchAnimals(AnimalSearchRequest.builder().keyword(keyword).build(),PageRequest.of(0,20)))
                    .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(source);
    }

    @Test
    void analyzed_text_keeps_input_bounds_before_readiness_queries() throws Exception {
        try(com.pawbridge.animalservice.search.KoreanSearchAnalyzer analyzer=new com.pawbridge.animalservice.search.KoreanSearchAnalyzer()) {
            PostgresqlAnimalQueryService analyzed=new PostgresqlAnimalQueryService(source,mock(AnimalRepository.class),new AnimalMapper(),java.util.Optional.of(analyzer));
            assertThatThrownBy(()->analyzed.searchAnimals(AnimalSearchRequest.builder().keyword("a b c d e f g h i").build(),PageRequest.of(0,20)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(source);
    }
}
