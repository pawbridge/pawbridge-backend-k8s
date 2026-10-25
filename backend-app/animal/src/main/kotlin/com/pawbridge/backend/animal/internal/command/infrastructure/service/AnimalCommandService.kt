package com.pawbridge.backend.animal.internal.command.infrastructure.service

import com.pawbridge.backend.animal.api.AnimalCommandActors
import com.pawbridge.backend.animal.api.AnimalCommandActors.Actor
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.request.CreateAnimalRequest
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.request.UpdateAnimalDescriptionRequest
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.request.UpdateAnimalStatusRequest
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.response.AnimalDetailResponse
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Animal
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.ApiSource
import com.pawbridge.backend.animal.internal.command.infrastructure.mapper.AnimalMapper
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.ShelterRepository
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter
import jakarta.persistence.EntityNotFoundException
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/** Selected original JSON commands; Root supplies one Animal JPA writer and current-account lookup. */
class AnimalCommandService(
    private val searchDocuments: SearchDocumentWriter,
    private val animalRepository: AnimalRepository,
    private val shelterRepository: ShelterRepository,
    private val mapper: AnimalMapper,
    private val noticeNumberGenerator: NoticeNumberGenerator,
    private val actors: AnimalCommandActors,
    writerTransactions: JpaTransactionManager,
) {
    private val writes: TransactionTemplate

    init {
        if (writerTransactions.dataSource !== searchDocuments.dataSource()) {
            throw IllegalArgumentException("Animal JPA and search must use the same writer DataSource")
        }
        writes = TransactionTemplate(writerTransactions)
        writes.isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
    }

    fun create(verifiedUserId: Long?, request: CreateAnimalRequest): AnimalDetailResponse {
        return writes.execute { _ ->
            val actor = currentActor(verifiedUserId)
            requireShelter(actor, request.careRegNo)
            create(request)
        }!!
    }

    fun updateStatus(verifiedUserId: Long?, id: Long?, request: UpdateAnimalStatusRequest): AnimalDetailResponse {
        return writes.execute { _ -> updateStatus(ownedAnimal(verifiedUserId, id), request) }!!
    }

    fun updateDescription(verifiedUserId: Long?, id: Long?, request: UpdateAnimalDescriptionRequest): AnimalDetailResponse {
        return writes.execute { _ -> updateDescription(ownedAnimal(verifiedUserId, id), request) }!!
    }

    fun delete(verifiedUserId: Long?, id: Long?) {
        writes.executeWithoutResult { _ ->
            ownedAnimal(verifiedUserId, id)
            animalRepository.deleteById(id ?: throw IllegalArgumentException("The given id must not be null"))
        }
    }

    private fun currentActor(verifiedUserId: Long?): Actor {
        if (verifiedUserId == null) {
            throw AnimalCommandForbiddenException()
        }
        val actor: Actor? = actors.find(verifiedUserId)
        if (actor == null ||
            actor.userId() != verifiedUserId ||
            !actor.active() ||
            actor.deleted() ||
            !("ROLE_ADMIN" == actor.role() || "ROLE_SHELTER" == actor.role())
        ) {
            throw AnimalCommandForbiddenException()
        }
        return actor
    }

    private fun ownedAnimal(verifiedUserId: Long?, id: Long?): Animal {
        val actor = currentActor(verifiedUserId)
        val animal = animalRepository.findByIdForUpdate(id)
            .orElseThrow { EntityNotFoundException("Animal not found: $id") }
        requireShelter(actor, animal.shelter?.careRegNo)
        return animal
    }

    private fun requireShelter(actor: Actor, careRegNo: String?) {
        if ("ROLE_ADMIN" == actor.role()) {
            return
        }
        val actorCareRegNo = actor.careRegNo()
        if (actorCareRegNo == null || isBlank(actorCareRegNo) || actorCareRegNo != careRegNo) {
            throw AnimalCommandForbiddenException()
        }
    }

    private fun create(request: CreateAnimalRequest): AnimalDetailResponse {
        // Shelter 조회 (careRegNo로)
        val shelter = shelterRepository.findByCareRegNo(request.careRegNo)
            .orElseThrow { EntityNotFoundException("Shelter not found: " + request.careRegNo) }

        // apiSource 기본값 처리 (미입력 시 MANUAL)
        if (request.apiSource == null) {
            request.apiSource = ApiSource.MANUAL
        }

        if (request.apiSource != ApiSource.MANUAL && hasNoNoticeNumber(request)) {
            throw IllegalArgumentException("수동 등록 외 데이터는 공고번호가 필요합니다.")
        }

        // 수동 등록 시 공고번호 자동 생성
        if (request.apiSource == ApiSource.MANUAL && hasNoNoticeNumber(request)) {
            request.apmsNoticeNo = noticeNumberGenerator.generate()
        }

        // DTO → Entity 변환
        val animal = mapper.toEntity(request, shelter)

        // 저장
        val saved = animalRepository.save(animal)
        refreshSearch(saved.id)

        // Entity → Response DTO
        return mapper.toDetailResponse(saved)
    }

    private fun updateStatus(animal: Animal, request: UpdateAnimalStatusRequest): AnimalDetailResponse {
        animal.updateStatus(request.newStatus)

        animalRepository.flush()
        return mapper.toDetailResponse(animal)
    }

    private fun updateDescription(animal: Animal, request: UpdateAnimalDescriptionRequest): AnimalDetailResponse {
        animal.updateDescription(request.description)
        refreshSearch(animal.id)

        return mapper.toDetailResponse(animal)
    }

    private fun refreshSearch(id: Long?) {
        animalRepository.flush()
        // Java unboxed the id here, so a missing id fails with a NullPointerException.
        searchDocuments.animal(id!!)
    }

    private fun hasNoNoticeNumber(request: CreateAnimalRequest): Boolean {
        val noticeNumber = request.apmsNoticeNo
        return noticeNumber == null || isBlank(noticeNumber)
    }

    private companion object {
        // Java String.isBlank() uses Character.isWhitespace; Kotlin isBlank() also accepts NBSP.
        private fun isBlank(value: String): Boolean = value.all { Character.isWhitespace(it) }
    }
}
