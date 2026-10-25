package com.pawbridge.backend.animal.internal.recommendation.application

import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.AnimalStatus
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Gender
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Species
import java.time.LocalDate
import java.time.LocalDateTime

/** Similarity order is supplied by Python; current relational rows determine visibility. */
interface AnimalRecommendations {
    fun recommend(animalId: Long): List<Card>

    @JvmRecord
    data class Card(
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
    )
}
