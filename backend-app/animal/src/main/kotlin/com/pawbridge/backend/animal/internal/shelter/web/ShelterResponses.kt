package com.pawbridge.backend.animal.internal.shelter.web

import com.fasterxml.jackson.annotation.JsonInclude
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads
import java.time.LocalDateTime

// Package-private final class with a private constructor in Java. The nested records are exposed through the public
// controller signatures, which Kotlin does not allow for `internal` types, so the holder and its nested classes are public.
class ShelterResponses private constructor() {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JvmRecord
    data class Summary(
        val id: Long?,
        val careRegNo: String?,
        val name: String?,
        val phone: String?,
        val address: String?,
        val organizationName: String?,
        val createdAt: LocalDateTime?,
    ) {
        companion object {
            @JvmStatic
            internal fun from(value: ShelterReads.Summary): Summary {
                return Summary(
                    value.id,
                    value.careRegNo,
                    value.name,
                    value.phone,
                    value.address,
                    value.organizationName,
                    value.createdAt,
                )
            }
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JvmRecord
    data class Details(
        val id: Long?,
        val careRegNo: String?,
        val name: String?,
        val phone: String?,
        val address: String?,
        val ownerName: String?,
        val organizationName: String?,
        val email: String?,
        val introduction: String?,
        val adoptionProcedure: String?,
        val operatingHours: String?,
        val createdAt: LocalDateTime?,
        val updatedAt: LocalDateTime?,
        val publicInformation: PublicInformation?,
    ) {
        companion object {
            @JvmStatic
            internal fun from(value: ShelterReads.Details): Details {
                return Details(
                    value.id,
                    value.careRegNo,
                    value.name,
                    value.phone,
                    value.address,
                    value.ownerName,
                    value.organizationName,
                    value.email,
                    value.introduction,
                    value.adoptionProcedure,
                    value.operatingHours,
                    value.createdAt,
                    value.updatedAt,
                    if (value.publicInformation == null) null else PublicInformation.from(value.publicInformation),
                )
            }
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JvmRecord
    data class PublicInformation(
        val source: String?,
        val phone: String?,
        val latitude: Double?,
        val longitude: Double?,
        val weekdayOpen: String?,
        val weekdayClose: String?,
        val weekendOpen: String?,
        val weekendClose: String?,
        val closedDays: String?,
        val sourceUpdatedDate: String?,
        val collectedAt: LocalDateTime?,
    ) {
        companion object {
            @JvmStatic
            internal fun from(value: ShelterReads.PublicInformation): PublicInformation {
                return PublicInformation(
                    value.source,
                    value.phone,
                    value.latitude,
                    value.longitude,
                    value.weekdayOpen,
                    value.weekdayClose,
                    value.weekendOpen,
                    value.weekendClose,
                    value.closedDays,
                    value.sourceUpdatedDate,
                    value.collectedAt,
                )
            }
        }
    }
}
