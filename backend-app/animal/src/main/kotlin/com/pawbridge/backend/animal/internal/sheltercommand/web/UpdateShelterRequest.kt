package com.pawbridge.backend.animal.internal.sheltercommand.web

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Size
import java.util.Objects

/**
 * 보호소 정보 수정 요청 DTO
 * - PUT /api/shelters/{id} 또는 PATCH /api/shelters/{id}
 * - 보호소 회원이 자신의 보호소 정보를 수정할 때 사용
 * - APMS 기본 정보(careRegNo, name, address, ownerName, organizationName)는 수정 불가
 * - 보호소가 직접 관리하는 정보만 수정 가능
 */
class UpdateShelterRequest {
    /**
     * 보호소 전화번호
     * - 예: "055-225-5701"
     */
    @field:Size(max = 50, message = "전화번호는 50자 이하여야 합니다")
    var phone: String? = null

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
        if (other !is UpdateShelterRequest) {
            return false
        }
        if (phone != other.phone) {
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
            phone,
            email,
            introduction,
            adoptionProcedure,
            operatingHours,
        )
    }

    override fun toString(): String {
        return "UpdateShelterRequest(" +
            "phone=$phone, " +
            "email=$email, " +
            "introduction=$introduction, " +
            "adoptionProcedure=$adoptionProcedure, " +
            "operatingHours=$operatingHours" +
            ")"
    }

    class UpdateShelterRequestBuilder internal constructor() {
        private var phone: String? = null
        private var email: String? = null
        private var introduction: String? = null
        private var adoptionProcedure: String? = null
        private var operatingHours: String? = null

        fun phone(phone: String?): UpdateShelterRequestBuilder = apply {
            this.phone = phone
        }

        fun email(email: String?): UpdateShelterRequestBuilder = apply {
            this.email = email
        }

        fun introduction(introduction: String?): UpdateShelterRequestBuilder = apply {
            this.introduction = introduction
        }

        fun adoptionProcedure(adoptionProcedure: String?): UpdateShelterRequestBuilder = apply {
            this.adoptionProcedure = adoptionProcedure
        }

        fun operatingHours(operatingHours: String?): UpdateShelterRequestBuilder = apply {
            this.operatingHours = operatingHours
        }

        fun build(): UpdateShelterRequest {
            val request = UpdateShelterRequest()
            request.phone = phone
            request.email = email
            request.introduction = introduction
            request.adoptionProcedure = adoptionProcedure
            request.operatingHours = operatingHours
            return request
        }
    }

    companion object {
        @JvmStatic
        fun builder(): UpdateShelterRequestBuilder = UpdateShelterRequestBuilder()
    }
}
