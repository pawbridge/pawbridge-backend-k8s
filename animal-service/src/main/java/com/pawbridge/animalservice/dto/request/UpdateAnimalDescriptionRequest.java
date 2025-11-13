package com.pawbridge.animalservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 동물 설명 수정 요청 DTO
 * - PATCH /api/animals/{id}/description
 * - 보호소 회원이 동물에 대한 상세 설명을 추가/수정
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateAnimalDescriptionRequest {

    /**
     * 새로운 설명
     * - 보호소가 직접 작성하는 동물에 대한 상세 설명
     */
    @NotBlank(message = "설명은 필수입니다")
    @Size(max = 2000, message = "설명은 2000자 이하여야 합니다")
    private String description;
}
