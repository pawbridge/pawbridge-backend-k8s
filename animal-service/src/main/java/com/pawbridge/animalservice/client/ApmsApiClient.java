package com.pawbridge.animalservice.client;

import com.pawbridge.animalservice.config.FeignConfig;
import com.pawbridge.animalservice.dto.apms.ApmsAnimal;
import com.pawbridge.animalservice.dto.apms.ApmsRootResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * APMS 유기동물 조회 API FeignClient
 */
@FeignClient(
        name = "apms-api",
        url = "${apms.api.base-url}",
        configuration = FeignConfig.class
)
public interface ApmsApiClient {

    /**
     * 유기동물 조회
     *
     * @param serviceKey 공공데이터 인증키
     * @param pageNo 페이지 번호 (기본값: 1)
     * @param numOfRows 한 페이지 결과 수 (최대: 1000)
     * @param bgnde 구조 시작일 (YYYYMMDD, 선택)
     * @param endde 구조 종료일 (YYYYMMDD, 선택)
     * @param upkind 축종 코드 (417000:개, 422400:고양이, 429900:기타, 선택)
     * @param state 상태 (notice:공고중, protect:보호중, 선택)
     * @return APMS API 응답
     */
    @GetMapping("/abandonmentPublic_v2")
    ApmsRootResponse<ApmsAnimal> getAbandonmentAnimals(
            @RequestParam("serviceKey") String serviceKey,
            @RequestParam("pageNo") int pageNo,
            @RequestParam("numOfRows") int numOfRows,
            @RequestParam(value = "bgnde", required = false) String bgnde,
            @RequestParam(value = "endde", required = false) String endde,
            @RequestParam(value = "upkind", required = false) String upkind,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "_type", defaultValue = "json") String type
    );
}
