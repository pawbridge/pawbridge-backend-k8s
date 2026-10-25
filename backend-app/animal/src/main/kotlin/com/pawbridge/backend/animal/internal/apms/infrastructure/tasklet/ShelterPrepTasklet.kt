package com.pawbridge.backend.animal.internal.apms.infrastructure.tasklet

import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsAnimalSnapshot
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsAnimal
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Shelter
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.ShelterRepository
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter
import org.slf4j.LoggerFactory
import org.springframework.batch.core.StepContribution
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.repeat.RepeatStatus
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.util.StringUtils
import java.util.stream.Collectors

/**
 * Step 0: APMS API에서 보호소 정보를 사전 저장하는 Tasklet
 * - Step 1(AnimalItemProcessor) 실행 전 모든 보호소를 DB에 저장
 * - Chunk 트랜잭션 롤백과 무관하게 보호소 데이터 일관성 보장
 * - 이 Step이 FAILED이면 Step Flow에 의해 Job을 즉시 종료 (Step 1 실행 안 함)
 */
open class ShelterPrepTasklet(
    private val searchDocuments: SearchDocumentWriter,
    private val snapshot: ApmsAnimalSnapshot,
    private val shelterRepository: ShelterRepository,
    manager: PlatformTransactionManager,
    timeoutSeconds: Int,
) : Tasklet {

    private val writes: TransactionTemplate

    init {
        if (timeoutSeconds < 1) {
            throw IllegalArgumentException("APMS shelter timeout must be positive")
        }
        writes = TransactionTemplate(manager)
        writes.timeout = timeoutSeconds
    }

    override fun execute(contribution: StepContribution, chunkContext: ChunkContext): RepeatStatus {
        log.info("[BATCH Step 0] 보호소 사전 저장 Tasklet 시작")

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw IllegalStateException("APMS preparation must start outside the writer transaction")
        }
        val allAnimals: List<ApmsAnimal> = snapshot.animals()
        log.info("[BATCH Step 0] APMS API 전체 조회 완료: {} 건", allAnimals.size)

        // careRegNo 기준 중복 제거 (첫 번째 등장 기준)
        // Collectors.toMap keeps the Java HashMap, whose iteration order decides the order of the new shelters.
        val uniqueShelterMap: Map<String, ApmsAnimal> = allAnimals.stream()
            .filter { animal -> StringUtils.hasText(animal.careRegNo) }
            .collect(
                Collectors.toMap(
                    { animal -> animal.careRegNo!! },
                    { animal -> animal },
                    { existing, _ -> existing },
                ),
            )

        writes.executeWithoutResult {
            val allCareRegNos = ArrayList(uniqueShelterMap.keys)
            val existingCareRegNos: Set<String?> = shelterRepository.findByCareRegNoIn(allCareRegNos)
                .map { shelter -> shelter.careRegNo }
                .toSet()

            val newShelters: List<Shelter> = uniqueShelterMap.entries
                .filter { entry -> !existingCareRegNos.contains(entry.key) }
                .map { entry ->
                    val animal = entry.value
                    Shelter.createFromApms(
                        entry.key,
                        if (StringUtils.hasText(animal.careNm)) animal.careNm else "알 수 없는 보호소",
                        animal.careTel,
                        animal.careAddr,
                        animal.chargeNm,
                        animal.orgNm,
                    )
                }

            if (newShelters.isNotEmpty()) {
                shelterRepository.saveAll(newShelters)
                shelterRepository.flush()
                for (shelter in newShelters) {
                    searchDocuments.shelter(shelter.id!!)
                }
                log.info(
                    "[BATCH Step 0] 신규 보호소 {} 건 저장 완료 (기존: {} 건 유지)",
                    newShelters.size,
                    existingCareRegNos.size,
                )
            } else {
                log.info("[BATCH Step 0] 신규 보호소 없음 (기존: {} 건)", existingCareRegNos.size)
            }
        }
        return RepeatStatus.FINISHED
    }

    private companion object {
        private val log = LoggerFactory.getLogger(ShelterPrepTasklet::class.java)
    }
}
