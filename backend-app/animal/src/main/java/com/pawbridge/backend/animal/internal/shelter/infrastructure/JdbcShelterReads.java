package com.pawbridge.backend.animal.internal.shelter.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pawbridge.backend.animal.internal.discovery.application.IntakeDateRange;
import com.pawbridge.backend.animal.internal.shelter.application.ShelterNotFoundException;
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/** Selected original read SQL and JPA scalar queries; no writes or background work. */
public final class JdbcShelterReads implements ShelterReads {
    private static final String SUMMARY = "s.id,s.care_reg_no,s.name,s.phone,s.address,s.organization_name,s.created_at";
    private static final String DETAILS = SUMMARY + ",s.owner_name,s.email,s.introduction,s.adoption_procedure,s.operating_hours,s.updated_at";
    private static final String FROM = " FROM pawbridge_animal.shelters s ";
    private static final Map<String, String> SORTS = Map.ofEntries(
            Map.entry("id", "id"), Map.entry("careRegNo", "care_reg_no"), Map.entry("name", "name"),
            Map.entry("phone", "phone"), Map.entry("address", "address"), Map.entry("ownerName", "owner_name"),
            Map.entry("organizationName", "organization_name"), Map.entry("email", "email"),
            Map.entry("introduction", "introduction"), Map.entry("adoptionProcedure", "adoption_procedure"),
            Map.entry("operatingHours", "operating_hours"), Map.entry("createdAt", "created_at"), Map.entry("updatedAt", "updated_at"));
    private static final Map<String, String> ANIMAL_SORTS = Map.ofEntries(
            Map.entry("id", "id"), Map.entry("apmsDesertionNo", "apms_desertion_no"), Map.entry("apmsNoticeNo", "apms_notice_no"),
            Map.entry("species", "species"), Map.entry("breed", "breed"), Map.entry("birthYear", "birth_year"),
            Map.entry("weight", "weight"), Map.entry("color", "color"), Map.entry("gender", "gender"),
            Map.entry("neuterStatus", "neuter_status"), Map.entry("specialMark", "special_mark"), Map.entry("apmsProcessState", "apms_process_state"),
            Map.entry("noticeStartDate", "notice_start_date"), Map.entry("noticeEndDate", "notice_end_date"), Map.entry("apmsUpdatedAt", "apms_updated_at"),
            Map.entry("happenDate", "happen_date"), Map.entry("happenPlace", "happen_place"), Map.entry("imageUrl", "image_url"),
            Map.entry("imageUrl2", "image_url2"), Map.entry("status", "status"), Map.entry("apiSource", "api_source"),
            Map.entry("favoriteCount", "favorite_count"), Map.entry("description", "description"),
            Map.entry("createdAt", "created_at"), Map.entry("updatedAt", "updated_at"));
    private static final List<String> ANIMAL_TEXT = List.of("apmsDesertionNo", "apmsNoticeNo", "breed", "weight", "color", "specialMark",
            "apmsProcessState", "happenPlace", "imageUrl", "imageUrl2", "description");
    private final NamedParameterJdbcOperations jdbc;
    private final NamedParameterJdbcOperations discoveryJdbc;
    private final TransactionOperations reads;
    private final TransactionOperations discoveryReads;
    private final ObjectMapper mapper;

    public JdbcShelterReads(DataSource source, PlatformTransactionManager manager, ObjectMapper mapper) {
        this.jdbc = new NamedParameterJdbcTemplate(new JdbcTemplate(source));
        JdbcTemplate discoveryTemplate = new JdbcTemplate(source);
        discoveryTemplate.setQueryTimeout(5);
        this.discoveryJdbc = new NamedParameterJdbcTemplate(discoveryTemplate);
        TransactionTemplate regular = new TransactionTemplate(manager);
        regular.setReadOnly(true);
        this.reads = regular;
        TransactionTemplate discovery = new TransactionTemplate(manager);
        discovery.setReadOnly(true);
        discovery.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        discovery.setTimeout(5);
        this.discoveryReads = discovery;
        this.mapper = mapper;
    }

    JdbcShelterReads(NamedParameterJdbcOperations jdbc, NamedParameterJdbcOperations discoveryJdbc,
                    TransactionOperations reads, TransactionOperations discoveryReads, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.discoveryJdbc = discoveryJdbc;
        this.reads = reads;
        this.discoveryReads = discoveryReads;
        this.mapper = mapper;
    }

    @Override
    public Page<Summary> search(Search search, PageRequest page) {
        return reads.execute(status -> {
            if (page.offset() > Integer.MAX_VALUE) throw new InvalidDataAccessApiUsageException("Page offset exceeds Integer.MAX_VALUE (2147483647)");
            MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", page.size()).addValue("offset", page.offset());
            String where = filter(search, params);
            Sorting sorting = ordering(page.orders(), search.keyword() != null);
            List<Summary> rows = jdbc.query("SELECT " + SUMMARY + FROM + sorting.joins() + where + sorting.orderBy() + " LIMIT :limit OFFSET :offset",
                    params, JdbcShelterReads::summary);
            if (sorting.collectionJoin()) {
                // Hibernate6 ALLOW semantics deduplicate entity roots after SQL LIMIT/OFFSET.
                Map<Long, Summary> roots = new LinkedHashMap<>();
                rows.forEach(row -> roots.putIfAbsent(row.id(), row));
                rows = List.copyOf(roots.values());
            }
            // Match PageableExecutionUtils: no count for an inferable partial page.
            long total;
            if (rows.size() < page.size() && (page.number() == 0 || !rows.isEmpty())) {
                total = page.offset() + rows.size();
            } else {
                total = jdbc.queryForObject("SELECT count(*)" + FROM + where, params, Long.class);
            }
            return new Page<>(rows, total);
        });
    }

    private static String filter(Search search, MapSqlParameterSource params) {
        if (hasText(search.keyword()) && hasText(search.address())) {
            params.addValue("keyword", "%" + search.keyword().trim() + "%").addValue("address", "%" + search.address().trim() + "%");
            return "WHERE s.address LIKE :address ESCAPE '' AND (s.name LIKE :keyword ESCAPE '' OR s.address LIKE :keyword ESCAPE '')";
        }
        if (search.keyword() != null) {
            params.addValue("keyword", "%" + search.keyword() + "%");
            // Hibernate PostgreSQL's JPQL LIKE emits ESCAPE '' when no explicit escape is set.
            return "WHERE s.name LIKE :keyword ESCAPE '' OR s.address LIKE :keyword ESCAPE ''";
        }
        if (search.name() != null) return containing("name", search.name(), params);
        if (search.address() != null) return containing("address", search.address(), params);
        if (search.organizationName() != null) return containing("organization_name", search.organizationName(), params);
        return "";
    }

    private static String containing(String column, String value, MapSqlParameterSource params) {
        // Spring Data derived Containing escapes the default backslash, '%' and '_'.
        params.addValue("term", "%" + value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        return "WHERE s." + column + " LIKE :term ESCAPE '\\'";
    }

    private static Sorting ordering(List<Order> orders, boolean declaredQuery) {
        List<String> terms = new ArrayList<>();
        StringBuilder joins = new StringBuilder();
        Map<String, String> aliases = new LinkedHashMap<>();
        for (Order order : orders) {
            String property = declaredQuery && order.property().startsWith("s.") ? order.property().substring(2) : order.property();
            String[] path = declaredQuery ? property.split("\\.", -1) : derivedSortPath(property);
            String alias = "s", prefix = "";
            boolean animal = false;
            for (int index = 0; index < path.length - 1; index++) {
                String relation = path[index];
                if (!declaredQuery && !relation.isEmpty()) relation = Character.toLowerCase(relation.charAt(0)) + relation.substring(1);
                if ((!animal && !relation.equals("animals")) || (animal && !relation.equals("shelter"))) {
                    throw new IllegalStateException("Unknown shelter sort property");
                }
                if (declaredQuery) throw new InvalidDataAccessApiUsageException("Collection paths require the source derived-query sort");
                prefix += "." + relation;
                String joined = aliases.get(prefix);
                if (joined == null) {
                    joined = "sort_" + aliases.size();
                    aliases.put(prefix, joined);
                    joins.append(animal ? " LEFT JOIN pawbridge_animal.shelters " : " LEFT JOIN pawbridge_animal.animals ")
                            .append(joined).append(" ON ").append(joined).append(animal ? ".id=" : ".shelter_id=")
                            .append(alias).append(animal ? ".shelter_id " : ".id ");
                }
                alias = joined;
                animal = !animal;
            }
            String leaf = path[path.length - 1];
            if (!declaredQuery && !leaf.isEmpty()) leaf = Character.toLowerCase(leaf.charAt(0)) + leaf.substring(1);
            String column = (animal ? ANIMAL_SORTS : SORTS).get(leaf);
            if (column == null) throw new IllegalStateException("Unknown shelter sort property");
            String expression = alias + "." + column;
            boolean text = animal ? ANIMAL_TEXT.contains(leaf) : !List.of("id", "createdAt", "updatedAt").contains(leaf);
            if (order.ignoreCase() && (text || declaredQuery)) expression = "lower(" + expression + ")";
            terms.add(expression + (order.ascending() ? " ASC" : " DESC"));
        }
        // No synthetic ID tie-break or null ordering: the source JPA scalar sort has neither.
        return new Sorting(joins.toString(), terms.isEmpty() ? "" : " ORDER BY " + String.join(",", terms), !aliases.isEmpty());
    }
    private record Sorting(String joins, String orderBy, boolean collectionJoin) { }

    private static String[] derivedSortPath(String property) {
        // Source PropertyPath supports dot/underscore and camel paths across these two entities.
        List<String> path = new ArrayList<>();
        boolean animal = false;
        for (String part : property.split("[._]", -1)) {
            String remaining = part;
            while (!remaining.isEmpty()) {
                String value = Character.toLowerCase(remaining.charAt(0)) + remaining.substring(1);
                if ((animal ? ANIMAL_SORTS : SORTS).containsKey(value)) {
                    path.add(value);
                    remaining = "";
                } else {
                    String relation = animal ? "shelter" : "animals";
                    if (!value.startsWith(relation) || (value.length() > relation.length()
                            && !Character.isUpperCase(value.charAt(relation.length())))) {
                        throw new IllegalStateException("Unknown shelter sort property");
                    }
                    path.add(relation);
                    animal = !animal;
                    remaining = value.substring(relation.length());
                }
            }
            if (part.isEmpty()) throw new IllegalStateException("Unknown shelter sort property");
        }
        if (!path.isEmpty() && List.of("animals", "shelter").contains(path.getLast())) path.add("id");
        return path.toArray(String[]::new);
    }

    @Override
    public Details findById(long id) { return detail("s.id=:value", id); }
    @Override
    public Details findByCareRegNo(String careRegNo) { return detail("s.care_reg_no=:value", careRegNo); }

    private Details detail(String predicate, Object value) {
        return reads.execute(status -> {
            Details shelter = jdbc.query("SELECT " + DETAILS + FROM + "WHERE " + predicate,
                    new MapSqlParameterSource("value", value), JdbcShelterReads::details).stream().findFirst()
                    // Original ShelterQueryService's EntityNotFoundException reaches catch-all500.
                    .orElseThrow(() -> new IllegalStateException("Shelter not found: " + value));
            PublicInformation information = jdbc.query("SELECT details,collected_at FROM pawbridge_animal.shelter_public_information WHERE shelter_id=:id",
                    new MapSqlParameterSource("id", shelter.id()), (row, index) -> publicInformation(row)).stream().findFirst().orElse(null);
            return new Details(shelter.id(), shelter.careRegNo(), shelter.name(), shelter.phone(), shelter.address(), shelter.ownerName(),
                    shelter.organizationName(), shelter.email(), shelter.introduction(), shelter.adoptionProcedure(), shelter.operatingHours(),
                    shelter.createdAt(), shelter.updatedAt(), information);
        });
    }

    private PublicInformation publicInformation(ResultSet row) {
        try {
            ObjectNode node = (ObjectNode) mapper.readTree(row.getString("details"));
            node.put("source", "APMS_SHELTER_INFO");
            node.put("collectedAt", row.getTimestamp("collected_at").toLocalDateTime().toString());
            return mapper.treeToValue(node, PublicInformation.class);
        } catch (Exception failure) {
            throw new IllegalStateException("Invalid shelter public information");
        }
    }

    @Override
    public long count() { return reads.execute(status -> jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal.shelters", new MapSqlParameterSource(), Long.class)); }

    @Override
    public Page<Discovery> discover(String keyword, String address, LocalDate from, LocalDate to, int page, int size) {
        return discoveryReads.execute(status -> {
            IntakeDateRange range = new IntakeDateRange(from, to);
            if (page < 0 || size < 1 || size > 24 || ((long) page + 1) * size > 10_000) {
                throw new IllegalArgumentException("보호소는 페이지당 최대 24개, 결과 10,000개 이내로 조회해주세요.");
            }
            MapSqlParameterSource params = new MapSqlParameterSource().addValue("from", range.from()).addValue("to", range.to())
                    .addValue("keyword", like(keyword)).addValue("address", like(address)).addValue("limit", size).addValue("offset", (long) page * size);
            String matching = """
                    FROM pawbridge_animal.shelters s JOIN pawbridge_animal.animals a ON a.shelter_id = s.id
                    WHERE a.status = 'PROTECT' AND a.happen_date BETWEEN :from AND :to
                      AND (s.name ILIKE :keyword ESCAPE '!' OR s.address ILIKE :keyword ESCAPE '!')
                      AND COALESCE(s.address, '') ILIKE :address ESCAPE '!'
                    """;
            long total = discoveryJdbc.queryForObject("SELECT COUNT(DISTINCT s.id) " + matching, params, Long.class);
            List<Discovery> shelters = discoveryJdbc.query("""
                    SELECT s.id, s.care_reg_no, s.name, s.address, s.phone, COUNT(*) AS protected_count
                    """ + matching + """
                    GROUP BY s.id, s.care_reg_no, s.name, s.address, s.phone
                    ORDER BY protected_count DESC, s.name ASC, s.id ASC LIMIT :limit OFFSET :offset
                    """, params, (row, index) -> new Discovery(row.getLong("id"), row.getString("care_reg_no"), row.getString("name"),
                    row.getString("address"), row.getString("phone"), row.getLong("protected_count"), List.of()));
            if (shelters.isEmpty()) return new Page<>(List.of(), total);
            params.addValue("ids", shelters.stream().map(Discovery::id).toList());
            Map<Long, List<Preview>> previews = new HashMap<>();
            discoveryJdbc.query("""
                    SELECT * FROM (
                      SELECT id, shelter_id, breed, species, gender, birth_year, image_url, happen_date,
                             ROW_NUMBER() OVER (PARTITION BY shelter_id ORDER BY happen_date DESC, id ASC) AS rn
                      FROM pawbridge_animal.animals WHERE shelter_id IN (:ids) AND status = 'PROTECT'
                        AND happen_date BETWEEN :from AND :to
                    ) ranked WHERE rn <= 3 ORDER BY shelter_id, rn
                    """, params, row -> {
                previews.computeIfAbsent(row.getLong("shelter_id"), key -> new ArrayList<>()).add(new Preview(
                        row.getLong("id"), row.getString("breed"), row.getString("species"), row.getString("gender"),
                        row.getObject("birth_year", Integer.class), row.getString("image_url"), row.getObject("happen_date", LocalDate.class)));
            });
            return new Page<>(shelters.stream().map(s -> new Discovery(s.id(), s.careRegNo(), s.name(), s.address(), s.phone(),
                    s.protectedCount(), List.copyOf(previews.getOrDefault(s.id(), List.of())))).toList(), total);
        });
    }

    @Override
    public List<Observation> observations(long shelterId, LocalDate from, LocalDate to) {
        return discoveryReads.execute(status -> {
            new IntakeDateRange(from, to);
            MapSqlParameterSource params = new MapSqlParameterSource("id", shelterId).addValue("from", from).addValue("to", to);
            if (!Boolean.TRUE.equals(discoveryJdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pawbridge_animal.shelters WHERE id=:id)", params, Boolean.class))) {
                throw new ShelterNotFoundException();
            }
            return discoveryJdbc.query("""
                    SELECT observation_date, observed_at, protected_count FROM pawbridge_animal.shelter_daily_observations
                    WHERE shelter_id = :id AND observation_date BETWEEN :from AND :to ORDER BY observation_date
                    """, params, (row, index) -> new Observation(row.getObject("observation_date", LocalDate.class),
                    row.getObject("observed_at", OffsetDateTime.class), row.getLong("protected_count")));
        });
    }

    private static String like(String text) {
        String value = text == null ? "" : text.trim();
        if (value.length() > 100) throw new IllegalArgumentException("검색어는 100자 이내로 입력해주세요.");
        return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
    private static Summary summary(ResultSet row, int index) throws SQLException {
        return new Summary(row.getObject("id", Long.class), row.getString("care_reg_no"), row.getString("name"), row.getString("phone"),
                row.getString("address"), row.getString("organization_name"), row.getObject("created_at", LocalDateTime.class));
    }
    private static Details details(ResultSet row, int index) throws SQLException {
        Summary s = summary(row, index);
        return new Details(s.id(), s.careRegNo(), s.name(), s.phone(), s.address(), row.getString("owner_name"), s.organizationName(),
                row.getString("email"), row.getString("introduction"), row.getString("adoption_procedure"), row.getString("operating_hours"),
                s.createdAt(), row.getObject("updated_at", LocalDateTime.class), null);
    }
}
