package com.pawbridge.backend.animal.internal.command.infrastructure.dto.request

import com.pawbridge.backend.animal.internal.command.infrastructure.enums.Gender
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.NeuterStatus
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.Species
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.LocalDate

/**
 * 동물 정보 수정 요청 DTO
 * - PUT /api/v1/animals/{id}
 * - 보호소가 직접 등록한(MANUAL) 동물의 정보 항목을 통째로 바꾼다. 비어 있는 선택 항목은 지운다.
 * - 보호소, 공고번호, 출처는 바꿀 수 없고 상태는 PATCH /{id}/status로만 바꾼다.
 * - 항목별 검증은 등록 요청(CreateAnimalRequest)과 같다.
 */
class UpdateAnimalRequest {
    @field:NotNull(message = "공고 시작일은 필수입니다")
    var noticeStartDate: LocalDate? = null

    @field:NotNull(message = "공고 종료일은 필수입니다")
    var noticeEndDate: LocalDate? = null

    @field:NotNull(message = "축종은 필수입니다")
    var species: Species? = null

    @field:NotNull(message = "성별은 필수입니다")
    var gender: Gender? = null

    @field:NotNull(message = "중성화 여부는 필수입니다")
    var neuterStatus: NeuterStatus? = null

    @field:Size(max = 100, message = "품종명은 100자 이하여야 합니다")
    var breed: String? = null

    @field:Min(value = 1900, message = "출생연도는 1900년 이후여야 합니다")
    @field:Max(value = 2100, message = "출생연도는 2100년 이하여야 합니다")
    var birthYear: Int? = null

    @field:Size(max = 50, message = "체중은 50자 이하여야 합니다")
    var weight: String? = null

    @field:Size(max = 100, message = "색상은 100자 이하여야 합니다")
    var color: String? = null

    @field:Size(max = 1000, message = "특징은 1000자 이하여야 합니다")
    var specialMark: String? = null

    var happenDate: LocalDate? = null

    @field:Size(max = 200, message = "발견장소는 200자 이하여야 합니다")
    var happenPlace: String? = null

    @field:Size(max = 500, message = "이미지 URL은 500자 이하여야 합니다")
    var imageUrl: String? = null

    @field:Size(max = 500, message = "이미지 URL은 500자 이하여야 합니다")
    var imageUrl2: String? = null

    @field:Size(max = 2000, message = "설명은 2000자 이하여야 합니다")
    var description: String? = null
}
