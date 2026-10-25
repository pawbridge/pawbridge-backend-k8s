package com.pawbridge.backend.animal.internal.travel.infrastructure.collection

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Original PostgreSQL collection writes/checkpoints only; HTTP stays outside these transactions. */
class PostgresqlTravelCollectionCatalog(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
    manager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(manager)

    @JvmRecord
    data class Region(val code: String, val name: String, val fetchedAt: Instant?, val completedAt: Instant?)

    @JvmRecord
    data class Target(
        val contentId: String,
        val areaCode: String?,
        val modifiedTime: String,
        val shown: Boolean,
        val imageUrl: String?,
        val copyrightType: String?,
        val generation: Long,
        val pending: Boolean,
    )

    enum class VisitResource(
        // Package-private fields in Java.
        @JvmField internal val revisionColumn: String,
        @JvmField internal val attemptedColumn: String,
        @JvmField internal val errorColumn: String,
    ) {
        INTRO("intro_source_modified_time", "intro_attempted_at", "intro_error"),
        INFO("info_source_modified_time", "info_attempted_at", "info_error"),
        IMAGES("image_source_modified_time", "image_attempted_at", "image_error"),
    }

    @JvmRecord
    data class VisitTarget(val contentId: String, val contentTypeId: String, val modifiedTime: String, val generation: Long)

    @JvmRecord
    data class CollectionState(val phase: String, val nextPage: Int, val errorCode: String?, val completedAt: Instant?)

    @JvmRecord
    data class PetCollectionState(val nextPage: Int, val expectedTotal: Int?, val cycleId: String?, val completedAt: Instant?)

    fun petCollectionState(): PetCollectionState {
        return jdbc.queryForObject(
            "SELECT * FROM pawbridge_animal.pet_travel_pet_collection_state WHERE id=1",
            RowMapper { r, _ ->
                PetCollectionState(
                    r.getInt("next_page"),
                    r.getObject("expected_total") as Int?,
                    r.getString("cycle_id"),
                    instant(r, "completed_at"),
                )
            },
        )!!
    }

    /** Page data and its checkpoint commit together. No inferred deletion for absent IDs. */
    fun savePetPage(expected: PetCollectionState, page: TourApiClient.Page, fetchedAt: Instant, validUntil: Instant) {
        val remaining = page.totalCount.toLong() - (expected.nextPage - 1).toLong() * 100
        if (page.totalCount < 0 ||
            page.items.size.toLong() != minOf(100L, maxOf(0L, remaining)) ||
            (expected.nextPage > 1 && remaining <= 0) ||
            (expected.expectedTotal != null && expected.expectedTotal != page.totalCount) ||
            page.items.map { row -> row["contentid"] }.distinct().size != page.items.size
        ) {
            throw IllegalStateException("PET_INVENTORY_CHANGED")
        }
        transaction.executeWithoutResult {
            val current = jdbc.queryForObject(
                "SELECT cycle_id FROM pawbridge_animal.pet_travel_pet_collection_state WHERE id=1 FOR UPDATE",
                String::class.java,
            )
            val state = petCollectionState()
            // cycleId is dereferenced like the Java equals call, so a missing cycle id fails with a NullPointerException.
            if (expected.cycleId!! != current || expected.nextPage != state.nextPage) {
                throw IllegalStateException("PET_CHECKPOINT_CHANGED")
            }
            for (row in page.items) {
                val contentId = row["contentid"]
                if (contentId == null || !CONTENT_ID.matches(contentId)) {
                    throw IllegalArgumentException("Invalid pet identity")
                }
                val alreadyStored = jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM pawbridge_animal.pet_travel_pet_details " +
                        "WHERE provider=? AND content_id=? AND cycle_id=?)",
                    Boolean::class.javaObjectType,
                    PROVIDER,
                    contentId,
                    expected.cycleId,
                )
                if (alreadyStored == true) {
                    throw IllegalStateException("PET_INVENTORY_CHANGED")
                }
                jdbc.update(
                    "INSERT INTO pawbridge_animal.pet_travel_pet_details(provider,content_id,pet_data,source,fetched_at,valid_until,cycle_id) " +
                        "VALUES (?,?," + "CAST(? AS jsonb)" + ",'BULK',?,?,?) " +
                        "ON CONFLICT (provider,content_id) DO UPDATE SET pet_data=EXCLUDED.pet_data,source='BULK'," +
                        "fetched_at=EXCLUDED.fetched_at,valid_until=EXCLUDED.valid_until,cycle_id=EXCLUDED.cycle_id",
                    PROVIDER,
                    contentId,
                    json(row),
                    Timestamp.from(fetchedAt),
                    Timestamp.from(validUntil),
                    expected.cycleId,
                )
            }
            val complete = expected.nextPage.toLong() * 100 >= page.totalCount
            jdbc.update(
                "UPDATE pawbridge_animal.pet_travel_pet_collection_state SET next_page=?,expected_total=?,cycle_id=?," +
                    "completed_at=CASE WHEN ? THEN ? ELSE completed_at END,error_code=NULL WHERE id=1",
                if (complete) 1 else expected.nextPage + 1,
                if (complete) null else page.totalCount,
                if (complete) UUID.randomUUID().toString() else expected.cycleId,
                complete,
                Timestamp.from(fetchedAt),
            )
        }
    }

    fun petCollectionFailed(code: String, restart: Boolean) {
        if (restart) {
            jdbc.update(
                "UPDATE pawbridge_animal.pet_travel_pet_collection_state SET next_page=1,expected_total=NULL,cycle_id=?,error_code=? WHERE id=1",
                UUID.randomUUID().toString(),
                code,
            )
        } else {
            jdbc.update("UPDATE pawbridge_animal.pet_travel_pet_collection_state SET error_code=? WHERE id=1", code)
        }
    }

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

    fun checkpoint(phase: String, page: Int) {
        jdbc.update("UPDATE pawbridge_animal.pet_travel_collection_state SET phase=?,next_page=? WHERE id=1", phase, page)
    }

    fun reserveRequest(operation: String, day: LocalDate, limit: Int): Boolean {
        return transaction.execute {
            jdbc.update(
                "INSERT INTO pawbridge_animal.pet_travel_request_budgets(request_day,operation,used) VALUES (?,?,0) " +
                    "ON CONFLICT (request_day,operation) DO NOTHING",
                java.sql.Date.valueOf(day),
                operation,
            )
            jdbc.update(
                "UPDATE pawbridge_animal.pet_travel_request_budgets SET used=used+1 " +
                    "WHERE request_day=? AND operation=? AND used<?",
                java.sql.Date.valueOf(day),
                operation,
                limit,
            ) == 1
        } == true
    }

    fun startRun(id: String, now: Instant) {
        val running = jdbc.queryForObject(
            "SELECT COUNT(*) FROM pawbridge_animal.pet_travel_collection_runs WHERE status='RUNNING'",
            Int::class.javaObjectType,
        )
        if (running != null && running > 0) {
            throw IllegalStateException("TRAVEL_ORPHAN_RUN")
        }
        jdbc.update(
            "INSERT INTO pawbridge_animal.pet_travel_collection_runs(id,started_at,status) VALUES (?,?,'RUNNING')",
            id,
            Timestamp.from(now),
        )
    }

    fun finishRun(id: String, state: String, error: String?, requests: Int, discovered: Int, published: Int, now: Instant) {
        transaction.executeWithoutResult {
            jdbc.update(
                "UPDATE pawbridge_animal.pet_travel_collection_runs SET finished_at=?,status=?,error_code=?,requests=?,discovered=?,published=? WHERE id=?",
                Timestamp.from(now),
                state,
                error,
                requests,
                discovered,
                published,
                id,
            )
            jdbc.update("UPDATE pawbridge_animal.pet_travel_collection_state SET error_code=? WHERE id=1", error)
        }
    }

    fun completeCycle(now: Instant) {
        transaction.executeWithoutResult {
            for (region in regions()) {
                completeRegion(region.code, now)
            }
            jdbc.update(
                "UPDATE pawbridge_animal.pet_travel_collection_state SET phase='HIDDEN',next_page=1,completed_at=?,error_code=NULL WHERE id=1",
                Timestamp.from(now),
            )
        }
    }

    /** Start a refresh wave only after existing work drains; discovery never waits for it. */
    fun beginDetails(cutoff: Instant) {
        transaction.executeWithoutResult {
            // Failed rows stay retryable, but must not hold every other place's refresh indefinitely.
            val hasPendingRows = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM pawbridge_animal.pet_travel_targets " +
                    "WHERE provider=? AND shown=TRUE AND pending=TRUE AND area_code IS NOT NULL AND detail_error IS NULL " +
                    "AND (" + "(basic_data->'contenttypeid')" + " IS NULL OR " +
                    "(basic_data->>'contenttypeid')" + " IN " + DISCOVERY_CONTENT_TYPES + "))",
                Boolean::class.javaObjectType,
                PROVIDER,
            )
            if (hasPendingRows == true) {
                return@executeWithoutResult
            }
            jdbc.update(
                "UPDATE pawbridge_animal.pet_travel_targets t SET pending=TRUE FROM pawbridge_animal.pet_travel_places p " +
                    "WHERE p.provider=t.provider AND p.content_id=t.content_id AND t.shown=TRUE AND p.published_at<? " +
                    "AND (COALESCE(" + "(t.basic_data->>'contenttypeid')" + "," +
                    "(p.common_data->>'contenttypeid')" + ") IS NULL OR " +
                    "COALESCE(" + "(t.basic_data->>'contenttypeid')" + "," +
                    "(p.common_data->>'contenttypeid')" + ") IN " + DISCOVERY_CONTENT_TYPES + ")",
                Timestamp.from(cutoff),
            )
        }
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

    /** Store discovery and basic public data atomically, without calling detail endpoints. */
    fun observeBasic(row: Map<String, String>, region: String?, now: Instant) {
        val shown = "1" == row["showflag"]
        if (shown && isBlank(row.getOrDefault("title", ""))) {
            throw IllegalArgumentException("Missing place title")
        }
        transaction.executeWithoutResult {
            observe(
                row["contentid"],
                region,
                row["modifiedtime"],
                shown,
                row["firstimage"],
                row["cpyrhtDivCd"],
                now,
                false,
            )
            val current = lockedTarget(row["contentid"])
            if (current.modifiedTime != row["modifiedtime"]) {
                return@executeWithoutResult
            }
            jdbc.update(
                "UPDATE pawbridge_animal.pet_travel_targets SET basic_data=" + "CAST(? AS jsonb)" +
                    ",basic_fetched_at=? WHERE provider=? AND content_id=?",
                json(row),
                Timestamp.from(now),
                PROVIDER,
                current.contentId,
            )
        }
    }

    fun detailFailed(target: Target, now: Instant) {
        jdbc.update(
            "UPDATE pawbridge_animal.pet_travel_targets SET detail_error='DETAIL_FAILED',detail_attempted_at=? " +
                "WHERE provider=? AND content_id=? AND generation=? AND shown=TRUE",
            Timestamp.from(now),
            PROVIDER,
            target.contentId,
            target.generation,
        )
    }

    fun saveRegions(regions: Map<String, String>, fetchedAt: Instant) {
        for ((code, name) in regions) {
            if (!REGION_CODE.matches(code) || isBlank(name)) {
                throw IllegalArgumentException("Invalid region")
            }
        }
        transaction.executeWithoutResult {
            for ((code, name) in regions) {
                jdbc.update(
                    "INSERT INTO pawbridge_animal.pet_travel_regions(code,name,fetched_at) VALUES (?,?,?) " +
                        "ON CONFLICT (code) DO UPDATE SET name=EXCLUDED.name,fetched_at=EXCLUDED.fetched_at",
                    code,
                    name,
                    Timestamp.from(fetchedAt),
                )
            }
        }
    }

    /** Explicit discovery updates visibility/photo rights even when subsequent detail fetching fails. */
    fun observe(
        contentId: String?,
        areaCode: String?,
        modifiedTime: String?,
        shown: Boolean,
        imageUrl: String?,
        copyrightType: String?,
        observedAt: Instant,
        refreshDetail: Boolean,
    ) {
        if (contentId == null || !CONTENT_ID.matches(contentId) ||
            (areaCode != null && !REGION_CODE.matches(areaCode)) ||
            modifiedTime == null || !MODIFIED_TIME.matches(modifiedTime)
        ) {
            throw IllegalArgumentException("Invalid discovery metadata")
        }
        transaction.executeWithoutResult {
            // Insert (or wait for a concurrent insert), then lock the existing target before comparing revisions.
            jdbc.update(
                "INSERT INTO pawbridge_animal.pet_travel_targets(provider,content_id,area_code,modified_time,shown," +
                    "image_url,copyright_type,generation,pending,observed_at) VALUES (?,?,?,?,?,?,?,0,TRUE,?) " +
                    "ON CONFLICT (provider,content_id) DO NOTHING",
                PROVIDER,
                contentId,
                areaCode,
                modifiedTime,
                shown,
                imageUrl,
                copyrightType,
                Timestamp.from(observedAt),
            )
            val previous = lockedTarget(contentId)
            if (modifiedTime.compareTo(previous.modifiedTime) < 0) {
                return@executeWithoutResult
            }
            val pending = shown && (
                previous.pending || refreshDetail || !previous.shown ||
                    modifiedTime != previous.modifiedTime || areaCode != previous.areaCode
                )
            if (shown != previous.shown) {
                jdbc.update(
                    "UPDATE pawbridge_animal.pet_travel_targets SET pet_valid_after=? WHERE provider=? AND content_id=?",
                    Timestamp.from(observedAt),
                    PROVIDER,
                    contentId,
                )
            }
            if (modifiedTime != previous.modifiedTime) {
                jdbc.update(
                    "UPDATE pawbridge_animal.pet_travel_targets SET pet_changed_at=? WHERE provider=? AND content_id=?",
                    Timestamp.from(observedAt),
                    PROVIDER,
                    contentId,
                )
            }
            jdbc.update(
                "UPDATE pawbridge_animal.pet_travel_targets SET area_code=?,modified_time=?,shown=?,image_url=?," +
                    "copyright_type=?,generation=generation+1,pending=?,observed_at=?,detail_error=CASE WHEN ? THEN NULL ELSE detail_error END WHERE provider=? AND content_id=?",
                areaCode,
                modifiedTime,
                shown,
                imageUrl,
                copyrightType,
                pending,
                Timestamp.from(observedAt),
                shown && !previous.shown,
                PROVIDER,
                contentId,
            )
            // Reappearance cannot republish stale hidden details; publish() alone makes them visible.
            jdbc.update(
                "UPDATE pawbridge_animal.pet_travel_places SET visible=CASE WHEN ? THEN visible ELSE FALSE END, image_url=?,copyright_type=? " +
                    "WHERE provider=? AND content_id=?",
                shown,
                imageUrl,
                copyrightType,
                PROVIDER,
                contentId,
            )
        }
    }

    fun pending(limit: Int): List<Target> {
        if (limit < 1 || limit > 100) {
            throw IllegalArgumentException("Target limit must be 1..100")
        }
        return jdbc.query(
            "SELECT * FROM pawbridge_animal.pet_travel_targets WHERE provider=? AND pending=TRUE AND shown=TRUE AND area_code IS NOT NULL " +
                "AND (" + "(basic_data->'contenttypeid')" + " IS NULL OR " +
                "(basic_data->>'contenttypeid')" + " IN " + DISCOVERY_CONTENT_TYPES + ") " +
                "ORDER BY (detail_attempted_at IS NOT NULL),detail_attempted_at,observed_at,content_id LIMIT ?",
            RowMapper { row, _ -> target(row) },
            PROVIDER,
            limit,
        )
    }

    fun pendingVisitDetails(resource: VisitResource, limit: Int): List<VisitTarget> {
        if (limit < 1 || limit > 100) {
            throw IllegalArgumentException("Visit detail limit must be 1..100")
        }
        val sql = "SELECT t.content_id," + "(t.basic_data->>'contenttypeid')" + " content_type_id," +
            "t.modified_time,t.generation FROM pawbridge_animal.pet_travel_targets t " +
            "LEFT JOIN pawbridge_animal.pet_travel_visit_details v ON v.provider=t.provider AND v.content_id=t.content_id " +
            "WHERE t.provider=? AND t.shown=TRUE AND t.area_code IS NOT NULL " +
            "AND " + "(t.basic_data->>'contenttypeid')" + " IN " + DISCOVERY_CONTENT_TYPES + " " +
            "AND (v." + resource.revisionColumn + " IS NULL OR v." + resource.revisionColumn + "<>t.modified_time) " +
            "ORDER BY (v." + resource.attemptedColumn + " IS NOT NULL),v." + resource.attemptedColumn +
            ",t.observed_at,t.content_id LIMIT ?"
        return jdbc.query(
            sql,
            RowMapper { row, _ ->
                VisitTarget(
                    row.getString("content_id"),
                    row.getString("content_type_id"),
                    row.getString("modified_time"),
                    row.getLong("generation"),
                )
            },
            PROVIDER,
            limit,
        )
    }

    fun hasPendingVisitDetails(): Boolean {
        for (resource in VisitResource.values()) {
            if (!pendingVisitDetails(resource, 1).isEmpty()) {
                return true
            }
        }
        return false
    }

    fun saveVisitIntro(expected: VisitTarget, rows: List<Map<String, String>>, fetchedAt: Instant): Boolean {
        if (rows.size > 1) {
            throw IllegalArgumentException("Invalid intro response")
        }
        validateVisitRows(expected, rows, true)
        return saveVisitResource(expected, VisitResource.INTRO, json(if (rows.isEmpty()) emptyMap() else rows[0]), fetchedAt)
    }

    fun saveVisitInformation(expected: VisitTarget, rows: List<Map<String, String>>, fetchedAt: Instant): Boolean {
        validateVisitRows(expected, rows, true)
        return saveVisitResource(expected, VisitResource.INFO, jsonList(rows), fetchedAt)
    }

    fun saveVisitImages(expected: VisitTarget, rows: List<Map<String, String>>, fetchedAt: Instant): Boolean {
        validateVisitRows(expected, rows, false)
        if (rows.map { row -> row["serialnum"] }.distinct().size.toLong() != rows.size.toLong()) {
            throw IllegalArgumentException("Duplicate image identity")
        }
        return transaction.execute {
            if (!matchesLockedTarget(expected)) {
                return@execute false
            }
            ensureVisitDetails(expected)
            jdbc.update(
                "DELETE FROM pawbridge_animal.pet_travel_images WHERE provider=? AND content_id=?",
                PROVIDER,
                expected.contentId,
            )
            var order = 0
            for (row in rows) {
                val copyright = row["cpyrhtDivCd"]
                val original = row["originimgurl"]
                if (!("Type1" == copyright || "Type3" == copyright) || original == null || isBlank(original)) {
                    continue
                }
                jdbc.update(
                    "INSERT INTO pawbridge_animal.pet_travel_images(provider,content_id,serial_number,image_name,original_url," +
                        "thumbnail_url,copyright_type,display_order,fetched_at) VALUES (?,?,?,?,?,?,?,?,?)",
                    PROVIDER,
                    expected.contentId,
                    row["serialnum"],
                    row["imgname"],
                    original,
                    row["smallimageurl"],
                    copyright,
                    order++,
                    Timestamp.from(fetchedAt),
                )
            }
            jdbc.update(
                "UPDATE pawbridge_animal.pet_travel_visit_details SET image_source_modified_time=?,image_fetched_at=?," +
                    "image_attempted_at=?,image_error=NULL WHERE provider=? AND content_id=?",
                expected.modifiedTime,
                Timestamp.from(fetchedAt),
                Timestamp.from(fetchedAt),
                PROVIDER,
                expected.contentId,
            )
            true
        } == true
    }

    fun visitDetailFailed(resource: VisitResource, target: VisitTarget, attemptedAt: Instant) {
        transaction.executeWithoutResult {
            if (!matchesLockedTarget(target)) {
                return@executeWithoutResult
            }
            ensureVisitDetails(target)
            jdbc.update(
                "UPDATE pawbridge_animal.pet_travel_visit_details SET " + resource.attemptedColumn + "=?," +
                    resource.errorColumn + "='DETAIL_FAILED' WHERE provider=? AND content_id=?",
                Timestamp.from(attemptedAt),
                PROVIDER,
                target.contentId,
            )
        }
    }

    private fun saveVisitResource(expected: VisitTarget, resource: VisitResource, json: String, fetchedAt: Instant): Boolean {
        return transaction.execute {
            if (!matchesLockedTarget(expected)) {
                return@execute false
            }
            ensureVisitDetails(expected)
            val dataColumn = if (resource == VisitResource.INTRO) "intro_data" else "info_data"
            val fetchedColumn = if (resource == VisitResource.INTRO) "intro_fetched_at" else "info_fetched_at"
            jdbc.update(
                "UPDATE pawbridge_animal.pet_travel_visit_details SET " + dataColumn + "=" + "CAST(? AS jsonb)" + "," +
                    resource.revisionColumn + "=?," + fetchedColumn + "=?," + resource.attemptedColumn + "=?," +
                    resource.errorColumn + "=NULL WHERE provider=? AND content_id=?",
                json,
                expected.modifiedTime,
                Timestamp.from(fetchedAt),
                Timestamp.from(fetchedAt),
                PROVIDER,
                expected.contentId,
            )
            true
        } == true
    }

    private fun validateVisitRows(expected: VisitTarget, rows: List<Map<String, String>>, requireContentType: Boolean) {
        for (row in rows) {
            if (expected.contentId != row["contentid"] ||
                (requireContentType && expected.contentTypeId != row["contenttypeid"])
            ) {
                throw IllegalArgumentException("Mismatched visit detail")
            }
        }
    }

    private fun matchesLockedTarget(expected: VisitTarget): Boolean {
        val current = lockedTarget(expected.contentId)
        return current.shown && current.generation == expected.generation && current.modifiedTime == expected.modifiedTime
    }

    private fun ensureVisitDetails(target: VisitTarget) {
        jdbc.update(
            "INSERT INTO pawbridge_animal.pet_travel_visit_details(provider,content_id,content_type_id) VALUES (?,?,?) " +
                "ON CONFLICT (provider,content_id) DO UPDATE SET content_type_id=EXCLUDED.content_type_id",
            PROVIDER,
            target.contentId,
            target.contentTypeId,
        )
    }

    /** Returns false if discovery changed while HTTP was in flight. An empty pet map is valid. */
    fun publish(expected: Target, common: Map<String, String>, pet: Map<String, String>, fetchedAt: Instant): Boolean {
        return publish(expected, common, pet, fetchedAt, true)
    }

    fun publishCommon(expected: Target, common: Map<String, String>, fetchedAt: Instant): Boolean {
        return publish(expected, common, emptyMap(), fetchedAt, false)
    }

    private fun publish(
        expected: Target,
        common: Map<String, String>,
        pet: Map<String, String>,
        fetchedAt: Instant,
        includesPet: Boolean,
    ): Boolean {
        if (expected.contentId != common["contentid"] ||
            isBlank(common.getOrDefault("title", "")) ||
            (pet.isNotEmpty() && expected.contentId != pet["contentid"])
        ) {
            throw IllegalArgumentException("Mismatched or incomplete detail")
        }
        val commonJson = json(common)
        val petJson = json(pet)
        return transaction.execute {
            val current = lockedTarget(expected.contentId)
            if (!current.shown || !current.pending || current.areaCode == null || current.generation != expected.generation) {
                return@execute false
            }
            jdbc.update(
                "INSERT INTO pawbridge_animal.pet_travel_places(provider,content_id,area_code,title,common_data,pet_data," +
                    "image_url,copyright_type,visible,published_at) VALUES (?,?,?,?," + "CAST(? AS jsonb)" + "," +
                    "CAST(? AS jsonb)" + ",?,?,TRUE,?) " +
                    "ON CONFLICT (provider,content_id) DO UPDATE SET area_code=EXCLUDED.area_code,title=EXCLUDED.title," +
                    "common_data=EXCLUDED.common_data,pet_data=CASE WHEN ? THEN EXCLUDED.pet_data ELSE pet_travel_places.pet_data END," +
                    "image_url=EXCLUDED.image_url,copyright_type=EXCLUDED.copyright_type,visible=TRUE,published_at=EXCLUDED.published_at",
                PROVIDER,
                current.contentId,
                current.areaCode,
                common["title"],
                commonJson,
                petJson,
                current.imageUrl,
                current.copyrightType,
                Timestamp.from(fetchedAt),
                includesPet,
            )
            if (includesPet) {
                jdbc.update(
                    "INSERT INTO pawbridge_animal.pet_travel_pet_details(provider,content_id,pet_data,source,fetched_at) VALUES (?,?," +
                        "CAST(? AS jsonb)" + ",'LEGACY',?) " +
                        "ON CONFLICT (provider,content_id) DO UPDATE SET pet_data=EXCLUDED.pet_data,source='LEGACY'," +
                        "fetched_at=EXCLUDED.fetched_at,valid_until=NULL,cycle_id=NULL",
                    PROVIDER,
                    current.contentId,
                    petJson,
                    Timestamp.from(fetchedAt),
                )
            }
            jdbc.update(
                "UPDATE pawbridge_animal.pet_travel_targets SET pending=FALSE,detail_error=NULL,detail_attempted_at=? WHERE provider=? AND content_id=?",
                Timestamp.from(fetchedAt),
                PROVIDER,
                current.contentId,
            )
            true
        } == true
    }

    fun hasUnresolvedRegions(): Boolean {
        return jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM pawbridge_animal.pet_travel_targets WHERE provider=? AND shown=TRUE AND area_code IS NULL)",
            Boolean::class.javaObjectType,
            PROVIDER,
        ) == true
    }

    /** Called only after a complete region inventory pass, never after a partial/failed scan. */
    fun completeRegion(areaCode: String?, completedAt: Instant): Boolean {
        return jdbc.update(
            "UPDATE pawbridge_animal.pet_travel_regions SET completed_at=? WHERE code=?",
            Timestamp.from(completedAt),
            areaCode,
        ) == 1
    }

    private fun lockedTarget(contentId: String?): Target {
        return jdbc.queryForObject(
            "SELECT * FROM pawbridge_animal.pet_travel_targets WHERE provider=? AND content_id=? FOR UPDATE",
            RowMapper { row, _ -> target(row) },
            PROVIDER,
            contentId,
        )!!
    }

    @Throws(SQLException::class)
    private fun target(row: ResultSet): Target {
        return Target(
            row.getString("content_id"),
            row.getString("area_code"),
            row.getString("modified_time"),
            row.getBoolean("shown"),
            row.getString("image_url"),
            row.getString("copyright_type"),
            row.getLong("generation"),
            row.getBoolean("pending"),
        )
    }

    private fun json(fields: Map<String, String>): String {
        try {
            return mapper.writeValueAsString(fields)
        } catch (exception: Exception) {
            throw IllegalArgumentException("Invalid travel fields")
        }
    }

    private fun jsonList(fields: List<Map<String, String>>): String {
        try {
            return mapper.writeValueAsString(fields)
        } catch (exception: Exception) {
            throw IllegalArgumentException("Invalid travel fields")
        }
    }

    // Java String.isBlank(): empty or only Character.isWhitespace characters (Kotlin isBlank also accepts NBSP).
    private fun isBlank(value: String): Boolean = value.all { Character.isWhitespace(it) }

    @Throws(SQLException::class)
    private fun instant(row: ResultSet, column: String): Instant? {
        val timestamp = row.getTimestamp(column)
        return timestamp?.toInstant()
    }

    companion object {
        private const val PROVIDER = "KOREA_TOURISM_ORGANIZATION"
        private const val DISCOVERY_CONTENT_TYPES = "('12','14','28')"
        private val CONTENT_ID = Regex("[0-9]{1,20}")
        private val REGION_CODE = Regex("[0-9]{2,5}")
        private val MODIFIED_TIME = Regex("[0-9]{14}")
    }
}
