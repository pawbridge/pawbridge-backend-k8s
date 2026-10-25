package com.pawbridge.backend.animal.internal.travel.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.travel.application.PetTravel
import com.pawbridge.backend.animal.internal.travel.application.PetTravelException
import com.pawbridge.backend.animal.internal.travel.application.PetTravelResponse
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.transaction.support.TransactionOperations
import java.net.URI
import java.time.Instant

class PostgresqlPetTravel internal constructor(
    private val repository: PostgresqlTravelCatalog,
    private val reads: TransactionOperations,
    private val repeatableReads: TransactionOperations,
) : PetTravel {

    constructor(
        jdbc: JdbcOperations,
        mapper: ObjectMapper,
        reads: TransactionOperations,
        repeatableReads: TransactionOperations,
    ) : this(PostgresqlTravelCatalog(jdbc, mapper), reads, repeatableReads)

    override fun regions(): PetTravelResponse.Regions {
        return reads.execute { readRegions() }!!
    }

    override fun places(areaCode: String?, page: Int): PetTravelResponse.Places {
        return repeatableReads.execute { readPlaces(areaCode, page) }!!
    }

    override fun detail(contentId: String?): PetTravelResponse.Detail {
        // The original detail method validates before any database access (unlike the transactional list).
        if (contentId == null || !CONTENT_ID.matches(contentId)) {
            invalid()
        }
        return reads.execute { readDetail(contentId) }!!
    }

    private fun readRegions(): PetTravelResponse.Regions {
        val data = repository.regions()
        val items = data.map { row -> PetTravelResponse.Region(row.code, row.name) }
        // Stream.min keeps the Java behavior: a missing fetch time fails instead of being skipped.
        val fetchedAt = data.stream()
            .map { row -> row.fetchedAt }
            .min { first, second -> first!!.compareTo(second!!) }
            .orElse(null)
        return PetTravelResponse.Regions(
            items,
            fetchedAt,
            if (data.isEmpty()) {
                if (repository.collectionState().errorCode == null) "PREPARING" else "FAILED"
            } else {
                "READY"
            },
        )
    }

    fun places(areaCode: String?): PetTravelResponse.Places {
        return places(areaCode, 0)
    }

    private fun readPlaces(areaCode: String?, page: Int): PetTravelResponse.Places {
        if (page < 0) {
            invalid()
        }
        if (areaCode == null || !AREA_CODE.matches(areaCode)) {
            invalid()
        }
        val region = repository.regions().firstOrNull { row -> row.code!! == areaCode }
            ?: throw PetTravelException(PetTravelException.Code.INVALID_REQUEST)
        val total = repository.countPlaces(areaCode)
        val data = if (page.toLong() * 10 < total) repository.places(areaCode, page) else emptyList()
        val state = repository.collectionState()
        val failed = "COLLECTION_FAILED" == state.errorCode
        val availability = if (region.completedAt == null) {
            if (total == 0L) {
                if (failed) "FAILED" else "PREPARING"
            } else {
                "PARTIAL"
            }
        } else {
            if (failed) "STALE" else "READY"
        }
        return PetTravelResponse.Places(
            areaCode,
            data.map { row -> place(row.common) },
            true,
            region.completedAt,
            availability,
            page,
            10,
            total,
            (total + 9) / 10,
        )
    }

    private fun readDetail(contentId: String): PetTravelResponse.Detail {
        val snapshot = repository.detail(contentId)
            .orElseThrow { PetTravelException(PetTravelException.Code.NOT_FOUND) }
        val row = snapshot.common
        val conditions = snapshot.pet
        val intro = snapshot.intro
        val additional = snapshot.information
            .map { item -> PetTravelResponse.InformationItem(value(item, "infoname"), value(item, "infotext")) }
            .filter { item -> item.name != null && item.text != null }
        val visitInformation = PetTravelResponse.VisitInformation(
            value(row, "contenttypeid"),
            first(intro, "infocenter", "infocenterculture", "infocenterleports"),
            first(intro, "opendate", "openperiod"),
            first(intro, "usetime", "usetimeculture", "usetimeleports"),
            first(intro, "restdate", "restdateculture", "restdateleports"),
            first(intro, "parking", "parkingculture", "parkingleports"),
            first(intro, "parkingfee", "parkingfeeleports"),
            first(intro, "usefee", "usefeeleports"),
            value(intro, "reservation"),
            first(intro, "expagerange", "expagerangeleports"),
            value(intro, "expguide"),
            value(intro, "useseason"),
            first(intro, "scale", "scaleleports"),
            value(intro, "spendtime"),
            value(intro, "discountinfo"),
            additional,
            snapshot.visitInformationStatus,
            latest(snapshot.introFetchedAt, snapshot.informationFetchedAt),
        )
        val images = snapshot.images
            .map { image ->
                PetTravelResponse.Image(
                    providerImageUrl(image.originalUrl, image.copyrightType),
                    providerImageUrl(image.thumbnailUrl, image.copyrightType),
                    image.name,
                    image.copyrightType,
                )
            }
            .filter { image -> image.originalUrl != null }
        return PetTravelResponse.Detail(
            place(row),
            value(row, "overview"),
            PetTravelResponse.Conditions(
                value(conditions, "acmpyTypeCd"),
                value(conditions, "acmpyPsblCpam"),
                value(conditions, "acmpyNeedMtr"),
                value(conditions, "etcAcmpyInfo"),
                value(conditions, "relaAcdntRiskMtr"),
                value(conditions, "relaPosesFclty"),
                value(conditions, "relaFrnshPrdlst"),
            ),
            CONDITION_FIELDS.any { field -> value(conditions, field) != null },
            "KOREA_TOURISM_ORGANIZATION",
            if (snapshot.basicFetchedAt == null) snapshot.publishedAt else snapshot.basicFetchedAt,
            snapshot.publishedAt,
            snapshot.detailStatus,
            visitInformation,
            images,
            snapshot.imagesStatus,
            snapshot.imagesFetchedAt,
        )
    }

    private fun place(row: Map<String, String>): PetTravelResponse.Place {
        return PetTravelResponse.Place(required(row, "contentid"), required(row, "title"), value(row, "addr1"), imageUrl(row))
    }

    // Java String.isBlank(): empty or only Character.isWhitespace characters (Kotlin isBlank also accepts NBSP).
    private fun isBlank(value: String): Boolean = value.all { Character.isWhitespace(it) }

    // This API publishes Type1/Type3 representative photos. Do not infer a missing license
    // from another record or fetch an image per card. Frontend supplies attribution.
    private fun imageUrl(row: Map<String, String>): String? {
        val copyright = value(row, "cpyrhtDivCd")
        return providerImageUrl(value(row, "firstimage"), copyright)
    }

    private fun providerImageUrl(image: String?, copyright: String?): String? {
        if ("Type1" != copyright && "Type3" != copyright) {
            return null
        }
        if (image == null) {
            return null
        }
        try {
            val uri = URI.create(image)
            if (!("http" == uri.scheme || "https" == uri.scheme) ||
                "tong.visitkorea.or.kr" != uri.host ||
                uri.userInfo != null ||
                uri.port != -1 ||
                uri.rawQuery != null ||
                uri.rawFragment != null ||
                uri.rawPath == null ||
                !PROVIDER_IMAGE_PATH.matches(uri.rawPath)
            ) {
                return null
            }
            // Upgrade only the verified provider image host, never arbitrary HTTP URLs.
            return "https://tong.visitkorea.or.kr" + uri.rawPath
        } catch (exception: IllegalArgumentException) {
            return null
        }
    }

    private fun first(row: Map<String, String>, vararg keys: String): String? {
        for (key in keys) {
            val found = value(row, key)
            if (found != null) {
                return found
            }
        }
        return null
    }

    private fun latest(first: Instant?, second: Instant?): Instant? {
        if (first == null) {
            return second
        }
        if (second == null) {
            return first
        }
        return if (first.isAfter(second)) first else second
    }

    private fun value(row: Map<String, String>, key: String): String? {
        val text = row[key]
        return if (text == null || isBlank(text)) null else text
    }

    private fun required(row: Map<String, String>, key: String): String {
        val text = value(row, key)
        if (text == null) {
            throw PetTravelException.unavailable()
        }
        return text
    }

    private fun invalid(): Nothing {
        throw PetTravelException(PetTravelException.Code.INVALID_REQUEST)
    }

    companion object {
        private val CONTENT_ID = Regex("[0-9]{1,20}")
        private val AREA_CODE = Regex("[0-9]{2,5}")
        private val PROVIDER_IMAGE_PATH = Regex("/cms/resource/[0-9]+/[A-Za-z0-9_-]+\\.(jpg|jpeg|png|webp|gif)")
        private val CONDITION_FIELDS = listOf(
            "acmpyTypeCd",
            "acmpyPsblCpam",
            "acmpyNeedMtr",
            "etcAcmpyInfo",
            "relaAcdntRiskMtr",
            "relaPosesFclty",
            "relaFrnshPrdlst",
        )
    }
}
