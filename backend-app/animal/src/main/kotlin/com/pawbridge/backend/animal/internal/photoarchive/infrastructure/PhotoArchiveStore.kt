package com.pawbridge.backend.animal.internal.photoarchive.infrastructure

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.Optional
import java.util.UUID

/** Only short database transactions; network I/O belongs to PhotoArchiveWorker. */
class PhotoArchiveStore(
    private val jdbc: JdbcTemplate,
    manager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(manager)

    init {
        transaction.timeout = 15
    }

    @JvmRecord
    data class Claim(
        val animalId: Long,
        val slot: Int,
        val generation: Long,
        val token: String,
        val sourceUrl: String,
        val attempts: Int,
    )

    private data class AnimalSource(
        val id: Long,
        val desertionNo: String?,
        val first: String?,
        val second: String?,
    )

    fun discover(limit: Int): Int {
        return transaction.execute {
            jdbc.update(
                "INSERT" + " INTO pawbridge_animal.apms_photo_scan(id,new_cursor,sweep_cursor) " +
                    "SELECT 1,COALESCE(MAX(id),0),COALESCE(MAX(id),0) FROM pawbridge_animal.animals WHERE " + APMS +
                    " ON CONFLICT (id) DO NOTHING",
            )
            val cursors = jdbc.queryForObject(
                "SELECT new_cursor,sweep_cursor FROM pawbridge_animal.apms_photo_scan WHERE id=1 FOR UPDATE",
                RowMapper { row, _ -> longArrayOf(row.getLong(1), row.getLong(2)) },
            )!!
            val added = sources("id>? ORDER BY id ASC", cursors[0], limit)
            val sweep = sources("id<=? ORDER BY id DESC", cursors[1], limit)
            for (animal in added) {
                observe(animal)
            }
            for (animal in sweep) {
                observe(animal)
            }
            val newCursor = if (added.isEmpty()) cursors[0] else added[added.size - 1].id
            val sweepCursor = if (sweep.isEmpty()) {
                jdbc.queryForObject(
                    "SELECT COALESCE(MAX(id),0) FROM pawbridge_animal.animals WHERE " + APMS,
                    Long::class.javaObjectType,
                )!!
            } else {
                sweep[sweep.size - 1].id - 1
            }
            jdbc.update(
                "UPDATE pawbridge_animal.apms_photo_scan SET new_cursor=?,sweep_cursor=? WHERE id=1",
                newCursor,
                sweepCursor,
            )
            added.size + sweep.size
        }!!
    }

    private fun sources(bound: String, cursor: Long, limit: Int): List<AnimalSource> {
        return jdbc.query(
            "SELECT id,apms_desertion_no,image_url,image_url2 FROM pawbridge_animal.animals WHERE " + APMS +
                " AND " + bound + " LIMIT ?",
            RowMapper { row, _ -> AnimalSource(row.getLong(1), row.getString(2), row.getString(3), row.getString(4)) },
            cursor,
            limit,
        )
    }

    private fun observe(animal: AnimalSource) {
        observe(animal, 1, normalized(animal.first))
        observe(animal, 2, normalized(animal.second))
    }

    private fun normalized(url: String?): String? {
        return if (url == null || isBlank(url)) null else javaTrim(url)
    }

    private fun observe(animal: AnimalSource, slot: Int, source: String?) {
        val previous = jdbc.query(
            "SELECT source_url FROM pawbridge_animal.apms_photo_archive WHERE animal_id=? AND slot=? FOR UPDATE",
            RowMapper { row, _ -> row.getString(1) },
            animal.id,
            slot,
        )
        if (previous.isEmpty()) {
            if (source != null) {
                jdbc.update(
                    "INSERT INTO pawbridge_animal.apms_photo_archive(animal_id,slot,desertion_no,source_url,state) " +
                        "VALUES (?,?,?,?,'PENDING')",
                    animal.id,
                    slot,
                    animal.desertionNo,
                    source,
                )
            }
        } else if (previous[0] != source) {
            jdbc.update(
                "UPDATE pawbridge_animal.apms_photo_archive SET source_url=?,generation=generation+1,state=?,attempts=0," +
                    "next_attempt_at=CURRENT_TIMESTAMP(6),lease_token=NULL,lease_until=NULL,error_code=NULL " +
                    "WHERE animal_id=? AND slot=?",
                source,
                if (source == null) "ABSENT" else "PENDING",
                animal.id,
                slot,
            )
        }
    }

    fun claim(leaseSeconds: Int): Optional<Claim> {
        return claim(leaseSeconds, false)
    }

    fun claim(leaseSeconds: Int, preferRecent: Boolean): Optional<Claim> {
        val order = if (preferRecent) "p.animal_id DESC,p.slot" else "p.next_attempt_at,p.animal_id,p.slot"
        return transaction.execute {
            val candidates = jdbc.query(
                "SELECT p.* FROM pawbridge_animal.apms_photo_archive p JOIN pawbridge_animal.animals a ON a.id=p.animal_id " +
                    "WHERE a.api_source='APMS_ANIMAL' AND p.source_url IS NOT NULL AND " +
                    "((p.state IN ('PENDING','RETRY','READY') AND p.next_attempt_at<=CURRENT_TIMESTAMP(6)) " +
                    "OR (p.state='PROCESSING' AND p.lease_until<=CURRENT_TIMESTAMP(6))) " +
                    "ORDER BY " + order + " LIMIT 1 " + "FOR UPDATE OF p SKIP LOCKED",
                RowMapper { row, _ ->
                    Claim(
                        row.getLong("animal_id"),
                        row.getInt("slot"),
                        row.getLong("generation"),
                        UUID.randomUUID().toString(),
                        row.getString("source_url"),
                        row.getInt("attempts"),
                    )
                },
            )
            if (candidates.isEmpty()) {
                return@execute Optional.empty<Claim>()
            }
            val claim = candidates[0]
            jdbc.update(
                "UPDATE pawbridge_animal.apms_photo_archive SET state='PROCESSING',lease_token=?," +
                    "lease_until=" + "(clock_timestamp() + CAST(? AS integer) * INTERVAL '1 second')" +
                    " WHERE animal_id=? AND slot=?",
                claim.token,
                leaseSeconds,
                claim.animalId,
                claim.slot,
            )
            Optional.of(claim)
        }!!
    }

    fun complete(claim: Claim, photo: ArchivedPhoto, recheckSeconds: Int): Boolean {
        // Compare against the current Animal source as well as the discovered generation.
        // A batch update can have committed before discovery visits that animal again.
        return jdbc.update(
            "UPDATE pawbridge_animal.apms_photo_archive p SET " +
                "state='READY',attempts=0,error_code=NULL,lease_token=NULL,lease_until=NULL," +
                "next_attempt_at=" + "(clock_timestamp() + CAST(? AS integer) * INTERVAL '1 second')" + "," +
                "archived_source_url=p.source_url,source_sha256=?,stored_sha256=?,object_key=?," +
                "content_type=?,stored_bytes=?,width=?,height=?,recipe=?,archived_at=CURRENT_TIMESTAMP(6) " +
                "FROM pawbridge_animal.animals a " + "WHERE p.animal_id=? AND p.slot=? AND p.generation=? AND p.lease_token=? " +
                "AND p.lease_until>CURRENT_TIMESTAMP(6) AND a.api_source='APMS_ANIMAL' " +
                "AND a.id=p.animal_id AND " +
                "p.source_url COLLATE \"C\"=TRIM(CASE p.slot WHEN 1 THEN a.image_url ELSE a.image_url2 END) COLLATE \"C\"",
            recheckSeconds,
            photo.sourceHash,
            photo.storedHash,
            photo.objectKey(),
            photo.contentType,
            photo.bytes.size,
            photo.width,
            photo.height,
            photo.recipe,
            claim.animalId,
            claim.slot,
            claim.generation,
            claim.token,
        ) == 1
    }

    fun retry(claim: Claim, code: String?, seconds: Int): Boolean {
        return jdbc.update(
            "UPDATE pawbridge_animal.apms_photo_archive SET state='RETRY',attempts=attempts+1,error_code=?," +
                "next_attempt_at=" + "(clock_timestamp() + CAST(? AS integer) * INTERVAL '1 second')" +
                ",lease_token=NULL,lease_until=NULL " +
                "WHERE animal_id=? AND slot=? AND generation=? AND lease_token=? AND lease_until>CURRENT_TIMESTAMP(6)",
            code,
            seconds,
            claim.animalId,
            claim.slot,
            claim.generation,
            claim.token,
        ) == 1
    }

    // Java String.isBlank(): empty or only Character.isWhitespace characters (Kotlin isBlank also accepts NBSP).
    private fun isBlank(value: String): Boolean = value.all { Character.isWhitespace(it) }

    // Java String.trim() removes every character up to U+0020; Kotlin trim() uses Character.isWhitespace.
    private fun javaTrim(value: String): String = value.trim { it <= ' ' }

    companion object {
        private const val APMS = "api_source='APMS_ANIMAL' AND apms_desertion_no IS NOT NULL"
    }
}
