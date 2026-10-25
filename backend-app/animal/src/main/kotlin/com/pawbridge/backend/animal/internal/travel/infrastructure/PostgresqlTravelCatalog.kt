package com.pawbridge.backend.animal.internal.travel.infrastructure

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.util.Optional

/** Selected original PostgreSQL SELECTs only. No metadata probing, collector, write or scheduler. */
internal class PostgresqlTravelCatalog(
    private val jdbc: JdbcOperations,
    private val mapper: ObjectMapper,
) {

    @JvmRecord
    data class Region(val code: String?, val name: String?, val fetchedAt: Instant?, val completedAt: Instant?)

    @JvmRecord
    data class Image(
        val serialNumber: String?,
        val name: String?,
        val originalUrl: String?,
        val thumbnailUrl: String?,
        val copyrightType: String?,
        val displayOrder: Int,
    )

    @JvmRecord
    @JvmSuppressWildcards
    data class Place(
        val common: Map<String, String>,
        val pet: Map<String, String>,
        val publishedAt: Instant?,
        val basicFetchedAt: Instant?,
        val detailStatus: String?,
        val intro: Map<String, String>,
        val information: List<Map<String, String>>,
        val images: List<Image>,
        val introFetchedAt: Instant?,
        val informationFetchedAt: Instant?,
        val imagesFetchedAt: Instant?,
        val visitInformationStatus: String?,
        val imagesStatus: String?,
    ) {
        constructor(common: Map<String, String>, pet: Map<String, String>, publishedAt: Instant?) : this(
            common,
            pet,
            publishedAt,
            publishedAt,
            if (publishedAt == null) "PREPARING" else "READY",
            emptyMap(),
            emptyList(),
            emptyList(),
            null,
            null,
            null,
            "PREPARING",
            "PREPARING",
        )

        constructor(
            common: Map<String, String>,
            pet: Map<String, String>,
            publishedAt: Instant?,
            basicFetchedAt: Instant?,
            detailStatus: String?,
        ) : this(
            common,
            pet,
            publishedAt,
            basicFetchedAt,
            detailStatus,
            emptyMap(),
            emptyList(),
            emptyList(),
            null,
            null,
            null,
            "PREPARING",
            "PREPARING",
        )
    }

    @JvmRecord
    data class CollectionState(val phase: String?, val nextPage: Int, val errorCode: String?, val completedAt: Instant?)

    fun collectionState(): CollectionState {
        return jdbc.queryForObject(
            "SELECT * FROM pawbridge_animal.pet_travel_collection_state WHERE id=1",
            RowMapper { r, _ ->
                CollectionState(
                    r.getString("phase"),
                    r.getInt("next_page"),
                    r.getString("error_code"),
                    instant(r, "completed_at"),
                )
            },
        )!!
    }

    fun regions(): List<Region> {
        return jdbc.query(
            "SELECT * FROM pawbridge_animal.pet_travel_regions ORDER BY " + "CAST(code AS INTEGER)",
            RowMapper { row, _ ->
                Region(
                    row.getString("code"),
                    row.getString("name"),
                    instant(row, "fetched_at"),
                    instant(row, "completed_at"),
                )
            },
        )
    }

    fun places(areaCode: String, page: Int): List<Place> {
        return jdbc.query(
            publicQuery() + discoveryTypes() + " AND t.area_code=? ORDER BY " +
                "COALESCE(" + "(t.basic_data->>'title')" + ",p.title),t.content_id LIMIT 10 OFFSET ?",
            RowMapper { row, _ -> place(row) },
            PROVIDER,
            areaCode,
            page.toLong() * 10,
        )
    }

    fun countPlaces(areaCode: String): Long {
        return jdbc.queryForObject(
            "SELECT COUNT(*) " + publicFrom() + discoveryTypes() + " AND t.area_code=?",
            Long::class.javaObjectType,
            PROVIDER,
            areaCode,
        )!!
    }

    private fun discoveryTypes(): String {
        return " AND (CASE WHEN t.basic_data IS NOT NULL " +
            "THEN " + "(t.basic_data->>'contenttypeid')" + " " +
            "ELSE " + "(p.common_data->>'contenttypeid')" + " END) IN " + DISCOVERY_CONTENT_TYPES
    }

    fun detail(contentId: String): Optional<Place> {
        val rows = jdbc.query(
            publicQuery() + " AND t.content_id=?",
            RowMapper { row, _ -> place(row) },
            PROVIDER,
            contentId,
        )
        val first = rows.firstOrNull() ?: return Optional.empty()
        return Optional.of(
            Place(
                first.common,
                first.pet,
                first.publishedAt,
                first.basicFetchedAt,
                first.detailStatus,
                first.intro,
                first.information,
                images(contentId),
                first.introFetchedAt,
                first.informationFetchedAt,
                first.imagesFetchedAt,
                first.visitInformationStatus,
                first.imagesStatus,
            ),
        )
    }

    private fun publicQuery(): String {
        return "SELECT t.basic_data,t.basic_fetched_at,t.image_url,t.copyright_type,t.pending,t.detail_error,t.modified_time," +
            "p.common_data,q.pet_data,q.fetched_at AS pet_fetched_at,q.source AS pet_source," +
            "q.valid_until AS pet_valid_until,t.pet_changed_at,v.intro_data,v.info_data," +
            "v.intro_source_modified_time,v.info_source_modified_time,v.image_source_modified_time," +
            "v.intro_fetched_at,v.info_fetched_at,v.image_fetched_at,v.intro_error,v.info_error,v.image_error " +
            publicFrom()
    }

    private fun publicFrom(): String {
        return "FROM pawbridge_animal.pet_travel_targets t " +
            "LEFT JOIN pawbridge_animal.pet_travel_places p ON p.provider=t.provider AND p.content_id=t.content_id AND p.visible=TRUE " +
            "LEFT JOIN pawbridge_animal.pet_travel_pet_details q ON q.provider=t.provider AND q.content_id=t.content_id " +
            "AND (t.pet_valid_after IS NULL OR q.fetched_at>=t.pet_valid_after) " +
            "LEFT JOIN pawbridge_animal.pet_travel_visit_details v ON v.provider=t.provider AND v.content_id=t.content_id " +
            "WHERE t.provider=? AND t.shown=TRUE AND t.area_code IS NOT NULL " +
            "AND (t.basic_data IS NOT NULL OR p.content_id IS NOT NULL)"
    }

    @Throws(SQLException::class)
    private fun place(row: ResultSet): Place {
        val common = LinkedHashMap<String, String>()
        if (row.getString("common_data") != null) {
            common.putAll(fields(row.getString("common_data")))
        }
        if (row.getString("basic_data") != null) {
            common.putAll(fields(row.getString("basic_data")))
        }
        // The most recent discovery owns photo rights, not the last successful detail snapshot.
        common.remove("firstimage")
        common.remove("cpyrhtDivCd")
        if (row.getString("image_url") != null) {
            common["firstimage"] = row.getString("image_url")
        }
        if (row.getString("copyright_type") != null) {
            common["cpyrhtDivCd"] = row.getString("copyright_type")
        }
        val published = instant(row, "pet_fetched_at")
        val bulk = "BULK" == row.getString("pet_source")
        val validUntil = instant(row, "pet_valid_until")
        val changedAt = instant(row, "pet_changed_at")
        // Compare Instants rather than the database session's wall clock/time zone.
        // published.isBefore(...) keeps the Java NullPointerException when a changed bulk row has no fetch time.
        val stale = bulk && (
            (validUntil != null && !validUntil.isAfter(Instant.now())) ||
                (changedAt != null && published!!.isBefore(changedAt))
            )
        val detailStatus = if (bulk) {
            if (stale) "STALE" else "READY"
        } else if (row.getString("detail_error") != null) {
            if (published == null) "FAILED" else "STALE"
        } else if (published == null) {
            "PREPARING"
        } else if (row.getBoolean("pending")) {
            "STALE"
        } else {
            "READY"
        }
        val intro = if (row.getString("intro_data") == null) emptyMap() else fields(row.getString("intro_data"))
        val information = if (row.getString("info_data") == null) emptyList() else fieldList(row.getString("info_data"))
        val introFetched = instant(row, "intro_fetched_at")
        val infoFetched = instant(row, "info_fetched_at")
        val imageFetched = instant(row, "image_fetched_at")
        val modified = row.getString("modified_time")
        val introStatus = resourceStatus(introFetched, row.getString("intro_source_modified_time"), modified, row.getString("intro_error"))
        val infoStatus = resourceStatus(infoFetched, row.getString("info_source_modified_time"), modified, row.getString("info_error"))
        val visitStatus = combineStatuses(introStatus, infoStatus)
        val imageStatus = resourceStatus(imageFetched, row.getString("image_source_modified_time"), modified, row.getString("image_error"))
        return Place(
            java.util.Map.copyOf(common),
            if (row.getString("pet_data") == null) emptyMap() else fields(row.getString("pet_data")),
            published,
            instant(row, "basic_fetched_at"),
            detailStatus,
            intro,
            information,
            emptyList(),
            introFetched,
            infoFetched,
            imageFetched,
            visitStatus,
            imageStatus,
        )
    }

    private fun images(contentId: String): List<Image> {
        return jdbc.query(
            "SELECT serial_number,image_name,original_url,thumbnail_url,copyright_type,display_order " +
                "FROM pawbridge_animal.pet_travel_images WHERE provider=? AND content_id=? ORDER BY display_order,serial_number",
            RowMapper { row, _ ->
                Image(
                    row.getString("serial_number"),
                    row.getString("image_name"),
                    row.getString("original_url"),
                    row.getString("thumbnail_url"),
                    row.getString("copyright_type"),
                    row.getInt("display_order"),
                )
            },
            PROVIDER,
            contentId,
        )
    }

    private fun fields(json: String): Map<String, String> {
        try {
            return java.util.Map.copyOf(mapper.readValue(json, FIELDS))
        } catch (exception: Exception) {
            throw IllegalStateException("Invalid stored travel fields")
        }
    }

    private fun fieldList(json: String): List<Map<String, String>> {
        try {
            return mapper.readValue(json, FIELD_LIST).map { fields -> java.util.Map.copyOf(fields) }
        } catch (exception: Exception) {
            throw IllegalStateException("Invalid stored travel fields")
        }
    }

    @Throws(SQLException::class)
    private fun instant(row: ResultSet, column: String): Instant? {
        val timestamp = row.getTimestamp(column)
        return timestamp?.toInstant()
    }

    companion object {
        private const val PROVIDER = "KOREA_TOURISM_ORGANIZATION"
        private const val DISCOVERY_CONTENT_TYPES = "('12','14','28')"
        private val FIELDS = object : TypeReference<Map<String, String>>() {}
        private val FIELD_LIST = object : TypeReference<List<Map<String, String>>>() {}

        private fun resourceStatus(
            fetchedAt: Instant?,
            sourceModifiedTime: String?,
            currentModifiedTime: String?,
            error: String?,
        ): String {
            if (fetchedAt == null) {
                return if (error == null) "PREPARING" else "FAILED"
            }
            if (sourceModifiedTime != currentModifiedTime || error != null) {
                return "STALE"
            }
            return "READY"
        }

        private fun combineStatuses(first: String, second: String): String {
            if (first == "READY" && second == "READY") {
                return "READY"
            }
            if (first == "STALE" || second == "STALE") {
                return "STALE"
            }
            if ((first == "READY" && second != "READY") || (second == "READY" && first != "READY")) {
                return "PARTIAL"
            }
            if (first == "FAILED" || second == "FAILED") {
                return "FAILED"
            }
            return "PREPARING"
        }
    }
}
