package com.pawbridge.backend.animal.internal.discovery.web

import com.fasterxml.jackson.annotation.JsonInclude
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.AnimalStatus
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.ApiSource
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Details
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Gender
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.NeuterStatus
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Species
import java.time.LocalDate
import java.time.LocalDateTime

@JsonInclude(JsonInclude.Include.NON_NULL)
@JvmRecord
data class AnimalDetails(
    val id: Long?,
    val apmsNoticeNo: String?,
    val species: Species?,
    val breed: String?,
    val gender: Gender?,
    val neuterStatus: NeuterStatus?,
    val birthYear: Int?,
    val age: Int?,
    val weight: String?,
    val color: String?,
    val specialMark: String?,
    val status: AnimalStatus?,
    val noticeStartDate: LocalDate?,
    val noticeEndDate: LocalDate?,
    val happenPlace: String?,
    val happenDate: LocalDate?,
    val imageUrl: String?,
    val imageUrl2: String?,
    val description: String?,
    val favoriteCount: Int?,
    val shelterId: Long?,
    val shelterName: String?,
    val apmsDesertionNo: String?,
    val apmsProcessState: String?,
    val apmsUpdatedAt: LocalDateTime?,
    val apiSource: ApiSource?,
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?,
) {
    companion object {
        @JvmStatic
        internal fun from(animal: Details): AnimalDetails {
            return AnimalDetails(
                animal.id,
                animal.apmsNoticeNo,
                animal.species,
                animal.breed,
                animal.gender,
                animal.neuterStatus,
                animal.birthYear,
                animal.age,
                animal.weight,
                animal.color,
                animal.specialMark,
                animal.status,
                animal.noticeStartDate,
                animal.noticeEndDate,
                animal.happenPlace,
                animal.happenDate,
                animal.imageUrl,
                animal.imageUrl2,
                animal.description,
                animal.favoriteCount,
                animal.shelterId,
                animal.shelterName,
                animal.apmsDesertionNo,
                animal.apmsProcessState,
                animal.apmsUpdatedAt,
                animal.apiSource,
                animal.createdAt,
                animal.updatedAt,
            )
        }
    }
}
