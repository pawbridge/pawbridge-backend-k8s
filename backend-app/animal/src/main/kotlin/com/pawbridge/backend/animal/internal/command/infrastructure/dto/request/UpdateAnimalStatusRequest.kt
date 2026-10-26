package com.pawbridge.backend.animal.internal.command.infrastructure.dto.request

import com.pawbridge.backend.animal.internal.command.infrastructure.enums.AnimalStatus
import jakarta.validation.constraints.NotNull
import java.util.Objects

/**
 * 동물 상태 변경 요청 DTO
 * - PATCH /api/animals/{id}/status
 * - 공고중 → 보호중, 입양대기 → 입양완료 등의 상태 변경
 */
class UpdateAnimalStatusRequest {
    /**
     * 새로운 상태
     * - PROTECT, ADOPTION_PENDING, ADOPTED, RETURNED_TO_OWNER, EUTHANIZED 등
     */
    @field:NotNull(message = "새로운 상태는 필수입니다")
    var newStatus: AnimalStatus? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is UpdateAnimalStatusRequest) {
            return false
        }
        if (newStatus != other.newStatus) {
            return false
        }
        return true
    }

    override fun hashCode(): Int {
        return Objects.hash(
            newStatus,
        )
    }

    override fun toString(): String {
        return "UpdateAnimalStatusRequest(" +
            "newStatus=$newStatus" +
            ")"
    }

    class UpdateAnimalStatusRequestBuilder internal constructor() {
        private var newStatus: AnimalStatus? = null

        fun newStatus(newStatus: AnimalStatus?): UpdateAnimalStatusRequestBuilder = apply {
            this.newStatus = newStatus
        }

        fun build(): UpdateAnimalStatusRequest {
            val request = UpdateAnimalStatusRequest()
            request.newStatus = newStatus
            return request
        }
    }

    companion object {
        @JvmStatic
        fun builder(): UpdateAnimalStatusRequestBuilder = UpdateAnimalStatusRequestBuilder()
    }
}
