package com.pawbridge.userservice.client;

import com.pawbridge.userservice.dto.response.AnimalResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import java.util.List;

/**
 * Animal Service Feign Client
 * - animal-service와 통신
 * - 보호소 존재 여부 확인에 사용
 */
@FeignClient(name = "animal-service")
public interface AnimalServiceClient {

    /**
     * 보호소 등록번호 존재 여부 확인
     * @param careRegNo 보호소 등록번호
     * @return 존재 여부
     */
    @GetMapping("/api/v1/shelters/exists/{careRegNo}")
    Boolean existsByCareRegNo(@PathVariable("careRegNo") String careRegNo);

    /**
     * 여러 동물 ID로 일괄 조회
     * - 찜 목록 조회 시 사용
     */
    @PostMapping("/api/v1/animals/batch")
    List<AnimalResponse> getAnimalsByIds(@RequestBody List<Long> animalIds);
}
