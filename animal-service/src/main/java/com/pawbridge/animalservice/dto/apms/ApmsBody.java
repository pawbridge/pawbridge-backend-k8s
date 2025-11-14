package com.pawbridge.animalservice.dto.apms;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * APMS API 응답 바디
 *
 * @param <T> item 타입 (ApmsAnimal 등)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApmsBody<T> {

    /**
     * item 배열
     */
    private ApmsItems<T> items;

    /**
     * 한 페이지당 결과 수
     */
    private String numOfRows;

    /**
     * 페이지 번호
     */
    private String pageNo;

    /**
     * 전체 결과 수
     */
    private String totalCount;
}
