package com.pawbridge.backend.animal.internal.discovery.infrastructure;

import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery;
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.*;
import com.pawbridge.backend.animal.internal.discovery.application.AnimalNotFoundException;
import com.pawbridge.backend.animal.internal.discovery.application.IntakeDateRange;
import com.pawbridge.backend.animal.internal.discovery.application.SearchProjectionPendingException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/** Selected list SQL from PostgresqlAnimalQueryService; owns no search projection or writes. */
public final class JdbcAnimalDiscovery implements AnimalDiscovery {
    private static final String FROM = " FROM pawbridge_animal.animals a JOIN pawbridge_animal.shelters s ON s.id=a.shelter_id ";
    private static final String COLUMNS = "a.id,a.apms_notice_no,a.species,a.breed,a.gender,a.birth_year,"
            + "a.special_mark,a.status,a.notice_end_date,a.image_url,a.favorite_count,a.created_at,"
            + "s.id AS shelter_id,s.name AS shelter_name";
    private static final String DETAIL_COLUMNS = COLUMNS + ",a.neuter_status,a.weight,a.color,"
            + "a.notice_start_date,a.happen_place,a.happen_date,a.image_url2,a.description,"
            + "a.apms_desertion_no,a.apms_process_state,a.apms_updated_at,a.api_source,a.updated_at";
    private final NamedParameterJdbcOperations jdbc;
    private final TransactionOperations transactions;
    private final KoreanSearchAnalyzer analyzer;

    public JdbcAnimalDiscovery(DataSource source, PlatformTransactionManager manager, KoreanSearchAnalyzer analyzer) {
        JdbcTemplate template = new JdbcTemplate(source);
        template.setQueryTimeout(5);
        this.jdbc = new NamedParameterJdbcTemplate(template);
        TransactionTemplate reads = new TransactionTemplate(manager);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setTimeout(5);
        this.transactions = reads;
        this.analyzer = Objects.requireNonNull(analyzer);
    }

    JdbcAnimalDiscovery(NamedParameterJdbcOperations jdbc, TransactionOperations transactions, KoreanSearchAnalyzer analyzer) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.analyzer = Objects.requireNonNull(analyzer);
    }

    @Override
    public Page search(Search request, PageRequest pageable) {
        return transactions.execute(status -> {
            validatePage(pageable);
            String keyword = text(request.keyword());
            String breed = text(request.breed());
            if (keyword != null) terms(keyword);
            if (breed != null) terms(breed);
            String ordering = ordering(pageable, keyword != null || breed != null);
            Conditions conditions = new Conditions();
            String notice = request.noticeNo() == null || request.noticeNo().isBlank() ? null : request.noticeNo().trim();
            conditions.equal("a.apms_notice_no", "notice", notice);
            conditions.equal("a.species", "species", request.species());
            conditions.equal("a.gender", "gender", request.gender());
            conditions.equal("a.neuter_status", "neuter", request.neuterStatus());
            conditions.equal("a.shelter_id", "shelter", request.shelterId());
            if (request.intakeFrom() != null || request.intakeTo() != null) {
                IntakeDateRange range = new IntakeDateRange(request.intakeFrom(), request.intakeTo());
                conditions.where.add("a.happen_date BETWEEN :intakeFrom AND :intakeTo");
                conditions.parameters.addValue("intakeFrom", range.from()).addValue("intakeTo", range.to());
            }
            if (request.status() != null) conditions.equal("a.status", "status", request.status());
            else if (notice == null) conditions.where.add("a.status IN ('NOTICE','PROTECT')");
            int year = LocalDate.now().getYear();
            if (request.minAge() != null) {
                conditions.where.add("a.birth_year<=:maxBirthYear");
                conditions.parameters.addValue("maxBirthYear", year - request.minAge());
            }
            if (request.maxAge() != null) {
                conditions.where.add("a.birth_year>=:minBirthYear");
                conditions.parameters.addValue("minBirthYear", year - request.maxAge());
            }
            address(conditions, "region", request.region());
            address(conditions, "city", request.city());
            if (breed != null) addBreed(conditions, breed);
            if (keyword != null) {
                conditions.from = FROM + " LEFT JOIN pawbridge_animal.animal_search_documents ad ON ad.animal_id=a.id "
                        + "LEFT JOIN pawbridge_animal.shelter_search_documents sd ON sd.shelter_id=s.id ";
                requirePrepared(conditions);
                addAnalyzedText(conditions, "keyword", keyword);
            }
            return page(conditions, pageable, ordering);
        });
    }

    @Override
    public Details findById(long id) {
        return transactions.execute(status -> jdbc.query("SELECT " + DETAIL_COLUMNS + FROM + "WHERE a.id=:id",
                new MapSqlParameterSource("id", id), JdbcAnimalDiscovery::detail).stream()
                .findFirst().orElseThrow(AnimalNotFoundException::new));
    }

    @Override
    public Details findByApmsDesertionNo(String number) {
        return transactions.execute(status -> jdbc.query("SELECT " + DETAIL_COLUMNS + FROM
                + "WHERE a.apms_desertion_no=:number", new MapSqlParameterSource("number", number),
                JdbcAnimalDiscovery::detail).stream().findFirst().orElseThrow(AnimalNotFoundException::new));
    }

    @Override
    public Page findExpiringSoonAnimals(PageRequest pageable) {
        return transactions.execute(status -> {
            validatePage(pageable);
            Conditions conditions = new Conditions();
            conditions.equal("a.status", "status", AnimalStatus.PROTECT);
            conditions.where.add("a.notice_end_date BETWEEN :today AND :deadline");
            LocalDate today = LocalDate.now();
            conditions.parameters.addValue("today", today).addValue("deadline", today.plusDays(3));
            return page(conditions, pageable, "a.notice_end_date ASC NULLS LAST,a.id ASC");
        });
    }

    private Page page(Conditions conditions, PageRequest pageable, String ordering) {
        String where = " WHERE " + (conditions.where.isEmpty() ? "TRUE" : String.join(" AND ", conditions.where));
        long total = jdbc.queryForObject("SELECT count(*)" + conditions.from + where, conditions.parameters, Long.class);
        if (total == 0 || pageable.offset() >= total) return Page.from(List.of(), pageable, total);
        conditions.parameters.addValue("limit", pageable.size()).addValue("offset", pageable.offset());
        String score = conditions.scores.isEmpty() ? "0" : String.join(" + ", conditions.scores);
        List<Summary> content = jdbc.query("SELECT " + COLUMNS + ",(" + score + ") AS relevance" + conditions.from + where
                + " ORDER BY " + ordering + " LIMIT :limit OFFSET :offset", conditions.parameters, JdbcAnimalDiscovery::summary);
        return Page.from(content, pageable, total);
    }

    /** Keep the source's related dirty-row scope; the separate projector owns integrity audits. */
    private void requirePrepared(Conditions conditions) {
        String filters = conditions.where.isEmpty() ? "TRUE" : String.join(" AND ", conditions.where);
        Boolean pending = jdbc.queryForObject("SELECT EXISTS(SELECT 1" + FROM
                + "WHERE (" + filters + ") AND a.search_dirty) OR EXISTS(SELECT 1 FROM pawbridge_animal.shelters s"
                + " WHERE s.search_dirty AND EXISTS(SELECT 1 FROM pawbridge_animal.animals a"
                + " WHERE a.shelter_id=s.id AND (" + filters + ")))", conditions.parameters, Boolean.class);
        if (Boolean.TRUE.equals(pending)) throw new SearchProjectionPendingException();
    }

    private void addAnalyzedText(Conditions conditions, String name, String value) {
        terms(value);
        try {
            KoreanSearchAnalyzer.Relation relation = analyzer.queryRelation(value);
            List<String> analyzed = relation == null ? analyzer.tokens(value) : relation.tokens();
            if (analyzed.isEmpty()) { conditions.where.add("FALSE"); return; }
            String vector = "(ad.tokens || sd.tokens)";
            String query = "plainto_tsquery('simple',:" + name + "Tokens)";
            conditions.parameters.addValue(name + "Tokens", String.join(" ", analyzed));
            if (relation == null) {
                int tokenIndex = 0;
                for (String token : new java.util.LinkedHashSet<>(analyzed)) {
                    String parameter = name + "Term" + tokenIndex++;
                    conditions.parameters.addValue(parameter, token);
                    String termQuery = "plainto_tsquery('simple',:" + parameter + ")";
                    conditions.where.add("(numnode(" + termQuery + ")<>1 OR a.id IN (SELECT d.animal_id FROM pawbridge_animal.animal_search_documents d WHERE d.tokens @@ " + termQuery
                            + " UNION ALL SELECT resident.id FROM pawbridge_animal.shelter_search_documents shelter_doc"
                            + " JOIN pawbridge_animal.animals resident ON resident.shelter_id=shelter_doc.shelter_id WHERE shelter_doc.tokens @@ " + termQuery + "))");
                    if (tokenIndex == 3) break;
                }
            }
            conditions.where.add(vector + " @@ " + query);
            conditions.scores.add("ts_rank_cd(" + vector + "," + query + ",32)");
            if (relation != null) {
                conditions.parameters.addValue("relation", relation.key());
                conditions.where.add("ad.relation_tokens @@ plainto_tsquery('simple',:relation)");
            }
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    private static void address(Conditions conditions, String name, String value) {
        String normalized = text(value);
        if (normalized == null) return;
        String[] terms = terms(normalized);
        for (int i = 0; i < terms.length; i++) {
            String key = name + i;
            conditions.where.add("lower(s.address) LIKE :" + key + " ESCAPE '!'");
            conditions.parameters.addValue(key, like(terms[i]));
        }
    }

    private static void addBreed(Conditions conditions, String value) {
        conditions.parameters.addValue("breedPart", like(value)).addValue("breedExact", value);
        conditions.where.add("lower(a.breed) LIKE :breedPart ESCAPE '!'");
        conditions.scores.add("CASE WHEN lower(a.breed)=:breedExact THEN 100 ELSE 0 END");
    }

    private static String text(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 200) throw new IllegalArgumentException("검색어는 200자 이하여야 합니다.");
        return normalized;
    }
    private static String[] terms(String value) {
        String[] terms = value.split("\\s+");
        if (terms.length > 8) throw new IllegalArgumentException("검색어는 8개 단어 이하여야 합니다.");
        return terms;
    }
    private static String like(String value) {
        return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
    private static void validatePage(PageRequest pageable) {
        if (!pageable.paged() || pageable.size() < 1 || pageable.size() > 100 || pageable.number() < 0)
            throw new IllegalArgumentException("페이지 크기는 1 이상 100 이하여야 합니다.");
        if (pageable.orders().size() > 1)
            throw new IllegalArgumentException("정렬 기준은 하나만 지정할 수 있습니다.");
        if (pageable.offset() + pageable.size() > 10_000)
            throw new IllegalArgumentException("검색 결과는 최대 10,000건까지만 조회할 수 있습니다. 검색 조건을 좁혀주세요.");
    }
    private static String ordering(PageRequest pageable, boolean hasText) {
        Order order = pageable.orders().isEmpty() ? new Order("noticeEndDate", true) : pageable.orders().getFirst();
        String property = order.property();
        if (property.equals("relevance")) {
            if (!hasText) throw new IllegalArgumentException("관련도순 정렬에는 키워드나 품종이 필요합니다.");
            return "relevance DESC,a.created_at DESC NULLS LAST,a.id ASC";
        }
        String column = switch (property) {
            case "createdAt", "created_at" -> "a.created_at";
            case "happenDate", "happen_date" -> "a.happen_date";
            case "updatedAt", "updated_at" -> "a.updated_at";
            case "noticeEndDate", "notice_end_date" -> "a.notice_end_date";
            case "birthYear", "birth_year", "age" -> "a.birth_year";
            case "apmsDesertionNo", "apms_desertion_no" -> "a.apms_desertion_no";
            case "favoriteCount", "favorite_count" -> "a.favorite_count";
            default -> throw new IllegalArgumentException("지원하지 않는 정렬 기준입니다: " + property);
        };
        boolean ascending = order.ascending();
        if (property.equals("age")) ascending = !ascending;
        return column + (ascending ? " ASC" : " DESC") + " NULLS LAST,a.id ASC";
    }

    static Summary summary(ResultSet row, int index) throws SQLException {
        Integer year = row.getObject("birth_year", Integer.class);
        return new Summary(row.getLong("id"), row.getString("apms_notice_no"), Species.valueOf(row.getString("species")),
                row.getString("breed"), Gender.valueOf(row.getString("gender")), year, age(year),
                row.getString("special_mark"), AnimalStatus.valueOf(row.getString("status")),
                row.getObject("notice_end_date", LocalDate.class), row.getString("image_url"),
                row.getObject("favorite_count", Integer.class), row.getLong("shelter_id"), row.getString("shelter_name"),
                row.getObject("created_at", LocalDateTime.class));
    }

    static Details detail(ResultSet row, int index) throws SQLException {
        Integer year = row.getObject("birth_year", Integer.class);
        return new Details(row.getLong("id"), row.getString("apms_notice_no"), Species.valueOf(row.getString("species")),
                row.getString("breed"), Gender.valueOf(row.getString("gender")), NeuterStatus.valueOf(row.getString("neuter_status")),
                year, age(year), row.getString("weight"), row.getString("color"), row.getString("special_mark"),
                AnimalStatus.valueOf(row.getString("status")), row.getObject("notice_start_date", LocalDate.class),
                row.getObject("notice_end_date", LocalDate.class), row.getString("happen_place"),
                row.getObject("happen_date", LocalDate.class), row.getString("image_url"), row.getString("image_url2"),
                row.getString("description"), row.getObject("favorite_count", Integer.class), row.getLong("shelter_id"),
                row.getString("shelter_name"), row.getString("apms_desertion_no"), row.getString("apms_process_state"),
                row.getObject("apms_updated_at", LocalDateTime.class), ApiSource.valueOf(row.getString("api_source")),
                row.getObject("created_at", LocalDateTime.class), row.getObject("updated_at", LocalDateTime.class));
    }
    private static Integer age(Integer year) { return year == null ? null : LocalDate.now().getYear() - year; }

    private static final class Conditions {
        private String from = FROM;
        private final List<String> where = new ArrayList<>();
        private final List<String> scores = new ArrayList<>();
        private final MapSqlParameterSource parameters = new MapSqlParameterSource();
        private void equal(String column, String key, Object value) {
            if (value == null) return;
            where.add(column + "=:" + key);
            parameters.addValue(key, value instanceof Enum<?> enumeration ? enumeration.name() : value);
        }
    }
}
