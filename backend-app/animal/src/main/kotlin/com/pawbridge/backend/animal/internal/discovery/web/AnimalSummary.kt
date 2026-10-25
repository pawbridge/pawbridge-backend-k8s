package com.pawbridge.backend.animal.internal.discovery.web

import com.fasterxml.jackson.annotation.JsonInclude
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.AnimalStatus
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Gender
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Species
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Summary
import java.time.LocalDate
import java.time.LocalDateTime

@JsonInclude(JsonInclude.Include.NON_NULL)
@JvmRecord
data class AnimalSummary(
    val id: Long?,
    val apmsNoticeNo: String?,
    val species: Species?,
    val breed: String?,
    val gender: Gender?,
    val birthYear: Int?,
    val age: Int?,
    val specialMark: String?,
    val status: AnimalStatus?,
    val noticeEndDate: LocalDate?,
    val imageUrl: String?,
    val favoriteCount: Int?,
    val shelterId: Long?,
    val shelterName: String?,
    val createdAt: LocalDateTime?,
) {
    companion object {
        @JvmStatic
        internal fun from(animal: Summary): AnimalSummary {
            return AnimalSummary(
                animal.id,
                animal.apmsNoticeNo,
                animal.species,
                animal.breed,
                animal.gender,
                animal.birthYear,
                animal.age,
                animal.specialMark,
                animal.status,
                animal.noticeEndDate,
                animal.imageUrl,
                animal.favoriteCount,
                animal.shelterId,
                animal.shelterName,
                animal.createdAt,
            )
        }
    }
}
