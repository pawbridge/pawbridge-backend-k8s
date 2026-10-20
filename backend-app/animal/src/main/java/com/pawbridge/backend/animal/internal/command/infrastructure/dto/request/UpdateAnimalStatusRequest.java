package com.pawbridge.backend.animal.internal.command.infrastructure.dto.request;

import com.pawbridge.backend.animal.internal.command.infrastructure.enums.AnimalStatus;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 동물 상태 변경 요청 DTO
 * - PATCH /api/animals/{id}/status
 * - 공고중 → 보호중, 입양대기 → 입양완료 등의 상태 변경
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateAnimalStatusRequest {

    /**
     * 새로운 상태
     * - PROTECT, ADOPTION_PENDING, ADOPTED, RETURNED_TO_OWNER, EUTHANIZED 등
     */
    @NotNull(message = "새로운 상태는 필수입니다")
    private AnimalStatus newStatus;
}
