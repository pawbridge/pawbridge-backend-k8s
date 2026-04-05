package com.pawbridge.animalservice.batch.tasklet;

import com.pawbridge.animalservice.client.ApmsApiClient;
import com.pawbridge.animalservice.dto.apms.ApmsAnimal;
import com.pawbridge.animalservice.dto.apms.ApmsResponse;
import com.pawbridge.animalservice.dto.apms.ApmsRootResponse;
import com.pawbridge.animalservice.entity.Shelter;
import com.pawbridge.animalservice.repository.ShelterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Step 0: APMS API에서 보호소 정보를 사전 저장하는 Tasklet
 * - Step 1(AnimalItemProcessor) 실행 전 모든 보호소를 DB에 저장
 * - Chunk 트랜잭션 롤백과 무관하게 보호소 데이터 일관성 보장
 * - 이 Step이 FAILED이면 Step Flow에 의해 Job을 즉시 종료 (Step 1 실행 안 함)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShelterPrepTasklet implements Tasklet {

    private final ApmsApiClient apmsApiClient;
    private final ShelterRepository shelterRepository;

    @Value("${apms.api.service-key}")
    private String serviceKey;

    private static final int PAGE_SIZE = 1000;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        log.info("[BATCH Step 0] 보호소 사전 저장 Tasklet 시작");

        List<ApmsAnimal> allAnimals = fetchAllAnimals();
        log.info("[BATCH Step 0] APMS API 전체 조회 완료: {} 건", allAnimals.size());

        // careRegNo 기준 중복 제거 (첫 번째 등장 기준)
        Map<String, ApmsAnimal> uniqueShelterMap = allAnimals.stream()
                .filter(a -> StringUtils.hasText(a.getCareRegNo()))
                .collect(Collectors.toMap(
                        ApmsAnimal::getCareRegNo,
                        a -> a,
                        (existing, replacement) -> existing
                ));

        List<String> allCareRegNos = new ArrayList<>(uniqueShelterMap.keySet());
        Set<String> existingCareRegNos = shelterRepository.findByCareRegNoIn(allCareRegNos)
                .stream()
                .map(Shelter::getCareRegNo)
                .collect(Collectors.toSet());

        List<Shelter> newShelters = uniqueShelterMap.entrySet().stream()
                .filter(e -> !existingCareRegNos.contains(e.getKey()))
                .map(e -> {
                    ApmsAnimal a = e.getValue();
                    return Shelter.createFromApms(
                            e.getKey(),
                            StringUtils.hasText(a.getCareNm()) ? a.getCareNm() : "알 수 없는 보호소",
                            a.getCareTel(),
                            a.getCareAddr(),
                            a.getChargeNm(),
                            a.getOrgNm()
                    );
                })
                .collect(Collectors.toList());

        if (!newShelters.isEmpty()) {
            shelterRepository.saveAll(newShelters);
            log.info("[BATCH Step 0] 신규 보호소 {} 건 저장 완료 (기존: {} 건 유지)",
                    newShelters.size(), existingCareRegNos.size());
        } else {
            log.info("[BATCH Step 0] 신규 보호소 없음 (기존: {} 건)", existingCareRegNos.size());
        }

        return RepeatStatus.FINISHED;
    }

    /**
     * APMS API 전체 페이지 조회
     * - 오류 발생 시 예외 그대로 throw → Step FAILED → Step Flow에 의해 Job 종료
     */
    private List<ApmsAnimal> fetchAllAnimals() {
        List<ApmsAnimal> result = new ArrayList<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        String endde = LocalDate.now().format(formatter);
        String bgnde = LocalDate.now().minusDays(30).format(formatter);

        int page = 1;
        while (true) {
            ApmsRootResponse<ApmsAnimal> rootResponse = apmsApiClient.getAbandonmentAnimals(
                    serviceKey, page, PAGE_SIZE, bgnde, endde, null, null, "json"
            );
            ApmsResponse response = rootResponse != null ? rootResponse.getResponse() : null;

            if (response == null || response.getBody() == null
                    || response.getBody().getItems() == null) {
                // break로 끝내면 부분 로드 상태로 Step 성공 처리 → 후속 Step에서 cache miss 다량 발생
                throw new IllegalStateException(
                        "APMS API 비정상 응답 (null body/items) — 페이지: " + page);
            }

            List<ApmsAnimal> items = response.getBody().getItems().getItem();
            if (items == null || items.isEmpty()) {
                break;
            }

            result.addAll(items);

            if (items.size() < PAGE_SIZE) {
                log.info("[BATCH Step 0] 마지막 페이지 도달. 총 페이지: {}, 총 건수: {}", page, result.size());
                break;
            }
            page++;
        }
        return result;
    }
}
