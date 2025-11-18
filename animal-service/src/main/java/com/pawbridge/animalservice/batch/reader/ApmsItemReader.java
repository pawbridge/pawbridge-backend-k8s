package com.pawbridge.animalservice.batch.reader;

import com.pawbridge.animalservice.client.ApmsApiClient;
import com.pawbridge.animalservice.dto.apms.ApmsAnimal;
import com.pawbridge.animalservice.dto.apms.ApmsApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * APMS API로부터 유기동물 데이터를 읽어오는 ItemReader
 * - 페이징 처리를 통해 전체 데이터를 순차적으로 읽음
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApmsItemReader implements ItemReader<ApmsAnimal> {

    private final ApmsApiClient apmsApiClient;

    @Value("${apms.api.service-key}")
    private String serviceKey;

    private static final int PAGE_SIZE = 500; // APMS API 페이지 크기 (Chunk와 동일하게 설정)

    private int currentPage = 1;
    private List<ApmsAnimal> currentItems = new ArrayList<>();
    private int currentIndex = 0;
    private boolean isExhausted = false;

    @Override
    public ApmsAnimal read() {
        // 이미 모든 데이터를 읽었으면 null 반환
        if (isExhausted) {
            return null;
        }

        // 현재 페이지의 모든 아이템을 읽었으면 다음 페이지 로드
        if (currentIndex >= currentItems.size()) {
            loadNextPage();
            currentIndex = 0;
        }

        // 페이지 로드 후에도 아이템이 없으면 종료
        if (currentItems.isEmpty()) {
            isExhausted = true;
            return null;
        }

        // 현재 아이템 반환 후 인덱스 증가
        return currentItems.get(currentIndex++);
    }

    /**
     * 다음 페이지 데이터 로드
     */
    private void loadNextPage() {
        try {
            log.info("APMS API 호출 - 페이지: {}, 페이지 크기: {}", currentPage, PAGE_SIZE);

            ApmsApiResponse<ApmsAnimal> response = apmsApiClient.getAbandonmentAnimals(
                    serviceKey,
                    currentPage,
                    PAGE_SIZE,
                    null, // bgnde
                    null, // endde
                    null, // upkind
                    null, // state
                    "json"
            );

            // 응답 검증
            if (response == null || response.getBody() == null || response.getBody().getItems() == null) {
                log.warn("APMS API 응답이 비어있습니다. 페이지: {}", currentPage);
                currentItems = new ArrayList<>();
                return;
            }

            // 아이템 추출
            currentItems = response.getBody().getItems().getItem();
            if (currentItems == null) {
                currentItems = new ArrayList<>();
            }

            log.info("APMS API 응답 수신 - 페이지: {}, 조회된 아이템 수: {}", currentPage, currentItems.size());

            // 다음 페이지로 이동
            currentPage++;

            // 아이템이 PAGE_SIZE보다 작으면 마지막 페이지
            if (currentItems.size() < PAGE_SIZE) {
                log.info("마지막 페이지 도달 - 총 페이지: {}", currentPage - 1);
            }

        } catch (Exception e) {
            log.error("APMS API 호출 중 오류 발생 - 페이지: {}", currentPage, e);
            currentItems = new ArrayList<>();
        }
    }

    /**
     * Reader 상태 초기화 (Job 재실행 시 사용)
     */
    public void reset() {
        currentPage = 1;
        currentItems = new ArrayList<>();
        currentIndex = 0;
        isExhausted = false;
        log.info("ApmsItemReader 상태 초기화");
    }
}
