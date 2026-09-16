package com.pawbridge.animalservice.lostsearch;

import com.pawbridge.animalservice.client.PythonLostSearchClient;
import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.entity.Shelter;
import com.pawbridge.animalservice.enums.AnimalStatus;
import com.pawbridge.animalservice.enums.Species;
import com.pawbridge.animalservice.mapper.AnimalMapper;
import com.pawbridge.animalservice.repository.AnimalRepository;
import feign.FeignException;
import feign.Request;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LostSearchServiceTest {
    private final PythonLostSearchClient client = mock(PythonLostSearchClient.class);
    private final AnimalRepository animals = mock(AnimalRepository.class);
    private final LostSearchService service = new LostSearchService(client, animals, new AnimalMapper(), "test-key");
    private LostSearchRequest request;

    @BeforeEach
    void setUp() {
        request = new LostSearchRequest();
        request.setImage(new MockMultipartFile("image", "private-name.png", "image/png", new byte[]{1, 2, 3}));
        request.setSpecies(Species.DOG);
    }

    @Test
    void givenOptionalConditions__whenSearch__thenForwardNormalizedConditionsAndPrivateFile() {
        request.setLostDate(LocalDate.of(2026, 9, 8));
        request.setRegion("  상주시  ");
        request.setDescription("  흰색 귀  ");
        when(client.search(anyString(), any(), anyString(), any(), any(), any(), anyBoolean())).thenReturn(new PythonLostSearchResponse(List.of()));
        assertThat(service.search(request).candidates()).isEmpty();
        var file = ArgumentCaptor.forClass(feign.form.FormData.class);
        verify(client).search(eq("test-key"), file.capture(), eq("DOG"), eq("2026-09-08"), eq("상주시"), eq("흰색 귀"), eq(false));
        assertThat(file.getValue().getFileName()).isEqualTo("photo");
        assertThat(file.getValue().getData()).containsExactly(1, 2, 3);
        verifyNoInteractions(animals);
    }

    @Test
    void givenMissingChangedSpeciesAndEndedAnimals__whenDefaultSearch__thenReturnOnlyCurrentActiveAnimals() {
        when(client.search(anyString(), any(), anyString(), any(), any(), any(), anyBoolean())).thenReturn(new PythonLostSearchResponse(List.of(
                candidate(3L), candidate(99L), candidate(2L), candidate(4L), candidate(1L), candidate(5L), candidate(3L))));
        Animal adopted = animal(3L, Species.DOG);
        when(adopted.getStatus()).thenReturn(AnimalStatus.ADOPTED);
        Animal first = animal(1L, Species.DOG);
        Animal changedSpecies = animal(2L, Species.CAT);
        Animal protectedAnimal = animal(4L, Species.DOG);
        when(protectedAnimal.getStatus()).thenReturn(AnimalStatus.PROTECT);
        when(protectedAnimal.getHappenPlace()).thenReturn("실제 발견 장소");
        Shelter shelter = mock(Shelter.class);
        when(shelter.getPhone()).thenReturn("02-000-0000");
        when(protectedAnimal.getShelter()).thenReturn(shelter);
        Animal euthanized = animal(5L, Species.DOG);
        when(euthanized.getStatus()).thenReturn(AnimalStatus.EUTHANIZED);
        when(animals.findWithShelterByIdIn(List.of(3L, 99L, 2L, 4L, 1L, 5L)))
                .thenReturn(List.of(first, changedSpecies, adopted, protectedAnimal, euthanized));
        var results = service.search(request).candidates();
        assertThat(results).extracting(c -> c.animal().getId()).containsExactly(4L, 1L);
        assertThat(results.get(0).animal().getHappenPlace()).isEqualTo("실제 발견 장소");
        assertThat(results.get(0).shelterPhone()).isEqualTo("02-000-0000");
    }

    @Test
    void givenResolvedOption__whenSearch__thenIncludeAdoptedAndReturnedButExcludeDeath() {
        request.setIncludeAdoptedOrReturned(true);
        when(client.search(anyString(), any(), anyString(), any(), any(), any(), eq(true))).thenReturn(new PythonLostSearchResponse(List.of(
                candidate(3L), candidate(4L), candidate(5L), candidate(1L))));
        Animal adopted = animal(3L, Species.DOG);
        when(adopted.getStatus()).thenReturn(AnimalStatus.ADOPTED);
        Animal returned = animal(4L, Species.DOG);
        when(returned.getStatus()).thenReturn(AnimalStatus.RETURNED);
        Animal euthanized = animal(5L, Species.DOG);
        when(euthanized.getStatus()).thenReturn(AnimalStatus.EUTHANIZED);
        Animal active = animal(1L, Species.DOG);
        when(animals.findWithShelterByIdIn(List.of(3L, 4L, 5L, 1L))).thenReturn(List.of(active, returned, euthanized, adopted));
        assertThat(service.search(request).candidates()).extracting(c -> c.animal().getId()).containsExactly(3L, 4L, 1L);
        verify(client).search(anyString(), any(), eq("DOG"), isNull(), isNull(), isNull(), eq(true));
    }

    @Test
    void givenNoInternalKey__whenSearch__thenFailClosed() {
        assertThatThrownBy(() -> new LostSearchService(client, animals, new AnimalMapper(), "").search(request))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
        verifyNoInteractions(client, animals);
    }

    @Test
    void givenEmptyOrOversizedPhoto__whenSearch__thenRejectBeforeInference() {
        for (int size : new int[]{0, 5 * 1024 * 1024 + 1}) {
            request.setImage(new MockMultipartFile("image", new byte[size]));
            assertThatThrownBy(() -> service.search(request)).isInstanceOfSatisfying(ResponseStatusException.class,
                    e -> assertThat(e.getStatusCode().value()).isEqualTo(size == 0 ? 400 : 413));
        }
        verifyNoInteractions(client, animals);
    }

    @Test
    void givenOtherSpecies__whenSearch__thenRejectBeforeInference() {
        request.setSpecies(Species.ETC);
        assertThatThrownBy(() -> service.search(request)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
        verifyNoInteractions(client, animals);
    }

    @ParameterizedTest
    @CsvSource({"400,400", "422,400", "413,413", "401,503", "429,503", "500,503", "503,503"})
    void givenPythonFailure__whenSearch__thenMapStatusWithoutLeakingDetails(int upstream, int expected) {
        var rawRequest = Request.create(Request.HttpMethod.POST, "http://localhost", Map.of(), new byte[0], StandardCharsets.UTF_8, null);
        var response = feign.Response.builder().status(upstream).reason("private-upstream").request(rawRequest)
                .body("private-photo-or-key", StandardCharsets.UTF_8).build();
        when(client.search(anyString(), any(), anyString(), any(), any(), any(), anyBoolean())).thenThrow(FeignException.errorStatus("search", response));
        assertThatThrownBy(() -> service.search(request)).isInstanceOfSatisfying(ResponseStatusException.class, e -> {
            assertThat(e.getStatusCode().value()).isEqualTo(expected);
            assertThat(e.getMessage()).doesNotContain("private-");
            assertThat(e.getCause()).isNull();
        });
        verifyNoInteractions(animals);
    }

    @Test
    void givenMalformedResponse__whenSearch__thenDoNotReturnEmptySuccessOrQueryDatabase() {
        for (var response : new PythonLostSearchResponse[]{null, new PythonLostSearchResponse(null),
                new PythonLostSearchResponse(List.of(new PythonLostSearchResponse.Candidate(1L, Double.NaN, List.of()))),
                new PythonLostSearchResponse(List.of(new PythonLostSearchResponse.Candidate(1L, .8, List.of("UNKNOWN"))))}) {
            when(client.search(anyString(), any(), anyString(), any(), any(), any(), anyBoolean())).thenReturn(response);
            assertThatThrownBy(() -> service.search(request)).isInstanceOfSatisfying(ResponseStatusException.class,
                    e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
        }
        verifyNoInteractions(animals);
    }

    @Test
    void givenInferenceInProgress__whenAnotherRequestArrives__thenRejectWithoutSecondCall() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(client.search(anyString(), any(), anyString(), any(), any(), any(), anyBoolean())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("test timeout");
            return new PythonLostSearchResponse(List.of());
        });
        var running = java.util.concurrent.CompletableFuture.supplyAsync(() -> service.search(request));
        try {
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.search(request)).isInstanceOfSatisfying(ResponseStatusException.class,
                    e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
        } finally {
            release.countDown();
        }
        assertThat(running.get(5, java.util.concurrent.TimeUnit.SECONDS).candidates()).isEmpty();
        verify(client, times(1)).search(anyString(), any(), anyString(), any(), any(), any(), anyBoolean());
    }

    private static PythonLostSearchResponse.Candidate candidate(long id) {
        return new PythonLostSearchResponse.Candidate(id, .8, List.of());
    }

    private static Animal animal(long id, Species species) {
        Animal animal = mock(Animal.class);
        when(animal.getId()).thenReturn(id);
        when(animal.getSpecies()).thenReturn(species);
        when(animal.getStatus()).thenReturn(AnimalStatus.NOTICE);
        return animal;
    }
}
