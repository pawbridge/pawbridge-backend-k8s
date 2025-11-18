package com.pawbridge.animalservice.dto.apms;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * APMS API 응답 (response 필드 내부)
 *
 * @param <T> item 타입 (ApmsAnimal 등)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApmsResponse<T> {

    /**
     * 헤더
     */
    private ApmsHeader header;

    /**
     * 바디
     */
    private ApmsBody<T> body;
}
