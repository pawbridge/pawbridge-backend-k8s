package com.pawbridge.animalservice.travel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** DB-only catalog. The collector performs HTTP before entering these transactions. */
@Repository
public class PetTravelCatalog {
    private static final String PROVIDER = "KOREA_TOURISM_ORGANIZATION";
    private static final TypeReference<Map<String, String>> FIELDS = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;

    public PetTravelCatalog(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(manager);
    }

    public record Region(String code, String name, Instant fetchedAt, Instant completedAt) {}
    public record Target(String contentId, String areaCode, String modifiedTime, boolean shown,
                         String imageUrl, String copyrightType, long generation, boolean pending) {}
    public record Place(Map<String, String> common, Map<String, String> pet, Instant publishedAt,
                        Instant basicFetchedAt, String detailStatus) {
        public Place(Map<String,String> common, Map<String,String> pet, Instant publishedAt) {
            this(common,pet,publishedAt,publishedAt,publishedAt == null ? "PREPARING" : "READY");
        }
    }
    public record CollectionState(String phase, int nextPage, String errorCode, Instant completedAt) {}

    public CollectionState collectionState() {
        return jdbc.queryForObject("SELECT * FROM pet_travel_collection_state WHERE id=1",
                (r, i) -> new CollectionState(r.getString("phase"), r.getInt("next_page"),
                        r.getString("error_code"), instant(r, "completed_at")));
    }

    public void checkpoint(String phase, int page) {
        jdbc.update("UPDATE pet_travel_collection_state SET phase=?,next_page=? WHERE id=1", phase, page);
    }

    public boolean reserveRequest(String operation, java.time.LocalDate day, int limit) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            jdbc.update("INSERT INTO pet_travel_request_budgets(request_day,operation,used) VALUES (?,?,0) "
                    + "ON DUPLICATE KEY UPDATE used=used", java.sql.Date.valueOf(day), operation);
            return jdbc.update("UPDATE pet_travel_request_budgets SET used=used+1 "
                    + "WHERE request_day=? AND operation=? AND used<?", java.sql.Date.valueOf(day), operation, limit) == 1;
        }));
    }

    public void startRun(String id, Instant now) {
        Integer running = jdbc.queryForObject("SELECT COUNT(*) FROM pet_travel_collection_runs WHERE status='RUNNING'", Integer.class);
        if (running != null && running > 0) throw new IllegalStateException("TRAVEL_ORPHAN_RUN");
        jdbc.update("INSERT INTO pet_travel_collection_runs(id,started_at,status) VALUES (?,?,'RUNNING')", id, Timestamp.from(now));
    }

    public void finishRun(String id, String state, String error, int requests, int discovered, int published, Instant now) {
        transaction.executeWithoutResult(status -> {
            jdbc.update("UPDATE pet_travel_collection_runs SET finished_at=?,status=?,error_code=?,requests=?,discovered=?,published=? WHERE id=?",
                    Timestamp.from(now), state, error, requests, discovered, published, id);
            jdbc.update("UPDATE pet_travel_collection_state SET error_code=? WHERE id=1", error);
        });
    }

    public void completeCycle(Instant now) {
        transaction.executeWithoutResult(status -> {
            for (var region : regions()) completeRegion(region.code(), now);
            jdbc.update("UPDATE pet_travel_collection_state SET phase='HIDDEN',next_page=1,completed_at=?,error_code=NULL WHERE id=1",
                    Timestamp.from(now));
        });
    }

    /** Start a refresh wave only after existing work drains; discovery never waits for it. */
    public void beginDetails(Instant cutoff) {
        transaction.executeWithoutResult(status -> {
            // Failed rows stay retryable, but must not hold every other place's refresh indefinitely.
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pet_travel_targets "
                    + "WHERE provider=? AND shown=TRUE AND pending=TRUE AND area_code IS NOT NULL AND detail_error IS NULL)",
                    Boolean.class,PROVIDER))) return;
            jdbc.update("UPDATE pet_travel_targets t JOIN pet_travel_places p ON p.provider=t.provider AND p.content_id=t.content_id "
                    + "SET t.pending=TRUE WHERE t.shown=TRUE AND p.published_at<?", Timestamp.from(cutoff));
        });
    }

    public List<Region> regions() {
        return jdbc.query("SELECT * FROM pet_travel_regions ORDER BY CAST(code AS UNSIGNED)",
                (row, index) -> new Region(row.getString("code"), row.getString("name"),
                        instant(row, "fetched_at"), instant(row, "completed_at")));
    }

    public List<Place> places(String areaCode) {
        return jdbc.query(publicQuery() + " AND t.area_code=? ORDER BY "
                + "COALESCE(JSON_UNQUOTE(JSON_EXTRACT(t.basic_data,'$.title')),p.title),t.content_id LIMIT 10",
                this::place, PROVIDER, areaCode);
    }

    public Optional<Place> detail(String contentId) {
        return jdbc.query(publicQuery() + " AND t.content_id=?",
                this::place, PROVIDER, contentId).stream().findFirst();
    }

    private String publicQuery() {
        return "SELECT t.basic_data,t.basic_fetched_at,t.image_url,t.copyright_type,t.pending,t.detail_error,"
                + "p.common_data,p.pet_data,p.published_at FROM pet_travel_targets t "
                + "LEFT JOIN pet_travel_places p ON p.provider=t.provider AND p.content_id=t.content_id AND p.visible=TRUE "
                + "WHERE t.provider=? AND t.shown=TRUE AND t.area_code IS NOT NULL "
                + "AND (t.basic_data IS NOT NULL OR p.content_id IS NOT NULL)";
    }

    /** Store discovery and basic public data atomically, without calling detail endpoints. */
    public void observeBasic(Map<String,String> row, String region, Instant now) {
        boolean shown="1".equals(row.get("showflag"));
        if (shown && row.getOrDefault("title", "").isBlank()) throw new IllegalArgumentException("Missing place title");
        transaction.executeWithoutResult(status -> {
            observe(row.get("contentid"),region,row.get("modifiedtime"),shown,
                    row.get("firstimage"),row.get("cpyrhtDivCd"),now,false);
            var current=lockedTarget(row.get("contentid"));
            if (!current.modifiedTime().equals(row.get("modifiedtime"))) return;
            jdbc.update("UPDATE pet_travel_targets SET basic_data=?,basic_fetched_at=? WHERE provider=? AND content_id=?",
                    json(row),Timestamp.from(now),PROVIDER,current.contentId());
        });
    }

    public void detailFailed(Target target, Instant now) {
        jdbc.update("UPDATE pet_travel_targets SET detail_error='DETAIL_FAILED',detail_attempted_at=? "
                + "WHERE provider=? AND content_id=? AND generation=? AND shown=TRUE",
                Timestamp.from(now),PROVIDER,target.contentId(),target.generation());
    }

    public void saveRegions(Map<String, String> regions, Instant fetchedAt) {
        regions.forEach((code, name) -> {
            if (!code.matches("[0-9]{2,5}") || name.isBlank()) throw new IllegalArgumentException("Invalid region");
        });
        transaction.executeWithoutResult(status -> regions.forEach((code, name) -> jdbc.update(
                "INSERT INTO pet_travel_regions(code,name,fetched_at) VALUES (?,?,?) "
                        + "ON DUPLICATE KEY UPDATE name=VALUES(name), fetched_at=VALUES(fetched_at)",
                code, name, Timestamp.from(fetchedAt))));
    }

    /** Explicit discovery updates visibility/photo rights even when subsequent detail fetching fails. */
    public void observe(String contentId, String areaCode, String modifiedTime, boolean shown,
                        String imageUrl, String copyrightType, Instant observedAt, boolean refreshDetail) {
        if (contentId == null || !contentId.matches("[0-9]{1,20}")
                || (areaCode != null && !areaCode.matches("[0-9]{2,5}")) || modifiedTime == null || !modifiedTime.matches("[0-9]{14}")) {
            throw new IllegalArgumentException("Invalid discovery metadata");
        }
        transaction.executeWithoutResult(status -> {
            // ON DUPLICATE KEY obtains the same row lock as the following SELECT FOR UPDATE.
            jdbc.update("INSERT INTO pet_travel_targets(provider,content_id,area_code,modified_time,shown,"
                    + "image_url,copyright_type,generation,pending,observed_at) VALUES (?,?,?,?,?,?,?,0,TRUE,?) "
                    + "ON DUPLICATE KEY UPDATE content_id=content_id", PROVIDER, contentId, areaCode, modifiedTime,
                    shown, imageUrl, copyrightType, Timestamp.from(observedAt));
            var previous = lockedTarget(contentId);
            if (modifiedTime.compareTo(previous.modifiedTime()) < 0) return;
            boolean pending = shown && (previous.pending() || refreshDetail || !previous.shown()
                    || !modifiedTime.equals(previous.modifiedTime()) || !java.util.Objects.equals(areaCode, previous.areaCode()));
            jdbc.update("UPDATE pet_travel_targets SET area_code=?,modified_time=?,shown=?,image_url=?,"
                    + "copyright_type=?,generation=generation+1,pending=?,observed_at=?,detail_error=IF(?,NULL,detail_error) WHERE provider=? AND content_id=?",
                    areaCode, modifiedTime, shown, imageUrl, copyrightType, pending, Timestamp.from(observedAt),
                    shown && !previous.shown(), PROVIDER, contentId);
            // Reappearance cannot republish stale hidden details; publish() alone makes them visible.
            jdbc.update("UPDATE pet_travel_places SET visible=IF(?,visible,FALSE), image_url=?,copyright_type=? "
                    + "WHERE provider=? AND content_id=?", shown, imageUrl, copyrightType, PROVIDER, contentId);
        });
    }

    public List<Target> pending(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Target limit must be 1..100");
        return jdbc.query("SELECT * FROM pet_travel_targets WHERE provider=? AND pending=TRUE AND shown=TRUE AND area_code IS NOT NULL "
                + "ORDER BY detail_attempted_at,observed_at,content_id LIMIT ?", this::target, PROVIDER, limit);
    }

    /** Returns false if discovery changed while HTTP was in flight. An empty pet map is valid. */
    public boolean publish(Target expected, Map<String, String> common, Map<String, String> pet, Instant fetchedAt) {
        if (!expected.contentId().equals(common.get("contentid")) || common.getOrDefault("title", "").isBlank()
                || (!pet.isEmpty() && !expected.contentId().equals(pet.get("contentid")))) {
            throw new IllegalArgumentException("Mismatched or incomplete detail");
        }
        String commonJson = json(common);
        String petJson = json(pet);
        return Boolean.TRUE.equals(transaction.execute(status -> {
            var current = lockedTarget(expected.contentId());
            if (!current.shown() || !current.pending() || current.areaCode() == null || current.generation() != expected.generation()) return false;
            jdbc.update("INSERT INTO pet_travel_places(provider,content_id,area_code,title,common_data,pet_data,"
                    + "image_url,copyright_type,visible,published_at) VALUES (?,?,?,?,?,?,?, ?,TRUE,?) "
                    + "ON DUPLICATE KEY UPDATE area_code=VALUES(area_code),title=VALUES(title),common_data=VALUES(common_data),"
                    + "pet_data=VALUES(pet_data),image_url=VALUES(image_url),copyright_type=VALUES(copyright_type),"
                    + "visible=TRUE,published_at=VALUES(published_at)", PROVIDER, current.contentId(), current.areaCode(),
                    common.get("title"), commonJson, petJson, current.imageUrl(), current.copyrightType(), Timestamp.from(fetchedAt));
            jdbc.update("UPDATE pet_travel_targets SET pending=FALSE,detail_error=NULL,detail_attempted_at=? WHERE provider=? AND content_id=?",
                    Timestamp.from(fetchedAt),PROVIDER,current.contentId());
            return true;
        }));
    }

    public boolean hasUnresolvedRegions() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM pet_travel_targets WHERE provider=? AND shown=TRUE AND area_code IS NULL)",
                Boolean.class, PROVIDER));
    }

    /** Called only after a complete region inventory pass, never after a partial/failed scan. */
    public boolean completeRegion(String areaCode, Instant completedAt) {
        return jdbc.update("UPDATE pet_travel_regions SET completed_at=? WHERE code=?",
                Timestamp.from(completedAt), areaCode) == 1;
    }

    private Target lockedTarget(String contentId) {
        return jdbc.queryForObject("SELECT * FROM pet_travel_targets WHERE provider=? AND content_id=? FOR UPDATE",
                this::target, PROVIDER, contentId);
    }

    private Target target(ResultSet row, int index) throws SQLException {
        return new Target(row.getString("content_id"), row.getString("area_code"), row.getString("modified_time"),
                row.getBoolean("shown"), row.getString("image_url"), row.getString("copyright_type"),
                row.getLong("generation"), row.getBoolean("pending"));
    }

    private Place place(ResultSet row, int index) throws SQLException {
        var common = new LinkedHashMap<String,String>();
        if (row.getString("common_data") != null) common.putAll(fields(row.getString("common_data")));
        if (row.getString("basic_data") != null) common.putAll(fields(row.getString("basic_data")));
        // The most recent discovery owns photo rights, not the last successful detail snapshot.
        common.remove("firstimage");
        common.remove("cpyrhtDivCd");
        if (row.getString("image_url") != null) common.put("firstimage", row.getString("image_url"));
        if (row.getString("copyright_type") != null) common.put("cpyrhtDivCd", row.getString("copyright_type"));
        var published=instant(row,"published_at");
        String detailStatus=row.getString("detail_error") != null ? (published == null ? "FAILED" : "STALE")
                : published == null ? "PREPARING" : row.getBoolean("pending") ? "STALE" : "READY";
        return new Place(Map.copyOf(common),row.getString("pet_data") == null ? Map.of() : fields(row.getString("pet_data")),
                published,instant(row,"basic_fetched_at"),detailStatus);
    }

    private String json(Map<String, String> fields) {
        try { return mapper.writeValueAsString(fields); }
        catch (Exception exception) { throw new IllegalArgumentException("Invalid travel fields"); }
    }

    private Map<String, String> fields(String json) {
        try { return Map.copyOf(mapper.readValue(json, FIELDS)); }
        catch (Exception exception) { throw new IllegalStateException("Invalid stored travel fields"); }
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        var timestamp = row.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
