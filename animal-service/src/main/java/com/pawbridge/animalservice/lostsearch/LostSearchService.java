package com.pawbridge.animalservice.lostsearch;

import com.pawbridge.animalservice.client.PythonLostSearchClient;
import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.enums.AnimalStatus;
import com.pawbridge.animalservice.enums.Species;
import com.pawbridge.animalservice.mapper.AnimalMapper;
import com.pawbridge.animalservice.repository.AnimalRepository;
import feign.FeignException;
import feign.form.FormData;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LostSearchService {
    static final long MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final Set<String> EVIDENCE = Set.of("FOUND_ON_OR_AFTER_LOST_DATE",
            "DISCOVERY_PLACE_TEXT_MATCH", "REGISTERED_DESCRIPTION_TEXT_MATCH");
    private static final Set<AnimalStatus> ACTIVE = Set.of(AnimalStatus.NOTICE, AnimalStatus.PROTECT);
    private static final Set<AnimalStatus> ACTIVE_AND_RESOLVED = Set.of(
            AnimalStatus.NOTICE, AnimalStatus.PROTECT, AnimalStatus.ADOPTED, AnimalStatus.RETURNED);
    private final PythonLostSearchClient client;
    private final AnimalRepository animals;
    private final AnimalMapper mapper;
    private final String internalKey;
    private final Semaphore searchPermit = new Semaphore(1);

    public LostSearchService(PythonLostSearchClient client, AnimalRepository animals, AnimalMapper mapper,
            @Value("${python-ai-service.internal-api-key:}") String internalKey) {
        this.client = client;
        this.animals = animals;
        this.mapper = mapper;
        this.internalKey = internalKey;
    }

    // No DB transaction spans the remote inference request.
    public LostSearchResponse search(LostSearchRequest request) {
        if (!searchPermit.tryAcquire()) throw unavailable();
        try {
            return searchWithPermit(request);
        } finally {
            searchPermit.release();
        }
    }

    private LostSearchResponse searchWithPermit(LostSearchRequest request) {
        if (request.getSpecies() != Species.DOG && request.getSpecies() != Species.CAT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "강아지 또는 고양이를 선택해 주세요");
        }
        if (request.getImage() == null || request.getImage().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "사진을 선택해 주세요");
        }
        if (request.getImage().getSize() > MAX_IMAGE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "사진은 5MiB 이하이어야 합니다");
        }
        if (internalKey.isBlank()) throw unavailable();
        PythonLostSearchResponse response;
        try {
            // Use a fixed filename/content type; Python validates the actual image bytes.
            response = client.search(internalKey,
                    new FormData("application/octet-stream", "photo", request.getImage().getBytes()),
                    request.getSpecies().name(), request.getLostDate() == null ? null : request.getLostDate().toString(),
                    normalize(request.getRegion()), normalize(request.getDescription()),
                    request.isIncludeAdoptedOrReturned());
        } catch (FeignException ex) {
            if (ex.status() == 413) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "사진은 5MiB 이하이어야 합니다");
            }
            if (ex.status() == 400 || ex.status() == 422) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "JPG·PNG 사진과 검색 조건을 확인해 주세요");
            }
            throw unavailable();
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "사진을 읽을 수 없습니다");
        }
        if (response == null || response.candidates() == null || response.candidates().size() > 20) throw unavailable();
        for (var candidate : response.candidates()) {
            if (candidate == null || candidate.animalId() == null || candidate.animalId() <= 0
                    || candidate.imageScore() == null || !Double.isFinite(candidate.imageScore())
                    || candidate.matchedEvidence() == null
                    || candidate.matchedEvidence().stream().anyMatch(e -> e == null || !EVIDENCE.contains(e))) {
                throw unavailable();
            }
        }
        if (response.candidates().isEmpty()) return new LostSearchResponse(List.of());
        List<Long> ids = response.candidates().stream().map(PythonLostSearchResponse.Candidate::animalId).distinct().toList();
        Map<Long, Animal> current = animals.findWithShelterByIdIn(ids).stream()
                .collect(Collectors.toMap(Animal::getId, Function.identity()));
        Map<Long, List<String>> evidence = response.candidates().stream().collect(Collectors.toMap(
                PythonLostSearchResponse.Candidate::animalId, PythonLostSearchResponse.Candidate::matchedEvidence, (first, next) -> first));
        Set<AnimalStatus> allowedStatuses = request.isIncludeAdoptedOrReturned() ? ACTIVE_AND_RESOLVED : ACTIVE;
        return new LostSearchResponse(ids.stream().filter(current::containsKey).map(current::get)
                .filter(animal -> animal.getSpecies() == request.getSpecies())
                .filter(animal -> allowedStatuses.contains(animal.getStatus()))
                .map(animal -> new LostSearchResponse.Candidate(mapper.toDetailResponse(animal),
                        animal.getShelter() == null ? null : animal.getShelter().getPhone(), evidence.get(animal.getId())))
                .toList());
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "검색을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요");
    }
}
