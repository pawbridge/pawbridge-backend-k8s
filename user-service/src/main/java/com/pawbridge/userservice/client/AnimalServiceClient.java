package com.pawbridge.userservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

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
}
