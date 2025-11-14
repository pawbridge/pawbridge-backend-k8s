package com.pawbridge.animalservice.dto.apms;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * APMS API item 배열 래퍼
 *
 * @param <T> item 타입 (ApmsAnimal 등)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApmsItems<T> {

    /**
     * item 목록
     */
    private List<T> item;
}
