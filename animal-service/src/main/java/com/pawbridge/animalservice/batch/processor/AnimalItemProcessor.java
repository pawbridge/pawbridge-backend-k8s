package com.pawbridge.animalservice.batch.processor;

import com.pawbridge.animalservice.dto.apms.ApmsAnimal;
import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.entity.Shelter;
import com.pawbridge.animalservice.enums.*;
import com.pawbridge.animalservice.repository.AnimalRepository;
import com.pawbridge.animalservice.repository.ShelterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ApmsAnimal DTO를 Animal Entity로 변환하는 Processor
 * - 기존 데이터가 있으면 업데이트, 없으면 신규 생성
 * - Shelter 자동 생성 및 매핑
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnimalItemProcessor implements ItemProcessor<ApmsAnimal, Animal> {

    private final AnimalRepository animalRepository;
    private final ShelterRepository shelterRepository;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S");
    private static final Pattern BIRTH_YEAR_PATTERN = Pattern.compile("(\\d{4})");

    @Override
    public Animal process(ApmsAnimal apmsAnimal) throws Exception {
        try {
            // desertionNo 필수 체크
            if (!StringUtils.hasText(apmsAnimal.getDesertionNo())) {
                log.warn("desertionNo가 없는 데이터 스킵: {}", apmsAnimal);
                return null;
            }

            // Shelter 조회 또는 생성
            Shelter shelter = findOrCreateShelter(apmsAnimal);

            // 기존 Animal 조회 (desertionNo 기준)
            Animal animal = animalRepository.findByApmsDesertionNo(apmsAnimal.getDesertionNo())
                    .orElse(null);

            if (animal == null) {
                // 신규 생성
                animal = createNewAnimal(apmsAnimal, shelter);
                log.debug("신규 Animal 생성: desertionNo={}", apmsAnimal.getDesertionNo());
            } else {
                // 기존 데이터 업데이트
                updateExistingAnimal(animal, apmsAnimal, shelter);
                log.debug("기존 Animal 업데이트: desertionNo={}", apmsAnimal.getDesertionNo());
            }

            return animal;

        } catch (Exception e) {
            log.error("ApmsAnimal 처리 중 오류 발생: desertionNo={}", apmsAnimal.getDesertionNo(), e);
            // 오류 발생 시 해당 아이템 스킵
            return null;
        }
    }

    /**
     * Shelter 조회 또는 생성
     */
    private Shelter findOrCreateShelter(ApmsAnimal apmsAnimal) {
        String careRegNo = apmsAnimal.getCareRegNo();

        if (!StringUtils.hasText(careRegNo)) {
            log.warn("careRegNo가 없는 데이터, 기본 Shelter 사용: desertionNo={}", apmsAnimal.getDesertionNo());
            // 기본 Shelter 조회 또는 생성 (careRegNo = "UNKNOWN")
            careRegNo = "UNKNOWN";
        }

        String finalCareRegNo = careRegNo;
        return shelterRepository.findByCareRegNo(finalCareRegNo)
                .orElseGet(() -> {
                    Shelter newShelter = Shelter.builder()
                            .careRegNo(finalCareRegNo)
                            .name(StringUtils.hasText(apmsAnimal.getCareNm()) ? apmsAnimal.getCareNm() : "알 수 없는 보호소")
                            .phone(apmsAnimal.getCareTel())
                            .address(apmsAnimal.getCareAddr())
                            .organizationName(apmsAnimal.getOrgNm())
                            .build();
                    log.info("신규 Shelter 생성: careRegNo={}, name={}", finalCareRegNo, newShelter.getName());
                    return shelterRepository.save(newShelter);
                });
    }

    /**
     * 신규 Animal 생성
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
                .description(null) // APMS 데이터는 description 없음
                .build();
    }

    /**
     * 기존 Animal 업데이트
     * - APMS에서 변경될 수 있는 필드만 업데이트
     */
    private void updateExistingAnimal(Animal animal, ApmsAnimal apmsAnimal, Shelter shelter) {
        animal.updateFromApms(
                extractBreedName(apmsAnimal.getKindNm()),
                extractBirthYear(apmsAnimal.getAge()),
                apmsAnimal.getWeight(),
                apmsAnimal.getColorCd(),
                Gender.fromCode(apmsAnimal.getSexCd()),
                NeuterStatus.fromCode(apmsAnimal.getNeuterYn()),
                apmsAnimal.getSpecialMark(),
                apmsAnimal.getProcessState(),
                parseDate(apmsAnimal.getNoticeSdt()),
                parseDate(apmsAnimal.getNoticeEdt()),
                parseDateTime(apmsAnimal.getUpdTm()),
                parseDate(apmsAnimal.getHappenDt()),
                apmsAnimal.getHappenPlace(),
                apmsAnimal.getPopfile1(),
                apmsAnimal.getPopfile2(),
                shelter,
                AnimalStatus.fromCode(apmsAnimal.getProcessState())
        );
    }

    /**
     * 품종명 추출
     * - "[개] 믹스견" → "믹스견"
     */
    private String extractBreedName(String kindNm) {
        if (!StringUtils.hasText(kindNm)) {
            return null;
        }

        // "[축종] 품종" 형식에서 품종만 추출
        if (kindNm.contains("]")) {
            return kindNm.substring(kindNm.indexOf("]") + 1).trim();
        }

        return kindNm.trim();
    }

    /**
     * 출생연도 추출
     * - "2023(년생)" → 2023
     * - "2025(60일미만)(년생)" → 2025
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
            return LocalDate.parse(dateStr, DATE_FORMATTER);
        } catch (Exception e) {
            log.warn("날짜 파싱 실패: {}", dateStr);
            return null;
        }
    }

    /**
     * 날짜시간 파싱 (yyyy-MM-dd HH:mm:ss.S)
     */
    private LocalDateTime parseDateTime(String dateTimeStr) {
        if (!StringUtils.hasText(dateTimeStr)) {
            return null;
        }

        try {
            return LocalDateTime.parse(dateTimeStr, DATETIME_FORMATTER);
        } catch (Exception e) {
            log.warn("날짜시간 파싱 실패: {}", dateTimeStr);
            return null;
        }
    }
}
