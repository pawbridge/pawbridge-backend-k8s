package com.pawbridge.animalservice.shelter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.animalservice.search.SearchDocumentWriter;
import java.sql.Connection;
import com.pawbridge.animalservice.persistence.AnimalSqlDialect;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ShelterDirectoryStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final SearchDocumentWriter searchDocuments;
    public ShelterDirectoryStore(JdbcTemplate jdbc, ObjectMapper mapper, SearchDocumentWriter searchDocuments) {
        this.jdbc=jdbc; this.mapper=mapper; this.searchDocuments=searchDocuments;
    }

    void save(Connection connection, List<Map<String, String>> rows, LocalDateTime now) throws Exception {
        AnimalSqlDialect dialect = AnimalSqlDialect.from(connection);
        try (var shelter = connection.prepareStatement(dialect.sql("""
                INSERT INTO shelters(care_reg_no,name,address,phone,organization_name,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE name=VALUES(name),address=COALESCE(VALUES(address),address),
                  organization_name=COALESCE(VALUES(organization_name),organization_name),updated_at=VALUES(updated_at)
                """, """
                INSERT INTO shelters(care_reg_no,name,address,phone,organization_name,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?)
                ON CONFLICT (care_reg_no) DO UPDATE SET name=EXCLUDED.name,
                  address=COALESCE(EXCLUDED.address,shelters.address),
                  organization_name=COALESCE(EXCLUDED.organization_name,shelters.organization_name),updated_at=EXCLUDED.updated_at
                """));
             var details = connection.prepareStatement(dialect.sql("""
                INSERT INTO shelter_public_information(shelter_id,details,collected_at)
                SELECT id,?,? FROM shelters WHERE care_reg_no=?
                ON DUPLICATE KEY UPDATE details=JSON_MERGE_PATCH(details,VALUES(details)),collected_at=VALUES(collected_at)
                """, """
                INSERT INTO shelter_public_information(shelter_id,details,collected_at)
                SELECT id,CAST(? AS jsonb),? FROM shelters WHERE care_reg_no=?
                ON CONFLICT (shelter_id) DO UPDATE SET
                  details=(shelter_public_information.details || EXCLUDED.details) -
                    ARRAY(SELECT key FROM jsonb_each(EXCLUDED.details) WHERE value='null'::jsonb),
                  collected_at=EXCLUDED.collected_at
                """))) {
            // The provider patch contains only scalar fields; explicit null removes that key.
            for (var row : rows) {
                var patch = ShelterPublicInformation.details(row);
                // Existing identity is retained; dated older snapshots must not roll it back.
                try (var previous = connection.prepareStatement("""
                        SELECT s.address,p.details FROM shelters s
                        LEFT JOIN shelter_public_information p ON p.shelter_id=s.id
                        WHERE s.care_reg_no=?
                        """ + dialect.sql(" FOR UPDATE", " FOR UPDATE OF s"))) {
                    previous.setString(1,row.get("careRegNo"));
                    try (var rs=previous.executeQuery()) {
                        if (rs.next()) {
                            String saved=rs.getString(2);
                            if (saved != null && patch.containsKey("sourceUpdatedDate")) {
                                String date=mapper.readTree(saved).path("sourceUpdatedDate").asText("");
                                if (date.compareTo(patch.get("sourceUpdatedDate").toString()) > 0) continue;
                            }
                            if (row.containsKey("careAddr") && !row.get("careAddr").equals(rs.getString(1))
                                    && !patch.containsKey("latitude")) {
                                // An old marker must not be presented at a newly changed address.
                                patch.put("latitude",null); patch.put("longitude",null);
                            }
                        }
                    }
                }
                shelter.setString(1,row.get("careRegNo")); shelter.setString(2,row.get("careNm"));
                shelter.setString(3,row.get("careAddr")); shelter.setString(4,row.get("careTel"));
                shelter.setString(5,row.get("orgNm")); shelter.setObject(6,now); shelter.setObject(7,now);
                shelter.executeUpdate();
                searchDocuments.shelter(connection,row.get("careRegNo"));
                details.setString(1,mapper.writeValueAsString(patch));
                details.setObject(2,now); details.setString(3,row.get("careRegNo")); details.executeUpdate();
            }
        }
    }

    public ShelterPublicInformation find(Long shelterId) {
        return jdbc.query("SELECT details,collected_at FROM shelter_public_information WHERE shelter_id=?", (rs, index) -> {
            try {
                var node = mapper.readTree(rs.getString(1));
                ((com.fasterxml.jackson.databind.node.ObjectNode)node).put("source", "APMS_SHELTER_INFO");
                ((com.fasterxml.jackson.databind.node.ObjectNode)node).put("collectedAt", rs.getTimestamp(2).toLocalDateTime().toString());
                return mapper.treeToValue(node, ShelterPublicInformation.class);
            } catch (Exception exception) { throw new IllegalStateException("Invalid shelter public information"); }
        }, shelterId).stream().findFirst().orElse(null);
    }
}
