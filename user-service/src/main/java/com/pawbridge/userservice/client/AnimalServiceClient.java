package com.pawbridge.userservice.client;

import com.pawbridge.userservice.dto.response.AnimalResponse;
import com.pawbridge.userservice.dto.response.PageResponse;
import com.pawbridge.userservice.dto.response.ShelterResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.List;

/**
 * Animal Service Feign Client
 * - animal-service와 통신
 * - 보호소 존재 여부 확인에 사용
 */
@FeignClient(name = "animal-service", url = "${service.animal.url:}")
public interface AnimalServiceClient {

    /**
     * 보호소 등록번호 존재 여부 확인
     * @param careRegNo 보호소 등록번호
     * @return 존재 여부
     */
    @GetMapping("/api/v1/shelters/exists/{careRegNo}")
    Boolean existsByCareRegNo(@PathVariable("careRegNo") String careRegNo);

    /**
     * APMS 등록번호로 보호소 조회
     * @param careRegNo 보호소 등록번호
     * @return 보호소 정보
     */
    @GetMapping("/api/v1/shelters/by-care-reg-no/{careRegNo}")
    ShelterResponse getShelterByCareRegNo(@PathVariable("careRegNo") String careRegNo);

    /**
     * 여러 동물 ID로 일괄 조회
     * - 찜 목록 조회 시 사용
     * - mypage 전용 엔드포인트
     */
    @PostMapping("/api/v1/mypage/animals/batch")
    List<AnimalResponse> getAnimalsByIds(@RequestBody List<Long> animalIds);

    /**
     * 보호소별 동물 목록 조회
     * - 보호소 직원이 등록한 동물 조회에 사용
     * - mypage 전용 엔드포인트
     * @param shelterId 보호소 ID
     * @param page 페이지 번호
     * @param size 페이지 크기
     * @param sort 정렬 조건
     * @return 동물 목록
     */
    @GetMapping("/api/v1/mypage/animals/by-shelter/{shelterId}")
    PageResponse<AnimalResponse> getAnimalsByShelterId(
            @PathVariable("shelterId") Long shelterId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort);
}
