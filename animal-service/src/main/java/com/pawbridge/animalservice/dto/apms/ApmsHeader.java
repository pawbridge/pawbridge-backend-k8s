package com.pawbridge.animalservice.dto.apms;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * APMS API 응답 헤더
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApmsHeader {

    /**
     * 요청번호
     */
    private String reqNo;

    /**
     * 결과코드
     * - 00: 정상
     * - 기타: 오류
     */
    private String resultCode;

    /**
     * 결과메시지
     */
    private String resultMsg;

    /**
     * 에러메시지
     */
    private String errorMsg;
}
