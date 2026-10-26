package com.pawbridge.backend.animal.internal.shelterdirectory.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter
import java.sql.Connection
import java.time.LocalDateTime

/** Original PostgreSQL snapshot upsert on the collector's own locked connection. */
class ShelterDirectoryStore(
    private val mapper: ObjectMapper,
    private val searchDocuments: SearchDocumentWriter,
) {

    @Throws(Exception::class)
    fun save(connection: Connection, rows: List<Map<String, String>>, now: LocalDateTime) {
        connection.prepareStatement(SHELTER_SQL).use { shelter ->
            connection.prepareStatement(DETAILS_SQL).use { details ->
                // The provider patch contains only scalar fields; explicit null removes that key.
                for (row in rows) {
                    val patch = ShelterPublicDetails.details(row)
                    // Existing identity is retained; dated older snapshots must not roll it back.
                    var olderSnapshot = false
                    connection.prepareStatement(PREVIOUS_SQL).use { previous ->
                        previous.setString(1, row["careRegNo"])
                        previous.executeQuery().use { rs ->
                            if (rs.next()) {
                                val saved = rs.getString(2)
                                if (saved != null && patch.containsKey("sourceUpdatedDate")) {
                                    val date = mapper.readTree(saved).path("sourceUpdatedDate").asText("")
                                    if (date.compareTo(patch["sourceUpdatedDate"]!!.toString()) > 0) {
                                        olderSnapshot = true
                                    }
                                }
                                if (!olderSnapshot &&
                                    row.containsKey("careAddr") &&
                                    row["careAddr"] != rs.getString(1) &&
                                    !patch.containsKey("latitude")
                                ) {
                                    // An old marker must not be presented at a newly changed address.
                                    patch["latitude"] = null
                                    patch["longitude"] = null
                                }
                            }
                        }
                    }
                    if (olderSnapshot) {
                        continue
                    }
                    shelter.setString(1, row["careRegNo"])
                    shelter.setString(2, row["careNm"])
                    shelter.setString(3, row["careAddr"])
                    shelter.setString(4, row["careTel"])
                    shelter.setString(5, row["orgNm"])
                    shelter.setObject(6, now)
                    shelter.setObject(7, now)
                    shelter.executeUpdate()
                    searchDocuments.shelter(connection, row["careRegNo"])
                    details.setString(1, mapper.writeValueAsString(patch))
                    details.setObject(2, now)
                    details.setString(3, row["careRegNo"])
                    details.executeUpdate()
                }
            }
        }
    }

    companion object {
        // Java text blocks end with a newline when the closing delimiter is on its own line.
        private val SHELTER_SQL = """
            INSERT INTO pawbridge_animal.shelters(care_reg_no,name,address,phone,organization_name,created_at,updated_at)
            VALUES (?,?,?,?,?,?,?)
            ON CONFLICT (care_reg_no) DO UPDATE SET name=EXCLUDED.name,
              address=COALESCE(EXCLUDED.address,shelters.address),
              organization_name=COALESCE(EXCLUDED.organization_name,shelters.organization_name),updated_at=EXCLUDED.updated_at
        """.trimIndent() + "\n"

        private val DETAILS_SQL = """
            INSERT INTO pawbridge_animal.shelter_public_information(shelter_id,details,collected_at)
            SELECT id,CAST(? AS jsonb),? FROM pawbridge_animal.shelters WHERE care_reg_no=?
            ON CONFLICT (shelter_id) DO UPDATE SET
              details=(shelter_public_information.details || EXCLUDED.details) -
                ARRAY(SELECT key FROM jsonb_each(EXCLUDED.details) WHERE value='null'::jsonb),
              collected_at=EXCLUDED.collected_at
        """.trimIndent() + "\n"

        // The closing delimiter shares the last line in Java, so there is no trailing newline.
        private val PREVIOUS_SQL = """
            SELECT s.address,p.details FROM pawbridge_animal.shelters s
            LEFT JOIN pawbridge_animal.shelter_public_information p ON p.shelter_id=s.id
            WHERE s.care_reg_no=?
             FOR UPDATE OF s
        """.trimIndent()
    }
}
