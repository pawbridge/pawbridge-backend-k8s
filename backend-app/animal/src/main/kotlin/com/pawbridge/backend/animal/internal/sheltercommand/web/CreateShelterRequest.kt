package com.pawbridge.backend.animal.internal.sheltercommand.web

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.Objects

/**
 * 보호소 등록 요청 DTO
 * - POST /api/shelters
 * - 관리자가 보호소를 수동 등록할 때 사용
 * - APMS 배치는 createFromApms() 정적 팩토리 메서드 사용
 */
class CreateShelterRequest {
    // 필수 필드 (2개)
    /**
     * 보호소 등록번호
     * - 예: "348527200900001"
     * - UNIQUE 제약
     */
    @field:NotBlank(message = "보호소 등록번호는 필수입니다")
    @field:Size(max = 50, message = "보호소 등록번호는 50자 이하여야 합니다")
    var careRegNo: String? = null

    /**
     * 보호소 이름
     * - 예: "창원동물보호센터"
     */
    @field:NotBlank(message = "보호소 이름은 필수입니다")
    @field:Size(max = 200, message = "보호소 이름은 200자 이하여야 합니다")
    var name: String? = null

    // 선택 필드 (APMS 정보)
    /**
     * 보호소 전화번호
     * - 예: "055-225-5701"
     */
    @field:Size(max = 50, message = "전화번호는 50자 이하여야 합니다")
    var phone: String? = null

    /**
     * 보호소 주소
     * - 예: "경상남도 창원시 성산구 공단로474번길 117 (상복동)"
     */
    @field:Size(max = 500, message = "주소는 500자 이하여야 합니다")
    var address: String? = null

    /**
     * 보호소 대표자
     * - 예: "창원시장"
     */
    @field:Size(max = 100, message = "대표자명은 100자 이하여야 합니다")
    var ownerName: String? = null

    /**
     * 관할 기관
     * - 예: "경상남도 창원시 의창성산구"
     */
    @field:Size(max = 200, message = "관할 기관명은 200자 이하여야 합니다")
    var organizationName: String? = null

    // 선택 필드 (자체 관리)
    /**
     * 보호소 이메일
     */
    @field:Email(message = "올바른 이메일 형식이 아닙니다")
    @field:Size(max = 100, message = "이메일은 100자 이하여야 합니다")
    var email: String? = null

    /**
     * 보호소 소개
     */
    @field:Size(max = 2000, message = "소개는 2000자 이하여야 합니다")
    var introduction: String? = null

    /**
     * 입양 절차 안내
     */
    @field:Size(max = 2000, message = "입양 절차 안내는 2000자 이하여야 합니다")
    var adoptionProcedure: String? = null

    /**
     * 운영 시간
     * - 예: "평일 09:00-18:00, 주말 10:00-17:00"
     */
    @field:Size(max = 200, message = "운영 시간은 200자 이하여야 합니다")
    var operatingHours: String? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is CreateShelterRequest) {
            return false
        }
        if (careRegNo != other.careRegNo) {
            return false
        }
        if (name != other.name) {
            return false
        }
        if (phone != other.phone) {
            return false
        }
        if (address != other.address) {
            return false
        }
        if (ownerName != other.ownerName) {
            return false
        }
        if (organizationName != other.organizationName) {
            return false
        }
        if (email != other.email) {
            return false
        }
        if (introduction != other.introduction) {
            return false
        }
        if (adoptionProcedure != other.adoptionProcedure) {
            return false
        }
        if (operatingHours != other.operatingHours) {
            return false
        }
        return true
    }

    override fun hashCode(): Int {
        return Objects.hash(
            careRegNo,
            name,
            phone,
            address,
            ownerName,
            organizationName,
            email,
            introduction,
            adoptionProcedure,
            operatingHours,
        )
    }

    override fun toString(): String {
        return "CreateShelterRequest(" +
            "careRegNo=$careRegNo, " +
            "name=$name, " +
            "phone=$phone, " +
            "address=$address, " +
            "ownerName=$ownerName, " +
            "organizationName=$organizationName, " +
            "email=$email, " +
            "introduction=$introduction, " +
            "adoptionProcedure=$adoptionProcedure, " +
            "operatingHours=$operatingHours" +
            ")"
    }

    class CreateShelterRequestBuilder internal constructor() {
        private var careRegNo: String? = null
        private var name: String? = null
        private var phone: String? = null
        private var address: String? = null
        private var ownerName: String? = null
        private var organizationName: String? = null
        private var email: String? = null
        private var introduction: String? = null
        private var adoptionProcedure: String? = null
        private var operatingHours: String? = null

        fun careRegNo(careRegNo: String?): CreateShelterRequestBuilder = apply {
            this.careRegNo = careRegNo
        }

        fun name(name: String?): CreateShelterRequestBuilder = apply {
            this.name = name
        }

        fun phone(phone: String?): CreateShelterRequestBuilder = apply {
            this.phone = phone
        }

        fun address(address: String?): CreateShelterRequestBuilder = apply {
            this.address = address
        }

        fun ownerName(ownerName: String?): CreateShelterRequestBuilder = apply {
            this.ownerName = ownerName
        }

        fun organizationName(organizationName: String?): CreateShelterRequestBuilder = apply {
            this.organizationName = organizationName
        }

        fun email(email: String?): CreateShelterRequestBuilder = apply {
            this.email = email
        }

        fun introduction(introduction: String?): CreateShelterRequestBuilder = apply {
            this.introduction = introduction
        }

        fun adoptionProcedure(adoptionProcedure: String?): CreateShelterRequestBuilder = apply {
            this.adoptionProcedure = adoptionProcedure
        }

        fun operatingHours(operatingHours: String?): CreateShelterRequestBuilder = apply {
            this.operatingHours = operatingHours
        }

        fun build(): CreateShelterRequest {
            val request = CreateShelterRequest()
            request.careRegNo = careRegNo
            request.name = name
            request.phone = phone
            request.address = address
            request.ownerName = ownerName
            request.organizationName = organizationName
            request.email = email
            request.introduction = introduction
            request.adoptionProcedure = adoptionProcedure
            request.operatingHours = operatingHours
            return request
        }
    }

    companion object {
        @JvmStatic
        fun builder(): CreateShelterRequestBuilder = CreateShelterRequestBuilder()
    }
}
