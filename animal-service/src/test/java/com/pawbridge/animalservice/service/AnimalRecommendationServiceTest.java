package com.pawbridge.animalservice.service;

import com.pawbridge.animalservice.client.PythonAiServiceClient;
import com.pawbridge.animalservice.client.PythonLostSearchClient;
import com.pawbridge.animalservice.controller.AnimalController;
import com.pawbridge.animalservice.dto.response.AnimalResponse;
import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.enums.AnimalStatus;
import com.pawbridge.animalservice.enums.Species;
import com.pawbridge.animalservice.exception.GlobalExceptionHandler;
import com.pawbridge.animalservice.exception.RecommendationUnavailableException;
import com.pawbridge.animalservice.facade.AnimalFacade;
import com.pawbridge.animalservice.mapper.AnimalMapper;
import com.pawbridge.animalservice.repository.AnimalRepository;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AnimalRecommendationServiceTest {
    private final AnimalRepository animals = mock(AnimalRepository.class);
    private final AnimalMapper mapper = mock(AnimalMapper.class);
    private final PythonAiServiceClient legacy = mock(PythonAiServiceClient.class);
    private final PythonLostSearchClient dinov3 = mock(PythonLostSearchClient.class);
    private final AnimalRecommendationService service = new AnimalRecommendationService(animals, mapper, legacy, dinov3, "postgresql", "test-key");

    @Test
    void adopted_source_uses_stored_features_and_filters_current_state_species_self_and_duplicates() {
        Animal source = animal(1, Species.DOG, AnimalStatus.ADOPTED);
        when(animals.findById(1L)).thenReturn(Optional.of(source));
        when(dinov3.recommend("test-key", 1L, "DOG")).thenReturn(List.of(1L, 4L, 2L, 3L, 4L, 5L));
        Animal first = animal(4, Species.DOG, AnimalStatus.PROTECT);
        Animal second = animal(5, Species.DOG, AnimalStatus.NOTICE);
        when(animals.findWithShelterByIdIn(List.of(4L, 2L, 3L, 5L))).thenReturn(List.of(second,
                animal(2, Species.DOG, AnimalStatus.ADOPTED), animal(3, Species.CAT, AnimalStatus.PROTECT), first));
        when(mapper.toResponse(first)).thenReturn(AnimalResponse.builder().id(4L).build());
        when(mapper.toResponse(second)).thenReturn(AnimalResponse.builder().id(5L).build());
        assertThat(service.recommend(1L)).extracting(AnimalResponse::getId).containsExactly(4L, 5L);
        InOrder sequence = inOrder(animals, dinov3);
        sequence.verify(animals).findById(1L);
        sequence.verify(dinov3).recommend("test-key", 1L, "DOG");
        sequence.verify(animals).findWithShelterByIdIn(List.of(4L, 2L, 3L, 5L));
        verifyNoInteractions(legacy);
    }

    @Test
    void unavailable_or_malformed_pg_results_do_not_fall_back_to_legacy_or_report_empty_success() {
        when(animals.findById(1L)).thenReturn(Optional.of(animal(1, Species.DOG, AnimalStatus.PROTECT)));
        when(dinov3.recommend(anyString(), anyLong(), anyString())).thenThrow(new RuntimeException("unavailable"));
        assertThatThrownBy(() -> service.recommend(1L)).isInstanceOf(RecommendationUnavailableException.class);
        reset(dinov3);
        when(dinov3.recommend(anyString(), anyLong(), anyString())).thenReturn(Arrays.asList(2L, null));
        assertThatThrownBy(() -> service.recommend(1L)).isInstanceOf(RecommendationUnavailableException.class);
        verify(animals, never()).findWithShelterByIdIn(any());
        verifyNoInteractions(legacy);
    }

    @Test
    void unsupported_species_or_no_candidates_returns_empty_without_loading_candidate_rows() {
        when(animals.findById(1L)).thenReturn(Optional.of(animal(1, Species.ETC, AnimalStatus.PROTECT)));
        assertThat(service.recommend(1L)).isEmpty();
        verifyNoInteractions(dinov3, legacy);
        when(animals.findById(1L)).thenReturn(Optional.of(animal(1, Species.CAT, AnimalStatus.PROTECT)));
        when(dinov3.recommend("test-key", 1L, "CAT")).thenReturn(List.of());
        assertThat(service.recommend(1L)).isEmpty();
        verify(animals, never()).findWithShelterByIdIn(any());
    }

    @Test
    void legacy_selection_retains_existing_client_and_filters_resolved_candidates() {
        Animal source = Animal.builder().id(1L).species(Species.DOG).status(AnimalStatus.PROTECT)
                .imageUrl("https://example.test/dog.jpg").build();
        when(animals.findById(1L)).thenReturn(Optional.of(source));
        when(legacy.getSimilarAnimals(any())).thenReturn(List.of(2L));
        when(animals.findWithShelterByIdIn(List.of(2L))).thenReturn(List.of(animal(2, Species.DOG, AnimalStatus.EUTHANIZED)));
        AnimalRecommendationService old = new AnimalRecommendationService(animals, mapper, legacy, dinov3, "elasticsearch", "");
        assertThat(old.recommend(1L)).isEmpty();
        verify(legacy).getSimilarAnimals(any());
        verifyNoInteractions(dinov3);
    }

    @Test
    void recommendation_unavailability_is_http_503_under_the_real_global_advice() throws Exception {
        AnimalFacade facade = mock(AnimalFacade.class);
        when(facade.getSimilarAnimals(1L)).thenThrow(new RecommendationUnavailableException());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AnimalController(facade))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/api/v1/animals/1/similar")).andExpect(status().isServiceUnavailable());
    }

    private Animal animal(long id, Species species, AnimalStatus status) {
        return Animal.builder().id(id).species(species).status(status).build();
    }
}
