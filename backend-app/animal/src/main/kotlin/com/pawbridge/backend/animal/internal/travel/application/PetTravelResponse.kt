package com.pawbridge.backend.animal.internal.travel.application

import java.time.Instant

class PetTravelResponse private constructor() {

    @JvmRecord
    data class Region(val code: String?, val name: String?)

    @JvmRecord
    data class Regions(val items: List<Region>, val fetchedAt: Instant?, val availability: String?) {
        constructor(items: List<Region>, fetchedAt: Instant?) : this(items, fetchedAt, "READY")
    }

    // imageUrl is a provider-hosted Type1/Type3 photo or null; consumers must show
    // attribution and preserve the complete image without cropping or modification.
    @JvmRecord
    data class Place(val contentId: String?, val title: String?, val address: String?, val imageUrl: String?)

    // previewOnly is retained for older clients; paged clients use the page metadata.
    @JvmRecord
    data class Places(
        val areaCode: String?,
        val items: List<Place>,
        val previewOnly: Boolean,
        val fetchedAt: Instant?,
        val availability: String?,
        val page: Int,
        val size: Int,
        val totalElements: Long,
        val totalPages: Long,
    ) {
        constructor(areaCode: String?, items: List<Place>, previewOnly: Boolean, fetchedAt: Instant?) : this(
            areaCode,
            items,
            previewOnly,
            fetchedAt,
            "READY",
            0,
            10,
            items.size.toLong(),
            if (items.isEmpty()) 0 else 1,
        )
    }

    @JvmRecord
    data class Conditions(
        val areas: String?,
        val allowedAnimals: String?,
        val requirements: String?,
        val otherInformation: String?,
        val risks: String?,
        val facilities: String?,
        val providedItems: String?,
    )

    @JvmRecord
    data class InformationItem(val name: String?, val text: String?)

    @JvmRecord
    data class VisitInformation(
        val contentTypeId: String?,
        val informationCenter: String?,
        val openPeriod: String?,
        val usageHours: String?,
        val restDate: String?,
        val parking: String?,
        val parkingFee: String?,
        val usageFee: String?,
        val reservation: String?,
        val ageRange: String?,
        val experienceGuide: String?,
        val usageSeason: String?,
        val scale: String?,
        val estimatedDuration: String?,
        val discountInformation: String?,
        val additionalItems: List<InformationItem>,
        val status: String?,
        val fetchedAt: Instant?,
    )

    @JvmRecord
    data class Image(
        val originalUrl: String?,
        val thumbnailUrl: String?,
        val name: String?,
        val copyrightType: String?,
    )

    @JvmRecord
    data class Detail(
        val place: Place?,
        val overview: String?,
        val conditions: Conditions?,
        val petInformationAvailable: Boolean,
        val source: String?,
        val fetchedAt: Instant?,
        val petInformationFetchedAt: Instant?,
        val petInformationStatus: String?,
        val visitInformation: VisitInformation?,
        val images: List<Image>,
        val imagesStatus: String?,
        val imagesFetchedAt: Instant?,
    ) {
        constructor(
            place: Place?,
            overview: String?,
            conditions: Conditions?,
            petInformationAvailable: Boolean,
            source: String?,
            fetchedAt: Instant?,
            petInformationFetchedAt: Instant?,
        ) : this(
            place,
            overview,
            conditions,
            petInformationAvailable,
            source,
            fetchedAt,
            petInformationFetchedAt,
            if (petInformationFetchedAt == null) "PREPARING" else "READY",
            null,
            emptyList(),
            "PREPARING",
            null,
        )

        constructor(
            place: Place?,
            overview: String?,
            conditions: Conditions?,
            petInformationAvailable: Boolean,
            source: String?,
            fetchedAt: Instant?,
            petInformationFetchedAt: Instant?,
            petInformationStatus: String?,
        ) : this(
            place,
            overview,
            conditions,
            petInformationAvailable,
            source,
            fetchedAt,
            petInformationFetchedAt,
            petInformationStatus,
            null,
            emptyList(),
            "PREPARING",
            null,
        )
    }
}
