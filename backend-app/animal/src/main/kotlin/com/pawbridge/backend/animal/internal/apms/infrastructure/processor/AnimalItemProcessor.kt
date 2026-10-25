package com.pawbridge.backend.animal.internal.apms.infrastructure.processor

import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsUpdatedAt
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsAnimal
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Animal
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Shelter
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.AnimalStatus
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.ApiSource
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.Gender
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.NeuterStatus
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.Species
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.ShelterRepository
import org.slf4j.LoggerFactory
import org.springframework.batch.core.StepExecution
import org.springframework.batch.core.StepExecutionListener
import org.springframework.batch.item.ItemProcessor
import org.springframework.util.StringUtils
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import java.util.stream.Collectors

/**
 * ApmsAnimal DTO를 Animal Entity로 변환하는 Processor
 * - beforeStep()에서 기존 Animal ID 맵 + Shelter 맵을 HashMap으로 선제 로딩 (SELECT 각 1회)
 * - process()에서 DB 호출 없이 Map 조회만으로 신규/기존 판별
 * - 신규(id==null): Writer에서 saveAll() → INSERT
 * - 기존(id!=null): Writer에서 @Modifying UPDATE → SELECT 없이 UPDATE만 실행
 *
 * Boot registers this as a @StepScope bean, which is proxied with CGLIB: the class and its methods stay open.
 */
open class AnimalItemProcessor(
    private val animalRepository: AnimalRepository,
    private val shelterRepository: ShelterRepository,
) : ItemProcessor<ApmsAnimal, Animal>, StepExecutionListener {

    // Step 시작 시 1회 로딩 — process()에서 DB 호출 제거
    // ConcurrentHashMap: 멀티스레딩 환경에서 여러 스레드가 동시에 읽음 (쓰기는 beforeStep에서만)
    // Null until beforeStep ran, so a process() call before it fails with an NPE like the Java fields did.
    private var existingAnimalIdMap: ConcurrentHashMap<String, Long>? = null // desertionNo → id
    private var shelterCache: ConcurrentHashMap<String, Shelter>? = null // careRegNo → Shelter

    /**
     * Step 실행 전 전체 캐시 구성 (DB SELECT 각 1회)
     */
    override fun beforeStep(stepExecution: StepExecution) {
        // Collectors.toMap keeps the Java behavior: a duplicate key throws instead of silently overwriting.
        val animalIds = ConcurrentHashMap(
            animalRepository.findAllApmsDesertionNoAndId()
                .stream()
                .collect(
                    Collectors.toMap(
                        { row -> row[0] as String },
                        { row -> row[1] as Long },
                    ),
                ),
        )
        existingAnimalIdMap = animalIds
        log.info("[BATCH] 기존 Animal {} 건 캐싱 완료 (existingAnimalIdMap)", animalIds.size)

        val shelters = ConcurrentHashMap(
            shelterRepository.findAll()
                .stream()
                .collect(
                    Collectors.toMap(
                        { shelter -> shelter.careRegNo!! },
                        { shelter -> shelter },
                        { existing, _ -> existing },
                    ),
                ),
        )
        shelterCache = shelters
        log.info("[BATCH] 기존 Shelter {} 건 캐싱 완료 (shelterCache)", shelters.size)
    }

    @Throws(Exception::class)
    override fun process(apmsAnimal: ApmsAnimal): Animal {
        val desertionNo = apmsAnimal.desertionNo
        if (!StringUtils.hasText(desertionNo)) {
            // null 반환은 조용한 필터링 → SkipListener 미호출. 예외로 throw해야 skip 통계에 집계됨
            throw IllegalArgumentException("desertionNo 누락: $apmsAnimal")
        }

        val shelter = findShelterFromCache(apmsAnimal)
        if (shelter == null) {
            val careRegNo = if (StringUtils.hasText(apmsAnimal.careRegNo)) apmsAnimal.careRegNo else "UNKNOWN"
            throw IllegalArgumentException("shelterCache miss: careRegNo=$careRegNo, desertionNo=$desertionNo")
        }

        val existingId = existingAnimalIdMap!!.get(desertionNo!!)
        return if (existingId == null) {
            createNewAnimal(apmsAnimal, shelter)
        } else {
            buildUpdateCarrier(existingId, apmsAnimal, shelter)
        }
    }

    /**
     * shelterCache에서 보호소 조회
     * - ShelterPrepTasklet(Step 0)에서 모든 보호소를 사전 저장하므로 캐시 미스 시 null 반환
     * - null이면 process()에서 해당 아이템을 스킵 처리
     */
    private fun findShelterFromCache(apmsAnimal: ApmsAnimal): Shelter? {
        val careRegNo = if (StringUtils.hasText(apmsAnimal.careRegNo)) apmsAnimal.careRegNo!! else "UNKNOWN"
        return shelterCache!!.get(careRegNo)
    }

    /**
     * 기존 Animal 업데이트 캐리어 (id 설정 → Writer에서 @Modifying UPDATE 경로)
     * - saveAll() 대신 updateAnimalFromApms()로 라우팅하기 위해 id를 담아 반환
     */
    private fun buildUpdateCarrier(existingId: Long, apmsAnimal: ApmsAnimal, shelter: Shelter): Animal {
        return Animal.builder()
            .id(existingId)
            .breed(extractBreedName(apmsAnimal.kindNm))
            .birthYear(extractBirthYear(apmsAnimal.age))
            .weight(apmsAnimal.weight)
            .color(apmsAnimal.colorCd)
            .gender(Gender.fromCode(apmsAnimal.sexCd))
            .neuterStatus(NeuterStatus.fromCode(apmsAnimal.neuterYn))
            .specialMark(apmsAnimal.specialMark)
            .apmsProcessState(apmsAnimal.processState)
            .noticeStartDate(parseDate(apmsAnimal.noticeSdt))
            .noticeEndDate(parseDate(apmsAnimal.noticeEdt))
            .apmsUpdatedAt(parseDateTime(apmsAnimal.updTm))
            .happenDate(parseDate(apmsAnimal.happenDt))
            .happenPlace(apmsAnimal.happenPlace)
            .imageUrl(apmsAnimal.popfile1)
            .imageUrl2(apmsAnimal.popfile2)
            .shelter(shelter)
            .status(AnimalStatus.fromCode(apmsAnimal.processState))
            .build()
    }

    companion object {
        private val log = LoggerFactory.getLogger(AnimalItemProcessor::class.java)

        private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val BIRTH_YEAR_PATTERN: Pattern = Pattern.compile("(\\d{4})")

        /**
         * 신규 Animal 생성 (id = null → Writer에서 saveAll() 경로)
         */
        @JvmStatic
        fun createNewAnimal(apmsAnimal: ApmsAnimal, shelter: Shelter): Animal {
            return Animal.builder()
                .apmsDesertionNo(apmsAnimal.desertionNo)
                .apmsNoticeNo(apmsAnimal.noticeNo)
                .species(Species.fromCode(apmsAnimal.upKindCd))
                .breed(extractBreedName(apmsAnimal.kindNm))
                .birthYear(extractBirthYear(apmsAnimal.age))
                .weight(apmsAnimal.weight)
                .color(apmsAnimal.colorCd)
                .gender(Gender.fromCode(apmsAnimal.sexCd))
                .neuterStatus(NeuterStatus.fromCode(apmsAnimal.neuterYn))
                .specialMark(apmsAnimal.specialMark)
                .apmsProcessState(apmsAnimal.processState)
                .noticeStartDate(parseDate(apmsAnimal.noticeSdt))
                .noticeEndDate(parseDate(apmsAnimal.noticeEdt))
                .apmsUpdatedAt(parseDateTime(apmsAnimal.updTm))
                .happenDate(parseDate(apmsAnimal.happenDt))
                .happenPlace(apmsAnimal.happenPlace)
                .imageUrl(apmsAnimal.popfile1)
                .imageUrl2(apmsAnimal.popfile2)
                .shelter(shelter)
                .status(AnimalStatus.fromCode(apmsAnimal.processState))
                .apiSource(ApiSource.APMS_ANIMAL)
                .favoriteCount(0)
                .description(null)
                .build()
        }

        /**
         * 품종명 추출
         * - "[개] 믹스견" → "믹스견"
         */
        private fun extractBreedName(kindNm: String?): String? {
            if (!StringUtils.hasText(kindNm)) {
                return null
            }
            if (kindNm!!.contains("]")) {
                return javaTrim(kindNm.substring(kindNm.indexOf("]") + 1))
            }
            return javaTrim(kindNm)
        }

        /**
         * 출생연도 추출
         * - "2023(년생)" → 2023
         */
        private fun extractBirthYear(age: String?): Int? {
            if (!StringUtils.hasText(age)) {
                return null
            }
            val matcher = BIRTH_YEAR_PATTERN.matcher(age!!)
            if (matcher.find()) {
                try {
                    return Integer.parseInt(matcher.group(1))
                } catch (e: NumberFormatException) {
                    log.warn("출생연도 파싱 실패: {}", age)
                    return null
                }
            }
            return null
        }

        /**
         * 날짜 파싱 (YYYYMMDD)
         */
        private fun parseDate(dateStr: String?): LocalDate? {
            if (!StringUtils.hasText(dateStr)) {
                return null
            }
            try {
                return LocalDate.parse(dateStr!!.replace("-", ""), DATE_FORMATTER)
            } catch (e: Exception) {
                log.warn("날짜 파싱 실패: {}", dateStr)
                return null
            }
        }

        /**
         * 날짜시간 파싱 (yyyy-MM-dd HH:mm:ss.S 또는 yyyy-MM-dd HH:mm:ss)
         */
        private fun parseDateTime(dateTimeStr: String?): LocalDateTime? {
            return ApmsUpdatedAt.parse(dateTimeStr)
        }

        // Java's String.trim() removes every char <= U+0020; Kotlin's trim() uses a different whitespace definition.
        private fun javaTrim(value: String): String = value.trim { it <= ' ' }
    }
}
