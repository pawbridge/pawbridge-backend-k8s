package com.pawbridge.backend.animal.internal.command.infrastructure.service;

import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter;
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.request.CreateAnimalRequest;
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.request.UpdateAnimalDescriptionRequest;
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.request.UpdateAnimalStatusRequest;
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.response.AnimalDetailResponse;
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Animal;
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Shelter;
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.ApiSource;
import com.pawbridge.backend.animal.internal.command.infrastructure.mapper.AnimalMapper;
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository;
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.ShelterRepository;
import jakarta.persistence.EntityNotFoundException;

import java.time.LocalDate;
import java.util.List;

import com.pawbridge.backend.animal.api.AnimalCommandActors;
import com.pawbridge.backend.animal.api.AnimalCommandActors.Actor;
import java.util.Objects;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Selected original JSON commands; Root supplies one Animal JPA writer and current-account lookup. */
public final class AnimalCommandService {
    private final SearchDocumentWriter searchDocuments;
    private final AnimalRepository animalRepository;
    private final ShelterRepository shelterRepository;
    private final AnimalMapper mapper;
    private final NoticeNumberGenerator noticeNumberGenerator;
    private final AnimalCommandActors actors;
    private final TransactionTemplate writes;

    public AnimalCommandService(SearchDocumentWriter searchDocuments, AnimalRepository animals,
            ShelterRepository shelters, AnimalMapper mapper,
            NoticeNumberGenerator noticeNumbers, AnimalCommandActors actors, JpaTransactionManager writerTransactions) {
        this.searchDocuments = Objects.requireNonNull(searchDocuments);
        this.animalRepository = Objects.requireNonNull(animals);
        this.shelterRepository = Objects.requireNonNull(shelters);
        this.mapper = Objects.requireNonNull(mapper);
        this.noticeNumberGenerator = Objects.requireNonNull(noticeNumbers);
        this.actors = Objects.requireNonNull(actors);
        if (writerTransactions.getDataSource() != searchDocuments.dataSource())
            throw new IllegalArgumentException("Animal JPA and search must use the same writer DataSource");
        this.writes = new TransactionTemplate(writerTransactions);
        this.writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public AnimalDetailResponse create(Long verifiedUserId, CreateAnimalRequest request) {
        return writes.execute(status -> {
            Actor actor = currentActor(verifiedUserId);
            requireShelter(actor, request.getCareRegNo());
            return create(request);
        });
    }

    public AnimalDetailResponse updateStatus(Long verifiedUserId, Long id, UpdateAnimalStatusRequest request) {
        return writes.execute(status -> updateStatus(ownedAnimal(verifiedUserId, id), request));
    }

    public AnimalDetailResponse updateDescription(Long verifiedUserId, Long id, UpdateAnimalDescriptionRequest request) {
        return writes.execute(status -> updateDescription(ownedAnimal(verifiedUserId, id), request));
    }

    public void delete(Long verifiedUserId, Long id) {
        writes.executeWithoutResult(status -> {
            ownedAnimal(verifiedUserId, id);
            animalRepository.deleteById(id);
        });
    }

    private Actor currentActor(Long verifiedUserId) {
        if (verifiedUserId == null) throw new AnimalCommandForbiddenException();
        Actor actor = actors.find(verifiedUserId);
        if (actor == null || actor.userId() != verifiedUserId || !actor.active() || actor.deleted()
                || !("ROLE_ADMIN".equals(actor.role()) || "ROLE_SHELTER".equals(actor.role())))
            throw new AnimalCommandForbiddenException();
        return actor;
    }

    private Animal ownedAnimal(Long verifiedUserId, Long id) {
        Actor actor = currentActor(verifiedUserId);
        Animal animal = animalRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new EntityNotFoundException("Animal not found: " + id));
        requireShelter(actor, animal.getShelter() == null ? null : animal.getShelter().getCareRegNo());
        return animal;
    }

    private void requireShelter(Actor actor, String careRegNo) {
        if ("ROLE_ADMIN".equals(actor.role())) return;
        if (actor.careRegNo() == null || actor.careRegNo().isBlank()
                || !actor.careRegNo().equals(careRegNo)) throw new AnimalCommandForbiddenException();
    }

    private AnimalDetailResponse create(CreateAnimalRequest request) {
        // Shelter 조회 (careRegNo로)
        Shelter shelter = shelterRepository.findByCareRegNo(request.getCareRegNo())
                .orElseThrow(() -> new EntityNotFoundException("Shelter not found: " + request.getCareRegNo()));

        // apiSource 기본값 처리 (미입력 시 MANUAL)
        if (request.getApiSource() == null) {
            request.setApiSource(ApiSource.MANUAL);
        }

        if (request.getApiSource() != ApiSource.MANUAL &&
                (request.getApmsNoticeNo() == null || request.getApmsNoticeNo().isBlank())) {
            throw new IllegalArgumentException("수동 등록 외 데이터는 공고번호가 필요합니다.");
        }

        // 수동 등록 시 공고번호 자동 생성
        if (request.getApiSource() == ApiSource.MANUAL && 
            (request.getApmsNoticeNo() == null || request.getApmsNoticeNo().isBlank())) {
            request.setApmsNoticeNo(noticeNumberGenerator.generate());
        }

        // DTO → Entity 변환
        Animal animal = mapper.toEntity(request, shelter);

        // 저장
        Animal saved = animalRepository.save(animal);
        refreshSearch(saved.getId());

        // Entity → Response DTO
        return mapper.toDetailResponse(saved);
    }

    private AnimalDetailResponse updateStatus(Animal animal, UpdateAnimalStatusRequest request) {

        animal.updateStatus(request.getNewStatus());

        animalRepository.flush();
        return mapper.toDetailResponse(animal);
    }

    private AnimalDetailResponse updateDescription(Animal animal, UpdateAnimalDescriptionRequest request) {

        animal.updateDescription(request.getDescription());
        refreshSearch(animal.getId());

        return mapper.toDetailResponse(animal);
    }

    private void refreshSearch(Long id) {
        animalRepository.flush();
        searchDocuments.animal(id);
    }
}
