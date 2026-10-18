package com.pawbridge.animalservice.service;

import com.pawbridge.animalservice.dto.request.AnimalSearchRequest;
import com.pawbridge.animalservice.mapper.AnimalDocumentMapper;
import com.pawbridge.animalservice.repository.AnimalDocumentRepository;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.*;

class AnimalSearchDateCompatibilityTest {
    @Test void legacy_backend_does_not_silently_ignore_intake_dates() {
        var operations = mock(ElasticsearchOperations.class);
        var repository = mock(AnimalDocumentRepository.class);
        var mapper = mock(AnimalDocumentMapper.class);
        var service = new AnimalElasticsearchService(operations, repository, mapper);
        var request = AnimalSearchRequest.builder().intakeFrom(LocalDate.of(2026,9,1))
                .intakeTo(LocalDate.of(2026,9,27)).build();
        assertThatIllegalArgumentException().isThrownBy(() -> service.searchAnimals(request, PageRequest.of(0,20)));
        verifyNoInteractions(operations, repository, mapper);
    }
}
