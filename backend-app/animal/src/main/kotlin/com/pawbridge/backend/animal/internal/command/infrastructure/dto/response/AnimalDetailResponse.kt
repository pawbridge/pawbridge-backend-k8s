package com.pawbridge.backend.animal.internal.command.infrastructure.dto.response

import com.fasterxml.jackson.annotation.JsonInclude
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.AnimalStatus
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.ApiSource
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.Gender
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.NeuterStatus
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.Species
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 동물 상세 응답 DTO (상세 조회용)
 * - GET /api/animals/{id} (상세 조회)
 * - 모든 필드를 포함하여 완전한 정보 제공
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AnimalDetailResponse(
    // 기본 정보
    /**
     * 동물 ID
     */
    var id: Long? = null,

    /**
     * 공고번호
     */
    var apmsNoticeNo: String? = null,

    /**
     * 축종
     */
    var species: Species? = null,

    /**
     * 품종명
     */
    var breed: String? = null,

    /**
     * 성별
     */
    var gender: Gender? = null,

    /**
     * 중성화 여부
     */
    var neuterStatus: NeuterStatus? = null,

    /**
     * 출생 연도
     */
    var birthYear: Int? = null,

    /**
     * 나이 (계산된 값)
     */
    var age: Int? = null,

    // 신체 특징
    /**
     * 체중
     */
    var weight: String? = null,

    /**
     * 색상
     */
    var color: String? = null,

    /**
     * 특징
     */
    var specialMark: String? = null,

    // 상태 및 공고 정보
    /**
     * 동물 상태
     */
    var status: AnimalStatus? = null,

    /**
     * 공고 시작일
     */
    var noticeStartDate: LocalDate? = null,

    /**
     * 공고 종료일
     */
    var noticeEndDate: LocalDate? = null,

    // 발견/접수 정보
    /**
     * 발견 장소
     */
    var happenPlace: String? = null,

    /**
     * 접수일 (APMS 데이터)
     */
    var happenDate: LocalDate? = null,

    // 이미지
    /**
     * 대표 이미지 URL
     */
    var imageUrl: String? = null,

    /**
     * 추가 이미지 URL
     */
    var imageUrl2: String? = null,

    // 설명
    /**
     * 보호소가 추가 작성한 설명
     */
    var description: String? = null,

    // 찜 정보
    /**
     * 찜 횟수
     */
    var favoriteCount: Int? = null,

    // 보호소 정보
    /**
     * 보호소 ID
     */
    var shelterId: Long? = null,

    /**
     * 보호소 이름
     */
    var shelterName: String? = null,

    // APMS 연동 정보
    /**
     * APMS 유기번호 (APMS 배치 전용)
     */
    var apmsDesertionNo: String? = null,

    /**
     * APMS 처리상태 (APMS 배치 전용)
     */
    var apmsProcessState: String? = null,

    /**
     * APMS 마지막 동기화 시간
     */
    var apmsUpdatedAt: LocalDateTime? = null,

    /**
     * 데이터 출처
     */
    var apiSource: ApiSource? = null,

    // 메타 정보
    /**
     * 등록일
     */
    var createdAt: LocalDateTime? = null,

    /**
     * 수정일
     */
    var updatedAt: LocalDateTime? = null,
) {

    class AnimalDetailResponseBuilder internal constructor() {
        private var id: Long? = null
        private var apmsNoticeNo: String? = null
        private var species: Species? = null
        private var breed: String? = null
        private var gender: Gender? = null
        private var neuterStatus: NeuterStatus? = null
        private var birthYear: Int? = null
        private var age: Int? = null
        private var weight: String? = null
        private var color: String? = null
        private var specialMark: String? = null
        private var status: AnimalStatus? = null
        private var noticeStartDate: LocalDate? = null
        private var noticeEndDate: LocalDate? = null
        private var happenPlace: String? = null
        private var happenDate: LocalDate? = null
        private var imageUrl: String? = null
        private var imageUrl2: String? = null
        private var description: String? = null
        private var favoriteCount: Int? = null
        private var shelterId: Long? = null
        private var shelterName: String? = null
        private var apmsDesertionNo: String? = null
        private var apmsProcessState: String? = null
        private var apmsUpdatedAt: LocalDateTime? = null
        private var apiSource: ApiSource? = null
        private var createdAt: LocalDateTime? = null
        private var updatedAt: LocalDateTime? = null

        fun id(id: Long?): AnimalDetailResponseBuilder = apply {
            this.id = id
        }

        fun apmsNoticeNo(apmsNoticeNo: String?): AnimalDetailResponseBuilder = apply {
            this.apmsNoticeNo = apmsNoticeNo
        }

        fun species(species: Species?): AnimalDetailResponseBuilder = apply {
            this.species = species
        }

        fun breed(breed: String?): AnimalDetailResponseBuilder = apply {
            this.breed = breed
        }

        fun gender(gender: Gender?): AnimalDetailResponseBuilder = apply {
            this.gender = gender
        }

        fun neuterStatus(neuterStatus: NeuterStatus?): AnimalDetailResponseBuilder = apply {
            this.neuterStatus = neuterStatus
        }

        fun birthYear(birthYear: Int?): AnimalDetailResponseBuilder = apply {
            this.birthYear = birthYear
        }

        fun age(age: Int?): AnimalDetailResponseBuilder = apply {
            this.age = age
        }

        fun weight(weight: String?): AnimalDetailResponseBuilder = apply {
            this.weight = weight
        }

        fun color(color: String?): AnimalDetailResponseBuilder = apply {
            this.color = color
        }

        fun specialMark(specialMark: String?): AnimalDetailResponseBuilder = apply {
            this.specialMark = specialMark
        }

        fun status(status: AnimalStatus?): AnimalDetailResponseBuilder = apply {
            this.status = status
        }

        fun noticeStartDate(noticeStartDate: LocalDate?): AnimalDetailResponseBuilder = apply {
            this.noticeStartDate = noticeStartDate
        }

        fun noticeEndDate(noticeEndDate: LocalDate?): AnimalDetailResponseBuilder = apply {
            this.noticeEndDate = noticeEndDate
        }

        fun happenPlace(happenPlace: String?): AnimalDetailResponseBuilder = apply {
            this.happenPlace = happenPlace
        }

        fun happenDate(happenDate: LocalDate?): AnimalDetailResponseBuilder = apply {
            this.happenDate = happenDate
        }

        fun imageUrl(imageUrl: String?): AnimalDetailResponseBuilder = apply {
            this.imageUrl = imageUrl
        }

        fun imageUrl2(imageUrl2: String?): AnimalDetailResponseBuilder = apply {
            this.imageUrl2 = imageUrl2
        }

        fun description(description: String?): AnimalDetailResponseBuilder = apply {
            this.description = description
        }

        fun favoriteCount(favoriteCount: Int?): AnimalDetailResponseBuilder = apply {
            this.favoriteCount = favoriteCount
        }

        fun shelterId(shelterId: Long?): AnimalDetailResponseBuilder = apply {
            this.shelterId = shelterId
        }

        fun shelterName(shelterName: String?): AnimalDetailResponseBuilder = apply {
            this.shelterName = shelterName
        }

        fun apmsDesertionNo(apmsDesertionNo: String?): AnimalDetailResponseBuilder = apply {
            this.apmsDesertionNo = apmsDesertionNo
        }

        fun apmsProcessState(apmsProcessState: String?): AnimalDetailResponseBuilder = apply {
            this.apmsProcessState = apmsProcessState
        }

        fun apmsUpdatedAt(apmsUpdatedAt: LocalDateTime?): AnimalDetailResponseBuilder = apply {
            this.apmsUpdatedAt = apmsUpdatedAt
        }

        fun apiSource(apiSource: ApiSource?): AnimalDetailResponseBuilder = apply {
            this.apiSource = apiSource
        }

        fun createdAt(createdAt: LocalDateTime?): AnimalDetailResponseBuilder = apply {
            this.createdAt = createdAt
        }

        fun updatedAt(updatedAt: LocalDateTime?): AnimalDetailResponseBuilder = apply {
            this.updatedAt = updatedAt
        }

        fun build(): AnimalDetailResponse = AnimalDetailResponse(
            id,
            apmsNoticeNo,
            species,
            breed,
            gender,
            neuterStatus,
            birthYear,
            age,
            weight,
            color,
            specialMark,
            status,
            noticeStartDate,
            noticeEndDate,
            happenPlace,
            happenDate,
            imageUrl,
            imageUrl2,
            description,
            favoriteCount,
            shelterId,
            shelterName,
            apmsDesertionNo,
            apmsProcessState,
            apmsUpdatedAt,
            apiSource,
            createdAt,
            updatedAt,
        )
    }

    companion object {
        @JvmStatic
        fun builder(): AnimalDetailResponseBuilder = AnimalDetailResponseBuilder()
    }
}
