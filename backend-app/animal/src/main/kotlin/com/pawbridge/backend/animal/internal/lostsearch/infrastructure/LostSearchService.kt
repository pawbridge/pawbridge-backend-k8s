package com.pawbridge.backend.animal.internal.lostsearch.infrastructure

import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Animal
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.AnimalStatus
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.Species
import com.pawbridge.backend.animal.internal.command.infrastructure.mapper.AnimalMapper
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository
import feign.FeignException
import feign.form.FormData
import org.springframework.http.HttpStatus
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.io.IOException
import java.util.concurrent.Semaphore

class LostSearchService(
    private val client: PythonLostSearchClient,
    private val animals: AnimalRepository,
    private val mapper: AnimalMapper,
    private val internalKey: String,
    readerTransactions: JpaTransactionManager,
) {
    private val reads: TransactionTemplate = TransactionTemplate(readerTransactions)
    private val searchPermit = Semaphore(1)

    init {
        reads.isReadOnly = true
    }

    // No DB transaction spans the remote inference request.
    fun search(request: LostSearchRequest): LostSearchResponse {
        if (!searchPermit.tryAcquire()) {
            throw unavailable()
        }
        try {
            return searchWithPermit(request)
        } finally {
            searchPermit.release()
        }
    }

    private fun searchWithPermit(request: LostSearchRequest): LostSearchResponse {
        if (request.species != Species.DOG && request.species != Species.CAT) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "강아지 또는 고양이를 선택해 주세요")
        }
        val image = request.image
        if (image == null || image.isEmpty) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "사진을 선택해 주세요")
        }
        if (image.size > MAX_IMAGE_BYTES) {
            throw ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "사진은 5MiB 이하이어야 합니다")
        }
        if (isBlank(internalKey)) {
            throw unavailable()
        }
        val response: PythonLostSearchResponse?
        try {
            // Use a fixed filename/content type; Python validates the actual image bytes.
            response = client.search(
                internalKey,
                FormData("application/octet-stream", "photo", image.bytes),
                request.species!!.name,
                request.lostDate?.toString(),
                normalize(request.region),
                normalize(request.description),
                request.includeAdoptedOrReturned,
            )
        } catch (ex: FeignException) {
            if (ex.status() == 413) {
                throw ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "사진은 5MiB 이하이어야 합니다")
            }
            if (ex.status() == 400 || ex.status() == 422) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "JPG·PNG 사진과 검색 조건을 확인해 주세요")
            }
            throw unavailable()
        } catch (ex: IOException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "사진을 읽을 수 없습니다")
        }
        val candidates = response?.candidates
        if (response == null || candidates == null || candidates.size > 20) {
            throw unavailable()
        }
        for (candidate in candidates) {
            if (candidate == null ||
                candidate.animalId == null ||
                candidate.animalId <= 0 ||
                candidate.imageScore == null ||
                !candidate.imageScore.isFinite() ||
                candidate.matchedEvidence == null ||
                candidate.matchedEvidence.any { evidence -> evidence == null || !EVIDENCE.contains(evidence) }
            ) {
                throw unavailable()
            }
        }
        if (candidates.isEmpty()) {
            return LostSearchResponse(emptyList())
        }
        val verified = response
        return reads.execute { currentCandidates(request, verified) }!!
    }

    private fun currentCandidates(request: LostSearchRequest, response: PythonLostSearchResponse): LostSearchResponse {
        val verifiedCandidates = response.candidates!!
        val ids = verifiedCandidates.map { candidate -> candidate!!.animalId!! }.distinct()
        val current = LinkedHashMap<Long?, Animal>()
        for (animal in animals.findWithShelterByIdIn(ids)) {
            // Collectors.toMap rejects a repeated key instead of silently replacing the first entry.
            val previous = current.putIfAbsent(animal.id, animal)
            if (previous != null) {
                throw IllegalStateException("Duplicate key " + animal.id)
            }
        }
        val evidence = HashMap<Long?, List<String?>?>()
        for (candidate in verifiedCandidates) {
            // The first evidence list for an animal wins.
            evidence.putIfAbsent(candidate!!.animalId, candidate.matchedEvidence)
        }
        val allowedStatuses = if (request.includeAdoptedOrReturned) ACTIVE_AND_RESOLVED else ACTIVE
        val matches = ids
            .filter { id -> current.containsKey(id) }
            .map { id -> current[id]!! }
            .filter { animal -> animal.species == request.species }
            .filter { animal -> allowedStatuses.contains(animal.status) }
            .map { animal ->
                LostSearchResponse.Candidate(
                    mapper.toDetailResponse(animal),
                    animal.shelter?.phone,
                    evidence[animal.id],
                )
            }
        return LostSearchResponse(matches)
    }

    // Java String.isBlank(): empty or only Character.isWhitespace characters (Kotlin isBlank also accepts NBSP).
    private fun isBlank(value: String): Boolean = value.all { Character.isWhitespace(it) }

    // Java String.strip() removes Character.isWhitespace characters from both ends.
    private fun strip(value: String): String = value.trim { Character.isWhitespace(it) }

    private fun normalize(value: String?): String? {
        return if (value == null || isBlank(value)) null else strip(value)
    }

    private fun unavailable(): ResponseStatusException {
        return ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "검색을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요")
    }

    companion object {
        const val MAX_IMAGE_BYTES: Long = 5 * 1024 * 1024

        // Set.of rejects null lookups with a NullPointerException; the Java sets are kept for that behavior.
        private val EVIDENCE: Set<String> = java.util.Set.of(
            "FOUND_ON_OR_AFTER_LOST_DATE",
            "DISCOVERY_PLACE_TEXT_MATCH",
            "REGISTERED_DESCRIPTION_TEXT_MATCH",
        )
        private val ACTIVE: Set<AnimalStatus> = java.util.Set.of(AnimalStatus.NOTICE, AnimalStatus.PROTECT)
        private val ACTIVE_AND_RESOLVED: Set<AnimalStatus> = java.util.Set.of(
            AnimalStatus.NOTICE,
            AnimalStatus.PROTECT,
            AnimalStatus.ADOPTED,
            AnimalStatus.RETURNED,
        )
    }
}
