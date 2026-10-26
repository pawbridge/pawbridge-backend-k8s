package com.pawbridge.backend.community.internal.report.application

import com.pawbridge.backend.community.internal.post.application.PostUpload
import java.time.LocalDate
import java.time.LocalDateTime

/** The existing report workflow; storage and framework transactions stay in its adapter. */
interface AnimalReports {

    fun list(filter: Filter): Slice

    fun get(reportId: Long): Details

    fun create(request: Draft?, photos: List<PostUpload?>?, authorId: Long?): Details

    fun update(reportId: Long, request: Draft?, photos: List<PostUpload?>?, authorId: Long?): Details

    fun delete(reportId: Long, authorId: Long?)

    enum class Kind { MISSING, SIGHTING }

    enum class AnimalType { DOG, CAT, OTHER }

    @JvmRecord
    data class Draft(
        val kind: Kind?,
        val occurredOn: LocalDate?,
        val approximateTime: String?,
        val region: String?,
        val landmark: String?,
        val species: String?,
        val animalName: String?,
        val coatColor: String?,
        val animalSize: String?,
        val distinguishingFeatures: String?,
        val direction: String?,
        val description: String?,
        val province: String?,
        val district: String?,
        val animalType: AnimalType?,
    )

    @JvmRecord
    data class Filter(
        val page: Int,
        val size: Int,
        val kind: Kind?,
        val keyword: String?,
        val province: String?,
        val district: String?,
        val animalType: AnimalType?,
        val from: LocalDate?,
        val to: LocalDate?,
    )

    @JvmRecord
    data class Slice(val content: List<Details>, val total: Long)

    @JvmRecord
    data class Details(
        val reportId: Long?,
        val authorId: Long?,
        val authorNickname: String?,
        val title: String?,
        val description: String?,
        val imageUrls: List<String>?,
        val kind: Kind?,
        val occurredOn: LocalDate?,
        val approximateTime: String?,
        val region: String?,
        val landmark: String?,
        val species: String?,
        val animalName: String?,
        val coatColor: String?,
        val animalSize: String?,
        val distinguishingFeatures: String?,
        val direction: String?,
        val createdAt: LocalDateTime?,
        val updatedAt: LocalDateTime?,
        val province: String?,
        val district: String?,
        val animalType: AnimalType?,
    )
}
