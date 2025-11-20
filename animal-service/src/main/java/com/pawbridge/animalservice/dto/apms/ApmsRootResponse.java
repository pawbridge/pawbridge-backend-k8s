package com.pawbridge.animalservice.dto.apms;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * APMS API 최상위 응답 (response 필드 Wrapper)
 *
 * @param <T> item 타입 (ApmsAnimal 등)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApmsRootResponse<T> {

    /**
     * 실제 응답 데이터
     */
    private ApmsResponse<T> response;
}
