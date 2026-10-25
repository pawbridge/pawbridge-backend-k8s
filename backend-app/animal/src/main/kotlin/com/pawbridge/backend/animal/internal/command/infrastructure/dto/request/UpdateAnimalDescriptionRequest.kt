package com.pawbridge.backend.animal.internal.command.infrastructure.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.Objects

/**
 * 동물 설명 수정 요청 DTO
 * - PATCH /api/animals/{id}/description
 * - 보호소 회원이 동물에 대한 상세 설명을 추가/수정
 */
class UpdateAnimalDescriptionRequest {
    /**
     * 새로운 설명
     * - 보호소가 직접 작성하는 동물에 대한 상세 설명
     */
    @field:NotBlank(message = "설명은 필수입니다")
    @field:Size(max = 2000, message = "설명은 2000자 이하여야 합니다")
    var description: String? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is UpdateAnimalDescriptionRequest) {
            return false
        }
        if (description != other.description) {
            return false
        }
        return true
    }

    override fun hashCode(): Int {
        return Objects.hash(
            description,
        )
    }

    override fun toString(): String {
        return "UpdateAnimalDescriptionRequest(" +
            "description=$description" +
            ")"
    }

    class UpdateAnimalDescriptionRequestBuilder internal constructor() {
        private var description: String? = null

        fun description(description: String?): UpdateAnimalDescriptionRequestBuilder = apply {
            this.description = description
        }

        fun build(): UpdateAnimalDescriptionRequest {
            val request = UpdateAnimalDescriptionRequest()
            request.description = description
            return request
        }
    }

    companion object {
        @JvmStatic
        fun builder(): UpdateAnimalDescriptionRequestBuilder = UpdateAnimalDescriptionRequestBuilder()
    }
}
