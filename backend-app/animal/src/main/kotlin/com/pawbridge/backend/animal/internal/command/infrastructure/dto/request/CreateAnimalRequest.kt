package com.pawbridge.backend.animal.internal.command.infrastructure.dto.request

import com.pawbridge.backend.animal.internal.command.infrastructure.enums.AnimalStatus
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.ApiSource
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.Gender
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.NeuterStatus
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.Species
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.LocalDate
import java.util.Objects

/**
 * 동물 등록 요청 DTO
 * - 보호소 회원/관리자가 동물을 수동 등록할 때 사용
 * - APMS 배치는 별도 DTO(ApmsAnimalDto) 사용
 */
class CreateAnimalRequest {
    // 필수 필드 (9개)
    /**
     * 보호소 등록번호
     * - 예: "411310000001"
     * - /api/users/me에서 careRegNo 획득
     */
    @field:NotBlank(message = "보호소 등록번호는 필수입니다")
    @field:Size(max = 50, message = "보호소 등록번호는 50자 이하여야 합니다")
    var careRegNo: String? = null

    /**
     * 공고번호 (선택)
     * - APMS 데이터: 직접 입력 (예: "경기-양평-2025-00429")
     * - 수동 등록: 미입력 시 자동 생성 (예: "MAN-251230-000001")
     */
    @field:Size(max = 100, message = "공고번호는 100자 이하여야 합니다")
    var apmsNoticeNo: String? = null

    /**
     * 공고 시작일
     */
    @field:NotNull(message = "공고 시작일은 필수입니다")
    var noticeStartDate: LocalDate? = null

    /**
     * 공고 종료일
     */
    @field:NotNull(message = "공고 종료일은 필수입니다")
    var noticeEndDate: LocalDate? = null

    /**
     * 축종 (개/고양이/기타)
     */
    @field:NotNull(message = "축종은 필수입니다")
    var species: Species? = null

    /**
     * 성별 (수컷/암컷/미상)
     */
    @field:NotNull(message = "성별은 필수입니다")
    var gender: Gender? = null

    /**
     * 중성화 여부 (예/아니오/미상)
     */
    @field:NotNull(message = "중성화 여부는 필수입니다")
    var neuterStatus: NeuterStatus? = null

    /**
     * 동물 상태
     * - NOTICE(공고중), PROTECT(보호중), ADOPTION_PENDING(입양대기), ADOPTED(입양완료) 등
     */
    @field:NotNull(message = "동물 상태는 필수입니다")
    var status: AnimalStatus? = null

    /**
     * 데이터 출처 (선택, 기본값: MANUAL)
     * - MANUAL(수동등록), APMS_ANIMAL(APMS API) 등
     * - 미입력 시 MANUAL로 처리
     */
    var apiSource: ApiSource? = null

    // 선택 필드 (9개)
    /**
     * 품종명
     * - 예: "믹스견", "라브라도 리트리버"
     */
    @field:Size(max = 100, message = "품종명은 100자 이하여야 합니다")
    var breed: String? = null

    /**
     * 출생 연도
     * - 예: 2023
     */
    @field:Min(value = 1900, message = "출생연도는 1900년 이후여야 합니다")
    @field:Max(value = 2100, message = "출생연도는 2100년 이하여야 합니다")
    var birthYear: Int? = null

    /**
     * 체중
     * - 예: "12(Kg)"
     */
    @field:Size(max = 50, message = "체중은 50자 이하여야 합니다")
    var weight: String? = null

    /**
     * 색상
     * - 예: "옅은 황색", "검정&흰색"
     */
    @field:Size(max = 100, message = "색상은 100자 이하여야 합니다")
    var color: String? = null

    /**
     * 특징
     * - 예: "엄청 까불까불하고 순해요~잘생김~"
     */
    @field:Size(max = 1000, message = "특징은 1000자 이하여야 합니다")
    var specialMark: String? = null

    /**
     * 발견 장소
     * - 예: "진해구 남문동 남문시티 1차"
     */
    @field:Size(max = 200, message = "발견장소는 200자 이하여야 합니다")
    var happenPlace: String? = null

    /**
     * 대표 이미지 URL
     */
    @field:Size(max = 500, message = "이미지 URL은 500자 이하여야 합니다")
    var imageUrl: String? = null

    /**
     * 추가 이미지 URL
     */
    @field:Size(max = 500, message = "이미지 URL은 500자 이하여야 합니다")
    var imageUrl2: String? = null

    /**
     * 보호소가 추가 작성한 설명
     * - APMS 데이터 외 보호소가 직접 입력하는 상세 설명
     */
    @field:Size(max = 2000, message = "설명은 2000자 이하여야 합니다")
    var description: String? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is CreateAnimalRequest) {
            return false
        }
        if (careRegNo != other.careRegNo) {
            return false
        }
        if (apmsNoticeNo != other.apmsNoticeNo) {
            return false
        }
        if (noticeStartDate != other.noticeStartDate) {
            return false
        }
        if (noticeEndDate != other.noticeEndDate) {
            return false
        }
        if (species != other.species) {
            return false
        }
        if (gender != other.gender) {
            return false
        }
        if (neuterStatus != other.neuterStatus) {
            return false
        }
        if (status != other.status) {
            return false
        }
        if (apiSource != other.apiSource) {
            return false
        }
        if (breed != other.breed) {
            return false
        }
        if (birthYear != other.birthYear) {
            return false
        }
        if (weight != other.weight) {
            return false
        }
        if (color != other.color) {
            return false
        }
        if (specialMark != other.specialMark) {
            return false
        }
        if (happenPlace != other.happenPlace) {
            return false
        }
        if (imageUrl != other.imageUrl) {
            return false
        }
        if (imageUrl2 != other.imageUrl2) {
            return false
        }
        if (description != other.description) {
            return false
        }
        return true
    }

    override fun hashCode(): Int {
        return Objects.hash(
            careRegNo,
            apmsNoticeNo,
            noticeStartDate,
            noticeEndDate,
            species,
            gender,
            neuterStatus,
            status,
            apiSource,
            breed,
            birthYear,
            weight,
            color,
            specialMark,
            happenPlace,
            imageUrl,
            imageUrl2,
            description,
        )
    }

    override fun toString(): String {
        return "CreateAnimalRequest(" +
            "careRegNo=$careRegNo, " +
            "apmsNoticeNo=$apmsNoticeNo, " +
            "noticeStartDate=$noticeStartDate, " +
            "noticeEndDate=$noticeEndDate, " +
            "species=$species, " +
            "gender=$gender, " +
            "neuterStatus=$neuterStatus, " +
            "status=$status, " +
            "apiSource=$apiSource, " +
            "breed=$breed, " +
            "birthYear=$birthYear, " +
            "weight=$weight, " +
            "color=$color, " +
            "specialMark=$specialMark, " +
            "happenPlace=$happenPlace, " +
            "imageUrl=$imageUrl, " +
            "imageUrl2=$imageUrl2, " +
            "description=$description" +
            ")"
    }

    class CreateAnimalRequestBuilder internal constructor() {
        private var careRegNo: String? = null
        private var apmsNoticeNo: String? = null
        private var noticeStartDate: LocalDate? = null
        private var noticeEndDate: LocalDate? = null
        private var species: Species? = null
        private var gender: Gender? = null
        private var neuterStatus: NeuterStatus? = null
        private var status: AnimalStatus? = null
        private var apiSource: ApiSource? = null
        private var breed: String? = null
        private var birthYear: Int? = null
        private var weight: String? = null
        private var color: String? = null
        private var specialMark: String? = null
        private var happenPlace: String? = null
        private var imageUrl: String? = null
        private var imageUrl2: String? = null
        private var description: String? = null

        fun careRegNo(careRegNo: String?): CreateAnimalRequestBuilder = apply {
            this.careRegNo = careRegNo
        }

        fun apmsNoticeNo(apmsNoticeNo: String?): CreateAnimalRequestBuilder = apply {
            this.apmsNoticeNo = apmsNoticeNo
        }

        fun noticeStartDate(noticeStartDate: LocalDate?): CreateAnimalRequestBuilder = apply {
            this.noticeStartDate = noticeStartDate
        }

        fun noticeEndDate(noticeEndDate: LocalDate?): CreateAnimalRequestBuilder = apply {
            this.noticeEndDate = noticeEndDate
        }

        fun species(species: Species?): CreateAnimalRequestBuilder = apply {
            this.species = species
        }

        fun gender(gender: Gender?): CreateAnimalRequestBuilder = apply {
            this.gender = gender
        }

        fun neuterStatus(neuterStatus: NeuterStatus?): CreateAnimalRequestBuilder = apply {
            this.neuterStatus = neuterStatus
        }

        fun status(status: AnimalStatus?): CreateAnimalRequestBuilder = apply {
            this.status = status
        }

        fun apiSource(apiSource: ApiSource?): CreateAnimalRequestBuilder = apply {
            this.apiSource = apiSource
        }

        fun breed(breed: String?): CreateAnimalRequestBuilder = apply {
            this.breed = breed
        }

        fun birthYear(birthYear: Int?): CreateAnimalRequestBuilder = apply {
            this.birthYear = birthYear
        }

        fun weight(weight: String?): CreateAnimalRequestBuilder = apply {
            this.weight = weight
        }

        fun color(color: String?): CreateAnimalRequestBuilder = apply {
            this.color = color
        }

        fun specialMark(specialMark: String?): CreateAnimalRequestBuilder = apply {
            this.specialMark = specialMark
        }

        fun happenPlace(happenPlace: String?): CreateAnimalRequestBuilder = apply {
            this.happenPlace = happenPlace
        }

        fun imageUrl(imageUrl: String?): CreateAnimalRequestBuilder = apply {
            this.imageUrl = imageUrl
        }

        fun imageUrl2(imageUrl2: String?): CreateAnimalRequestBuilder = apply {
            this.imageUrl2 = imageUrl2
        }

        fun description(description: String?): CreateAnimalRequestBuilder = apply {
            this.description = description
        }

        fun build(): CreateAnimalRequest {
            val request = CreateAnimalRequest()
            request.careRegNo = careRegNo
            request.apmsNoticeNo = apmsNoticeNo
            request.noticeStartDate = noticeStartDate
            request.noticeEndDate = noticeEndDate
            request.species = species
            request.gender = gender
            request.neuterStatus = neuterStatus
            request.status = status
            request.apiSource = apiSource
            request.breed = breed
            request.birthYear = birthYear
            request.weight = weight
            request.color = color
            request.specialMark = specialMark
            request.happenPlace = happenPlace
            request.imageUrl = imageUrl
            request.imageUrl2 = imageUrl2
            request.description = description
            return request
        }
    }

    companion object {
        @JvmStatic
        fun builder(): CreateAnimalRequestBuilder = CreateAnimalRequestBuilder()
    }
}
