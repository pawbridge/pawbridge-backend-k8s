package com.pawbridge.animalservice.service;

import com.pawbridge.animalservice.dto.response.ShelterDiscoveryResponse;
import com.pawbridge.animalservice.dto.response.ShelterDiscoveryResponse.Preview;
import com.pawbridge.animalservice.dto.response.ShelterObservationResponse;
import com.pawbridge.animalservice.exception.ShelterNotFoundException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "pawbridge.animal-query", name = "backend", havingValue = "postgresql")
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
public class ShelterDiscoveryService {
    private final NamedParameterJdbcTemplate jdbc;

    public ShelterDiscoveryService(DataSource source) {
        JdbcTemplate template = new JdbcTemplate(source);
        template.setQueryTimeout(5);
        jdbc = new NamedParameterJdbcTemplate(template);
    }

    public Page<ShelterDiscoveryResponse> discover(String keyword, String address,
            LocalDate from, LocalDate to, int page, int size) {
        IntakeDateRange range = new IntakeDateRange(from, to);
        if (page < 0 || size < 1 || size > 24 || ((long) page + 1) * size > 10_000) {
            throw new IllegalArgumentException("보호소는 페이지당 최대 24개, 결과 10,000개 이내로 조회해주세요.");
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("from", range.from()).addValue("to", range.to())
                .addValue("keyword", like(keyword)).addValue("address", like(address))
                .addValue("limit", size).addValue("offset", (long) page * size);
        String matching = """
                FROM shelters s JOIN animals a ON a.shelter_id = s.id
                WHERE a.status = 'PROTECT' AND a.happen_date BETWEEN :from AND :to
                  AND (s.name ILIKE :keyword ESCAPE '!' OR s.address ILIKE :keyword ESCAPE '!')
                  AND COALESCE(s.address, '') ILIKE :address ESCAPE '!'
                """;
        long total = jdbc.queryForObject("SELECT COUNT(DISTINCT s.id) " + matching, params, Long.class);
        List<ShelterDiscoveryResponse> shelters = jdbc.query("""
                SELECT s.id, s.care_reg_no, s.name, s.address, s.phone, COUNT(*) AS protected_count
                """ + matching + """
                GROUP BY s.id, s.care_reg_no, s.name, s.address, s.phone
                ORDER BY protected_count DESC, s.name ASC, s.id ASC LIMIT :limit OFFSET :offset
                """, params, (row, index) -> new ShelterDiscoveryResponse(
                row.getLong("id"), row.getString("care_reg_no"), row.getString("name"),
                row.getString("address"), row.getString("phone"), row.getLong("protected_count"), List.of()));
        if (shelters.isEmpty()) return new PageImpl<>(List.of(), PageRequest.of(page, size), total);
        params.addValue("ids", shelters.stream().map(ShelterDiscoveryResponse::id).toList());
        Map<Long, List<Preview>> previews = new HashMap<>();
        // One bounded query for the entire page, not one query per shelter.
        jdbc.query("""
                SELECT * FROM (
                  SELECT id, shelter_id, breed, species, gender, birth_year, image_url, happen_date,
                         ROW_NUMBER() OVER (PARTITION BY shelter_id ORDER BY happen_date DESC, id ASC) AS rn
                  FROM animals WHERE shelter_id IN (:ids) AND status = 'PROTECT'
                    AND happen_date BETWEEN :from AND :to
                ) ranked WHERE rn <= 3 ORDER BY shelter_id, rn
                """, params, row -> {
            previews.computeIfAbsent(row.getLong("shelter_id"), key -> new ArrayList<>()).add(new Preview(
                    row.getLong("id"), row.getString("breed"), row.getString("species"), row.getString("gender"),
                    row.getObject("birth_year", Integer.class), row.getString("image_url"),
                    row.getObject("happen_date", LocalDate.class)));
        });
        return new PageImpl<>(shelters.stream().map(s -> new ShelterDiscoveryResponse(
                s.id(), s.careRegNo(), s.name(), s.address(), s.phone(), s.protectedCount(),
                List.copyOf(previews.getOrDefault(s.id(), List.of())))).toList(), PageRequest.of(page, size), total);
    }

    public List<ShelterObservationResponse> observations(long shelterId, LocalDate from, LocalDate to) {
        new IntakeDateRange(from, to);
        MapSqlParameterSource params = new MapSqlParameterSource("id", shelterId)
                .addValue("from", from).addValue("to", to);
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM shelters WHERE id = :id)",
                params, Boolean.class))) throw new ShelterNotFoundException();
        return jdbc.query("""
                SELECT observation_date, observed_at, protected_count FROM shelter_daily_observations
                WHERE shelter_id = :id AND observation_date BETWEEN :from AND :to ORDER BY observation_date
                """, params, (row, index) -> new ShelterObservationResponse(
                row.getObject("observation_date", LocalDate.class),
                row.getObject("observed_at", OffsetDateTime.class), row.getLong("protected_count")));
    }

    private static String like(String text) {
        String value = text == null ? "" : text.trim();
        if (value.length() > 100) throw new IllegalArgumentException("검색어는 100자 이내로 입력해주세요.");
        return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
