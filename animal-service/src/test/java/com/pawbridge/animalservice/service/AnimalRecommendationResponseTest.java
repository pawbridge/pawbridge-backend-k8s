package com.pawbridge.animalservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pawbridge.animalservice.client.PythonAiServiceClient;
import com.pawbridge.animalservice.client.PythonLostSearchClient;
import com.pawbridge.animalservice.controller.AnimalController;
import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.enums.AnimalStatus;
import com.pawbridge.animalservice.enums.Species;
import com.pawbridge.animalservice.facade.AnimalFacade;
import com.pawbridge.animalservice.mapper.AnimalMapper;
import com.pawbridge.animalservice.repository.AnimalRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AnimalRecommendationResponseTest {
    private final AnimalRepository animals = mock(AnimalRepository.class);
    private final PythonAiServiceClient legacy = mock(PythonAiServiceClient.class);
    private final PythonLostSearchClient dinov3 = mock(PythonLostSearchClient.class);

    @ParameterizedTest
    @ValueSource(strings = {"elasticsearch", "postgresql"})
    void stored_card_fields_are_returned_in_similarity_order_with_one_candidate_query(String backend) throws Exception {
        Animal first = Animal.builder().id(4L).species(Species.DOG).status(AnimalStatus.PROTECT)
                .weight("0.69(Kg)").color("레몬색&흰색").happenDate(LocalDate.of(2026, 9, 29)).build();
        Animal second = Animal.builder().id(5L).species(Species.DOG).status(AnimalStatus.NOTICE)
                .weight("12").color("검정").happenDate(LocalDate.of(2026, 9, 28)).build();
        MockMvc mvc = recommendations(backend, List.of(first, second), List.of(second, first));

        mvc.perform(get("/api/v1/animals/1/similar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(4))
                .andExpect(jsonPath("$[0].weight").value("0.69(Kg)"))
                .andExpect(jsonPath("$[0].color").value("레몬색&흰색"))
                .andExpect(jsonPath("$[0].happenDate").value("2026-09-29"))
                .andExpect(jsonPath("$[1].id").value(5))
                .andExpect(jsonPath("$[1].weight").value("12"))
                .andExpect(jsonPath("$[1].color").value("검정"))
                .andExpect(jsonPath("$[1].happenDate").value("2026-09-28"));
        verify(animals).findById(1L);
        verify(animals).findWithShelterByIdIn(List.of(4L, 5L));
        verifyNoMoreInteractions(animals);
    }

    @ParameterizedTest
    @ValueSource(strings = {"elasticsearch", "postgresql"})
    void unavailable_card_fields_are_omitted_without_losing_the_recommendation(String backend) throws Exception {
        Animal candidate = Animal.builder().id(4L).species(Species.DOG).status(AnimalStatus.PROTECT).build();
        MockMvc mvc = recommendations(backend, List.of(candidate), List.of(candidate));

        mvc.perform(get("/api/v1/animals/1/similar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(4))
                .andExpect(jsonPath("$[0].weight").doesNotExist())
                .andExpect(jsonPath("$[0].color").doesNotExist())
                .andExpect(jsonPath("$[0].happenDate").doesNotExist());
    }

    @Test
    void ordinary_list_json_keeps_its_existing_fields_without_recommendation_only_fields() {
        Animal animal = Animal.builder().id(4L).species(Species.DOG).status(AnimalStatus.PROTECT)
                .weight("0.69(Kg)").color("레몬색&흰색").happenDate(LocalDate.of(2026, 9, 29)).build();

        var json = new ObjectMapper().valueToTree(new AnimalMapper().toResponse(animal));

        assertThat(json.path("id").asLong()).isEqualTo(4L);
        assertThat(json.has("weight")).isFalse();
        assertThat(json.has("color")).isFalse();
        assertThat(json.has("happenDate")).isFalse();
    }

    private MockMvc recommendations(String backend, List<Animal> ordered, List<Animal> stored) {
        Animal source = Animal.builder().id(1L).species(Species.DOG).status(AnimalStatus.PROTECT)
                .imageUrl("https://example.test/dog.jpg").build();
        List<Long> ids = ordered.stream().map(Animal::getId).toList();
        when(animals.findById(1L)).thenReturn(Optional.of(source));
        when(animals.findWithShelterByIdIn(ids)).thenReturn(stored);
        if ("postgresql".equals(backend)) {
            when(dinov3.recommend("test-key", 1L, "DOG")).thenReturn(ids);
        } else {
            when(legacy.getSimilarAnimals(any())).thenReturn(ids);
        }
        AnimalRecommendationService service = new AnimalRecommendationService(
                animals, new AnimalMapper(), legacy, dinov3, backend, "test-key");
        AnimalFacade facade = mock(AnimalFacade.class);
        when(facade.getSimilarAnimals(1L)).thenAnswer(invocation -> service.recommend(1L));
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return MockMvcBuilders.standaloneSetup(new AnimalController(facade))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
    }
}
