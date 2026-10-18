package com.pawbridge.animalservice.service;

import com.pawbridge.animalservice.dto.request.AnimalSearchRequest;
import com.pawbridge.animalservice.dto.response.AnimalDetailResponse;
import com.pawbridge.animalservice.dto.response.AnimalResponse;
import com.pawbridge.animalservice.enums.AnimalStatus;
import com.pawbridge.animalservice.enums.Species;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Public read contract shared by the current ES backend and the opt-in PostgreSQL backend. */
public interface AnimalQueryService {
    AnimalDetailResponse findByApmsDesertionNo(String number);
    Page<AnimalResponse> searchAnimals(AnimalSearchRequest request, Pageable pageable);
    Page<AnimalResponse> findExpiringSoonAnimals(Pageable pageable);
    Page<AnimalResponse> findByShelterId(Long shelterId, Pageable pageable);
    Page<AnimalResponse> findByShelterIdAndSpecies(Long shelterId, Species species, Pageable pageable);
    Page<AnimalResponse> findByShelterIdAndStatus(Long shelterId, AnimalStatus status, Pageable pageable);
    long countBySpecies(Species species);
    long countByStatus(AnimalStatus status);
    long countByShelterId(Long shelterId);
    long countBySpeciesAndStatus(Species species, AnimalStatus status);
}
