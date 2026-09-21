package com.pawbridge.animalservice.service;

import com.pawbridge.animalservice.client.PythonAiServiceClient;
import com.pawbridge.animalservice.client.PythonLostSearchClient;
import com.pawbridge.animalservice.dto.request.SimilarAnimalRequest;
import com.pawbridge.animalservice.dto.response.AnimalResponse;
import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.enums.AnimalStatus;
import com.pawbridge.animalservice.enums.Species;
import com.pawbridge.animalservice.exception.AnimalNotFoundException;
import com.pawbridge.animalservice.mapper.AnimalMapper;
import com.pawbridge.animalservice.repository.AnimalRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import com.pawbridge.animalservice.exception.RecommendationUnavailableException;
import org.springframework.stereotype.Service;

@Service
public class AnimalRecommendationService {
    private final AnimalRepository animals;
    private final AnimalMapper mapper;
    private final PythonAiServiceClient legacyClient;
    private final PythonLostSearchClient dinov3Client;
    private final String backend;
    private final String internalKey;

    public AnimalRecommendationService(AnimalRepository animals, AnimalMapper mapper,
            PythonAiServiceClient legacyClient, PythonLostSearchClient dinov3Client,
            @Value("${pawbridge.recommendation.backend:elasticsearch}") String backend,
            @Value("${python-ai-service.internal-api-key:}") String internalKey) {
        if (!List.of("elasticsearch", "postgresql").contains(backend)) {
            throw new IllegalArgumentException("Unknown recommendation backend");
        }
        this.animals = animals;
        this.mapper = mapper;
        this.legacyClient = legacyClient;
        this.dinov3Client = dinov3Client;
        this.backend = backend;
        this.internalKey = internalKey;
    }

    // Repository transactions end before the remote request; OSIV is off in the PG profile.
    public List<AnimalResponse> recommend(Long id) {
        Animal source = animals.findById(id).orElseThrow(AnimalNotFoundException::new);
        List<Long> ids;
        if ("postgresql".equals(backend)) {
            if (source.getSpecies() != Species.DOG && source.getSpecies() != Species.CAT) {
                return List.of();
            }
            if (internalKey.isBlank()) throw unavailable();
            try {
                ids = dinov3Client.recommend(internalKey, id, source.getSpecies().name());
            } catch (Exception failure) {
                throw unavailable();
            }
            if (ids == null || ids.size() > 6 || ids.stream().anyMatch(value -> value == null || value <= 0)) {
                throw unavailable();
            }
        } else {
            if (source.getImageUrl() == null || source.getImageUrl().isBlank()) return List.of();
            try {
                ids = legacyClient.getSimilarAnimals(new SimilarAnimalRequest(
                        id, source.getImageUrl(), source.getSpecies().name()));
            } catch (Exception failure) {
                return List.of();
            }
            if (ids == null) return List.of();
        }
        List<Long> candidates = ids.stream().filter(Objects::nonNull)
                .filter(candidate -> candidate > 0 && !candidate.equals(id)).distinct().limit(6).toList();
        if (candidates.isEmpty()) return List.of();
        Map<Long, Animal> current = animals.findWithShelterByIdIn(candidates).stream()
                .collect(Collectors.toMap(Animal::getId, Function.identity()));
        return candidates.stream().map(current::get).filter(Objects::nonNull)
                .filter(animal -> animal.getSpecies() == source.getSpecies())
                .filter(animal -> animal.getStatus() == AnimalStatus.NOTICE || animal.getStatus() == AnimalStatus.PROTECT)
                .map(mapper::toResponse).toList();
    }

    private RecommendationUnavailableException unavailable() {
        return new RecommendationUnavailableException();
    }
}
