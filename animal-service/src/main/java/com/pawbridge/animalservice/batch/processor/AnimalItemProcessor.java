package com.pawbridge.animalservice.batch.processor;

import com.pawbridge.animalservice.dto.apms.ApmsAnimal;
import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.entity.Shelter;
import com.pawbridge.animalservice.enums.*;
import com.pawbridge.animalservice.repository.AnimalRepository;
import com.pawbridge.animalservice.repository.ShelterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ApmsAnimal DTO를 Animal Entity로 변환하는 Processor
 * - beforeStep()에서 기존 Animal ID 맵 + Shelter 맵을 HashMap으로 선제 로딩 (SELECT 각 1회)
 * - process()에서 DB 호출 없이 Map 조회만으로 신규/기존 판별
 * - 신규(id==null): Writer에서 saveAll() → INSERT
 * - 기존(id!=null): Writer에서 @Modifying UPDATE → SELECT 없이 UPDATE만 실행
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnimalItemProcessor implements ItemProcessor<ApmsAnimal, Animal>, StepExecutionListener {

    private final AnimalRepository animalRepository;
    private final ShelterRepository shelterRepository;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S");
    private static final Pattern BIRTH_YEAR_PATTERN = Pattern.compile("(\\d{4})");

    // Step 시작 시 1회 로딩 — process()에서 DB 호출 제거
    private Map<String, Long> existingAnimalIdMap;  // desertionNo → id
    private Map<String, Shelter> shelterCache;       // careRegNo → Shelter

    /**
     * Step 실행 전 전체 캐시 구성 (DB SELECT 각 1회)
     */
    @Override
    public void beforeStep(StepExecution stepExecution) {
        existingAnimalIdMap = animalRepository.findAllApmsDesertionNoAndId()
                .stream()
                .collect(Collectors.toMap(
                        row -> (String) row[0],
                        row -> (Long) row[1]
                ));
        log.info("[BATCH] 기존 Animal {} 건 캐싱 완료 (existingAnimalIdMap)", existingAnimalIdMap.size());

        shelterCache = shelterRepository.findAll()
                .stream()
                .collect(Collectors.toMap(Shelter::getCareRegNo, s -> s));
        log.info("[BATCH] 기존 Shelter {} 건 캐싱 완료 (shelterCache)", shelterCache.size());
    }

    @Override
    public Animal process(ApmsAnimal apmsAnimal) throws Exception {
        if (!StringUtils.hasText(apmsAnimal.getDesertionNo())) {
            log.warn("desertionNo가 없는 데이터 스킵: {}", apmsAnimal);
            return null;
        }

        try {
            Shelter shelter = findShelterFromCache(apmsAnimal);
            if (shelter == null) {
                return null; // shelterCache miss → 스킵
            }
            Long existingId = existingAnimalIdMap.get(apmsAnimal.getDesertionNo());

            if (existingId == null) {
                return createNewAnimal(apmsAnimal, shelter);
            } else {
                return buildUpdateCarrier(existingId, apmsAnimal, shelter);
            }

        } catch (Exception e) {
            log.error("ApmsAnimal 처리 중 오류 발생: desertionNo={}", apmsAnimal.getDesertionNo(), e);
            return null;
        }
    }

    /**
     * shelterCache에서 보호소 조회
     * - ShelterPrepTasklet(Step 0)에서 모든 보호소를 사전 저장하므로 캐시 미스 시 null 반환
     * - null이면 process()에서 해당 아이템을 스킵 처리
     */
    private Shelter findShelterFromCache(ApmsAnimal apmsAnimal) {
        String careRegNo = StringUtils.hasText(apmsAnimal.getCareRegNo())
                ? apmsAnimal.getCareRegNo() : "UNKNOWN";
        Shelter shelter = shelterCache.get(careRegNo);
        if (shelter == null) {
            log.warn("[BATCH] shelterCache miss: careRegNo={}, desertionNo={} — 아이템 스킵",
                    careRegNo, apmsAnimal.getDesertionNo());
        }
        return shelter;
    }

    /**
     * 신규 Animal 생성 (id = null → Writer에서 saveAll() 경로)
     */
    private Animal createNewAnimal(ApmsAnimal apmsAnimal, Shelter shelter) {
        return Animal.builder()
                .apmsDesertionNo(apmsAnimal.getDesertionNo())
                .apmsNoticeNo(apmsAnimal.getNoticeNo())
                .species(Species.fromCode(apmsAnimal.getUpKindCd()))
                .breed(extractBreedName(apmsAnimal.getKindNm()))
                .birthYear(extractBirthYear(apmsAnimal.getAge()))
                .weight(apmsAnimal.getWeight())
                .color(apmsAnimal.getColorCd())
                .gender(Gender.fromCode(apmsAnimal.getSexCd()))
                .neuterStatus(NeuterStatus.fromCode(apmsAnimal.getNeuterYn()))
                .specialMark(apmsAnimal.getSpecialMark())
                .apmsProcessState(apmsAnimal.getProcessState())
                .noticeStartDate(parseDate(apmsAnimal.getNoticeSdt()))
                .noticeEndDate(parseDate(apmsAnimal.getNoticeEdt()))
                .apmsUpdatedAt(parseDateTime(apmsAnimal.getUpdTm()))
                .happenDate(parseDate(apmsAnimal.getHappenDt()))
                .happenPlace(apmsAnimal.getHappenPlace())
                .imageUrl(apmsAnimal.getPopfile1())
                .imageUrl2(apmsAnimal.getPopfile2())
                .shelter(shelter)
                .status(AnimalStatus.fromCode(apmsAnimal.getProcessState()))
                .apiSource(ApiSource.APMS_ANIMAL)
                .favoriteCount(0)
                .description(null)
                .build();
    }

    /**
     * 기존 Animal 업데이트 캐리어 (id 설정 → Writer에서 @Modifying UPDATE 경로)
     * - saveAll() 대신 updateAnimalFromApms()로 라우팅하기 위해 id를 담아 반환
     */
    private Animal buildUpdateCarrier(Long existingId, ApmsAnimal apmsAnimal, Shelter shelter) {
        return Animal.builder()
                .id(existingId)
                .breed(extractBreedName(apmsAnimal.getKindNm()))
                .birthYear(extractBirthYear(apmsAnimal.getAge()))
                .weight(apmsAnimal.getWeight())
                .color(apmsAnimal.getColorCd())
                .gender(Gender.fromCode(apmsAnimal.getSexCd()))
                .neuterStatus(NeuterStatus.fromCode(apmsAnimal.getNeuterYn()))
                .specialMark(apmsAnimal.getSpecialMark())
                .apmsProcessState(apmsAnimal.getProcessState())
                .noticeStartDate(parseDate(apmsAnimal.getNoticeSdt()))
                .noticeEndDate(parseDate(apmsAnimal.getNoticeEdt()))
                .apmsUpdatedAt(parseDateTime(apmsAnimal.getUpdTm()))
                .happenDate(parseDate(apmsAnimal.getHappenDt()))
                .happenPlace(apmsAnimal.getHappenPlace())
                .imageUrl(apmsAnimal.getPopfile1())
                .imageUrl2(apmsAnimal.getPopfile2())
                .shelter(shelter)
                .status(AnimalStatus.fromCode(apmsAnimal.getProcessState()))
                .build();
    }

    /**
     * 품종명 추출
     * - "[개] 믹스견" → "믹스견"
     */
    private String extractBreedName(String kindNm) {
        if (!StringUtils.hasText(kindNm)) {
            return null;
        }
        if (kindNm.contains("]")) {
            return kindNm.substring(kindNm.indexOf("]") + 1).trim();
        }
        return kindNm.trim();
    }

    /**
     * 출생연도 추출
     * - "2023(년생)" → 2023
     */
    private Integer extractBirthYear(String age) {
        if (!StringUtils.hasText(age)) {
            return null;
        }
        Matcher matcher = BIRTH_YEAR_PATTERN.matcher(age);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                log.warn("출생연도 파싱 실패: {}", age);
                return null;
            }
        }
        return null;
    }

    /**
     * 날짜 파싱 (YYYYMMDD)
     */
    private LocalDate parseDate(String dateStr) {
        if (!StringUtils.hasText(dateStr)) {
            return null;
        }
        try {
            return LocalDate.parse(dateStr.replace("-", ""), DATE_FORMATTER);
        } catch (Exception e) {
            log.warn("날짜 파싱 실패: {}", dateStr);
            return null;
        }
    }

    /**
     * 날짜시간 파싱 (yyyy-MM-dd HH:mm:ss.S 또는 yyyy-MM-dd HH:mm:ss)
     */
    private LocalDateTime parseDateTime(String dateTimeStr) {
        if (!StringUtils.hasText(dateTimeStr)) {
            return null;
        }
        try {
            return LocalDateTime.parse(dateTimeStr, DATETIME_FORMATTER);
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } catch (Exception ex) {
                log.warn("날짜시간 다중 파싱 모두 실패: {}", dateTimeStr);
                return null;
            }
        }
    }
}
