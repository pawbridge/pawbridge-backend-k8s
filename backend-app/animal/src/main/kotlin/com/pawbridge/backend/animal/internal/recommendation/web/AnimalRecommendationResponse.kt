package com.pawbridge.backend.animal.internal.recommendation.web

import com.fasterxml.jackson.annotation.JsonInclude
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.AnimalStatus
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Gender
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Species
import com.pawbridge.backend.animal.internal.recommendation.application.AnimalRecommendations.Card
import java.time.LocalDate
import java.time.LocalDateTime

@JsonInclude(JsonInclude.Include.NON_NULL)
@JvmRecord
data class AnimalRecommendationResponse(
    val id: Long?,
    val apmsNoticeNo: String?,
    val species: Species?,
    val breed: String?,
    val gender: Gender?,
    val birthYear: Int?,
    val age: Int?,
    val weight: String?,
    val color: String?,
    val happenDate: LocalDate?,
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
        internal fun from(card: Card): AnimalRecommendationResponse {
            return AnimalRecommendationResponse(
                card.id,
                card.apmsNoticeNo,
                card.species,
                card.breed,
                card.gender,
                card.birthYear,
                card.age,
                card.weight,
                card.color,
                card.happenDate,
                card.specialMark,
                card.status,
                card.noticeEndDate,
                card.imageUrl,
                card.favoriteCount,
                card.shelterId,
                card.shelterName,
                card.createdAt,
            )
        }
    }
}
