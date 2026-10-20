package com.pawbridge.backend.animal.internal.memberread.infrastructure;

import com.pawbridge.backend.animal.api.AnimalMemberReadUnavailableException;
import com.pawbridge.backend.animal.api.AnimalMemberReads;
import com.pawbridge.backend.animal.api.AnimalShelterReads;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Species;
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Gender;
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.NeuterStatus;
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.AnimalStatus;
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.ApiSource;
import org.springframework.data.mapping.PropertyPath;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/** Only the two selected MyPageAnimalServiceImpl JPA reads, translated to the existing PG schema. */
public final class JdbcAnimalMemberReads implements AnimalMemberReads {
    private static final String FROM = " FROM pawbridge_animal.animals a LEFT JOIN pawbridge_animal.shelters s ON s.id=a.shelter_id";
    private static final String COLUMNS = "a.id,a.apms_notice_no,a.species,a.breed,a.gender,a.birth_year,a.special_mark,"
            + "a.status,a.notice_end_date,a.image_url,a.favorite_count,a.created_at,s.id AS shelter_id,s.name AS shelter_name";
    private static final Map<String, String> ANIMAL_COLUMNS = Map.ofEntries(
            Map.entry("id", "id"), Map.entry("apmsDesertionNo", "apms_desertion_no"), Map.entry("apmsNoticeNo", "apms_notice_no"),
            Map.entry("species", "species"), Map.entry("breed", "breed"), Map.entry("birthYear", "birth_year"),
            Map.entry("weight", "weight"), Map.entry("color", "color"), Map.entry("gender", "gender"),
            Map.entry("neuterStatus", "neuter_status"), Map.entry("specialMark", "special_mark"),
            Map.entry("apmsProcessState", "apms_process_state"), Map.entry("noticeStartDate", "notice_start_date"),
            Map.entry("noticeEndDate", "notice_end_date"), Map.entry("apmsUpdatedAt", "apms_updated_at"),
            Map.entry("happenDate", "happen_date"), Map.entry("happenPlace", "happen_place"),
            Map.entry("imageUrl", "image_url"), Map.entry("imageUrl2", "image_url2"), Map.entry("status", "status"),
            Map.entry("apiSource", "api_source"), Map.entry("favoriteCount", "favorite_count"),
            Map.entry("description", "description"), Map.entry("createdAt", "created_at"), Map.entry("updatedAt", "updated_at"));
    private static final Map<String, String> SHELTER_COLUMNS = Map.ofEntries(
            Map.entry("id", "id"), Map.entry("careRegNo", "care_reg_no"), Map.entry("name", "name"),
            Map.entry("phone", "phone"), Map.entry("address", "address"), Map.entry("ownerName", "owner_name"),
            Map.entry("organizationName", "organization_name"), Map.entry("email", "email"),
            Map.entry("introduction", "introduction"), Map.entry("adoptionProcedure", "adoption_procedure"),
            Map.entry("operatingHours", "operating_hours"), Map.entry("createdAt", "created_at"), Map.entry("updatedAt", "updated_at"));
    private final NamedParameterJdbcOperations jdbc;
    private final TransactionOperations reads;
    private final AnimalShelterReads shelters;

    public JdbcAnimalMemberReads(DataSource source, PlatformTransactionManager manager, AnimalShelterReads shelters) {
        this(new NamedParameterJdbcTemplate(source), independentRead(manager), shelters);
    }
    JdbcAnimalMemberReads(NamedParameterJdbcOperations jdbc, TransactionOperations reads, AnimalShelterReads shelters) {
        this.jdbc = jdbc; this.reads = reads; this.shelters = shelters;
    }
    private static TransactionTemplate independentRead(PlatformTransactionManager manager) {
        TransactionTemplate transaction = new TransactionTemplate(manager);
        transaction.setReadOnly(true);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction;
    }
    @Override public Shelter findByCareRegNo(String careRegNo) { return shelters.findByCareRegNo(careRegNo); }

    @Override public List<Animal> batch(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return new ArrayList<>();
        try {
            return reads.execute(tx -> jdbc.query("SELECT " + COLUMNS + FROM + " WHERE a.id IN (:ids)",
                    new MapSqlParameterSource("ids", new ArrayList<>(ids)), JdbcAnimalMemberReads::animal));
        } catch (RuntimeException failure) { throw new AnimalMemberReadUnavailableException(failure); }
    }
    @Override public Page byShelter(Long shelterId, PageRequest request) {
        try {
            return reads.execute(tx -> {
                if (request.page() < 0 || request.size() < 1) throw new IllegalArgumentException("Invalid effective pageable");
                long offset = (long) request.page() * request.size();
                if (offset > Integer.MAX_VALUE) throw new IllegalArgumentException("Page offset exceeds Integer.MAX_VALUE (2147483647)");
                Sorting sorting = sorting(request.sort());
                var parameters = new MapSqlParameterSource("shelterId", shelterId)
                        .addValue("limit", request.size()).addValue("offset", offset);
                List<Animal> content = jdbc.query("SELECT " + COLUMNS + FROM + sorting.joins()
                        + " WHERE a.shelter_id=:shelterId AND a.api_source='MANUAL'" + sorting.orderBy() + " LIMIT :limit OFFSET :offset",
                        parameters, JdbcAnimalMemberReads::animal);
                if (sorting.collectionJoin()) {
                    // Hibernate6 root identity deduplication happens AFTER SQL LIMIT/OFFSET.
                    Map<Long, Animal> roots = new LinkedHashMap<>();
                    content.forEach(animal -> roots.putIfAbsent(animal.id(), animal));
                    content = new ArrayList<>(roots.values());
                }
                long total;
                // PageableExecutionUtils skips count on a partial nonempty page, or any partial first page.
                if (content.size() < request.size() && (request.page() == 0 || !content.isEmpty())) total = offset + content.size();
                else total = jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal.animals a"
                        + " WHERE a.shelter_id=:shelterId AND a.api_source='MANUAL'", parameters, Long.class);
                // PageImpl adapts stale count only for a nonempty final page.
                if (!content.isEmpty() && offset + request.size() > total) total = offset + content.size();
                int totalPages = (int) Math.ceil((double) total / request.size());
                return new Page(content, request.page(), request.size(), total, totalPages, request.page() + 1 >= totalPages);
            });
        } catch (RuntimeException failure) { throw new AnimalMemberReadUnavailableException(failure); }
    }

    /** The remote resolver parses ONE flattened sort value; it does not parse alternating pairs. */
    static String ordering(String wireSort) { return sorting(wireSort).orderBy(); }

    private static Sorting sorting(String wireSort) {
        if (wireSort == null || wireSort.isEmpty()) return new Sorting("", " ORDER BY a.created_at DESC", false);
        List<String> parts = new ArrayList<>(Arrays.stream(wireSort.split(",")).filter(p -> !p.chars().allMatch(c -> c == '.')).toList());
        boolean ignoreCase = !parts.isEmpty() && parts.getLast().equalsIgnoreCase("ignorecase");
        if (ignoreCase) parts.removeLast();
        String direction = "ASC";
        if (!parts.isEmpty() && (parts.getLast().equalsIgnoreCase("asc") || parts.getLast().equalsIgnoreCase("desc")))
            direction = parts.removeLast().equalsIgnoreCase("desc") ? "DESC" : "ASC";
        List<String> orders = new ArrayList<>();
        StringBuilder joins = new StringBuilder();
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("shelter", "s"); // The DTO projection already joins this exact association.
        boolean collectionJoin = false;
        for (String property : parts) {
            if (property.isBlank()) continue;
            PropertyPath parsed = PropertyPath.from(property, AnimalSortProperties.class);
            List<String> path = new ArrayList<>(List.of(parsed.toDotPath().split("\\.")));
            Class<?> leafType = parsed.getLeafProperty().getType();
            // Criteria ordering by an entity association orders its primary key.
            if (leafType == AnimalSortProperties.class || leafType == ShelterSortProperties.class) path.add("id");
            String alias = "a", prefix = "";
            boolean animal = true;
            for (int index = 0; index < path.size() - 1; index++) {
                String relation = path.get(index);
                if (!(animal ? "shelter" : "animals").equals(relation))
                    throw new IllegalStateException("Unmapped original JPA sort path: " + property);
                prefix += (prefix.isEmpty() ? "" : ".") + relation;
                String joined = aliases.get(prefix);
                if (joined == null) {
                    joined = "sort_" + (aliases.size() - 1);
                    aliases.put(prefix, joined);
                    joins.append(animal ? " LEFT JOIN pawbridge_animal.shelters " : " LEFT JOIN pawbridge_animal.animals ")
                            .append(joined).append(" ON ").append(joined).append(animal ? ".id=" : ".shelter_id=")
                            .append(alias).append(animal ? ".shelter_id" : ".id");
                }
                if (!animal) collectionJoin = true;
                alias = joined;
                animal = !animal;
            }
            String leaf = path.getLast();
            String column = (animal ? ANIMAL_COLUMNS : SHELTER_COLUMNS).get(leaf);
            if (column == null) throw new IllegalStateException("Unmapped original JPA sort path: " + property);
            String expression = alias + "." + column;
            // Enum/date/number properties do not acquire ignoreCase just because their wire form is text.
            if (ignoreCase && leafType == String.class) expression = "lower(" + expression + ")";
            orders.add(expression + " " + direction);
        }
        return new Sorting(joins.toString(), orders.isEmpty() ? "" : " ORDER BY " + String.join(",", orders), collectionJoin);
    }
    private record Sorting(String joins, String orderBy, boolean collectionJoin) { }

    // Only the two selected source entities' persistent property shapes, for the existing
    // Spring Data PropertyPath grammar (case, camel, underscore, quoting and depth semantics).
    // These are private metadata, never instantiated, serialized, persisted or exposed as module API.
    private record AnimalSortProperties(Long id, String apmsDesertionNo, String apmsNoticeNo, Species species,
            String breed, Integer birthYear, String weight, String color, Gender gender, NeuterStatus neuterStatus,
            String specialMark, String apmsProcessState, LocalDate noticeStartDate, LocalDate noticeEndDate,
            LocalDateTime apmsUpdatedAt, LocalDate happenDate, String happenPlace, String imageUrl, String imageUrl2,
            ShelterSortProperties shelter, AnimalStatus status, ApiSource apiSource, Integer favoriteCount,
            String description, LocalDateTime createdAt, LocalDateTime updatedAt) { }
    private record ShelterSortProperties(Long id, String careRegNo, String name, String phone, String address,
            String ownerName, String organizationName, String email, String introduction, String adoptionProcedure,
            String operatingHours, LocalDateTime createdAt, LocalDateTime updatedAt, List<AnimalSortProperties> animals) { }
    static Animal animal(ResultSet row, int index) throws SQLException {
        Integer year = row.getObject("birth_year", Integer.class);
        return new Animal(row.getObject("id", Long.class), row.getString("apms_notice_no"), row.getString("species"),
                row.getString("breed"), row.getString("gender"), year, year == null ? null : LocalDate.now().getYear() - year,
                row.getString("special_mark"), row.getString("status"), row.getObject("notice_end_date", LocalDate.class),
                row.getString("image_url"), row.getObject("favorite_count", Integer.class), row.getObject("shelter_id", Long.class),
                row.getString("shelter_name"), row.getObject("created_at", LocalDateTime.class));
    }
}
