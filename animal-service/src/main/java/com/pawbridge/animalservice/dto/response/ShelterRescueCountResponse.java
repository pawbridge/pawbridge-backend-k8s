package com.pawbridge.animalservice.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 보호소별 구조 건수 내부 전달 DTO
 * - Repository → Service 전달용 (클라이언트에 직접 반환 X)
 * - DB GROUP BY shelter_id 결과를 담아 서버에서 시/도별로 재집계
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ShelterRescueCountResponse {

    /**
     * 보호소 주소 (전체 주소)
     * - 예: "경상남도 창원시 성산구 공단로474번길 117..."
     * - 첫 단어(split(" ")[0])로 시/도 추출
     */
    private String shelterAddress;

    /**
     * 해당 보호소에서 구조된 마릿수
     */
    private Long count;
}
