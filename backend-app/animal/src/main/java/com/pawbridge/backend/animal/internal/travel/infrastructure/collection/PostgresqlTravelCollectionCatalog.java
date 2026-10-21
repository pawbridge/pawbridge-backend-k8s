package com.pawbridge.backend.animal.internal.travel.infrastructure.collection;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Original PostgreSQL collection writes/checkpoints only; HTTP stays outside these transactions. */
public class PostgresqlTravelCollectionCatalog {
    private static final String PROVIDER = "KOREA_TOURISM_ORGANIZATION";
    private static final String DISCOVERY_CONTENT_TYPES = "('12','14','28')";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;

    public PostgresqlTravelCollectionCatalog(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(manager);
    }

    public record Region(String code, String name, Instant fetchedAt, Instant completedAt) {}
    public record Target(String contentId, String areaCode, String modifiedTime, boolean shown,
                         String imageUrl, String copyrightType, long generation, boolean pending) {}
    public enum VisitResource {
        INTRO("intro_source_modified_time", "intro_attempted_at", "intro_error"),
        INFO("info_source_modified_time", "info_attempted_at", "info_error"),
        IMAGES("image_source_modified_time", "image_attempted_at", "image_error");
        final String revisionColumn;
        final String attemptedColumn;
        final String errorColumn;
        VisitResource(String revisionColumn, String attemptedColumn, String errorColumn) {
            this.revisionColumn=revisionColumn;this.attemptedColumn=attemptedColumn;this.errorColumn=errorColumn;
        }
    }
    public record VisitTarget(String contentId, String contentTypeId, String modifiedTime, long generation) {}
    public record CollectionState(String phase, int nextPage, String errorCode, Instant completedAt) {}
    public record PetCollectionState(int nextPage, Integer expectedTotal, String cycleId, Instant completedAt) {}

    public PetCollectionState petCollectionState() {
        return jdbc.queryForObject("SELECT * FROM pawbridge_animal.pet_travel_pet_collection_state WHERE id=1",
                (r,i) -> new PetCollectionState(r.getInt("next_page"),(Integer)r.getObject("expected_total"),
                        r.getString("cycle_id"),instant(r,"completed_at")));
    }

    /** Page data and its checkpoint commit together. No inferred deletion for absent IDs. */
    public void savePetPage(PetCollectionState expected, TourApiClient.Page page, Instant fetchedAt, Instant validUntil) {
        long remaining=(long)page.totalCount()-(long)(expected.nextPage()-1)*100;
        if (page.totalCount()<0 || page.items().size()!=Math.min(100,Math.max(0,remaining))
                || (expected.nextPage()>1 && remaining<=0)
                || (expected.expectedTotal()!=null && expected.expectedTotal()!=page.totalCount())
                || page.items().stream().map(row->row.get("contentid")).distinct().count()!=page.items().size())
            throw new IllegalStateException("PET_INVENTORY_CHANGED");
        transaction.executeWithoutResult(status -> {
            var current=jdbc.queryForObject("SELECT cycle_id FROM pawbridge_animal.pet_travel_pet_collection_state WHERE id=1 FOR UPDATE",String.class);
            var state=petCollectionState();
            if (!expected.cycleId().equals(current) || expected.nextPage()!=state.nextPage())
                throw new IllegalStateException("PET_CHECKPOINT_CHANGED");
            for (var row:page.items()) {
                String contentId=row.get("contentid");
                if (contentId==null || !contentId.matches("[0-9]{1,20}")) throw new IllegalArgumentException("Invalid pet identity");
                if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pawbridge_animal.pet_travel_pet_details "
                        + "WHERE provider=? AND content_id=? AND cycle_id=?)",Boolean.class,PROVIDER,contentId,expected.cycleId())))
                    throw new IllegalStateException("PET_INVENTORY_CHANGED");
                jdbc.update("INSERT INTO pawbridge_animal.pet_travel_pet_details(provider,content_id,pet_data,source,fetched_at,valid_until,cycle_id) "
                        + "VALUES (?,?," + "CAST(? AS jsonb)" + ",'BULK',?,?,?) " + "ON CONFLICT (provider,content_id) DO UPDATE SET pet_data=EXCLUDED.pet_data,source='BULK',"
                        + "fetched_at=EXCLUDED.fetched_at,valid_until=EXCLUDED.valid_until,cycle_id=EXCLUDED.cycle_id",
                        PROVIDER,contentId,json(row),Timestamp.from(fetchedAt),Timestamp.from(validUntil),expected.cycleId());
            }
            boolean complete=(long)expected.nextPage()*100>=page.totalCount();
            jdbc.update("UPDATE pawbridge_animal.pet_travel_pet_collection_state SET next_page=?,expected_total=?,cycle_id=?,"
                    + "completed_at=CASE WHEN ? THEN ? ELSE completed_at END,error_code=NULL WHERE id=1",
                    complete?1:expected.nextPage()+1,complete?null:page.totalCount(),
                    complete?java.util.UUID.randomUUID().toString():expected.cycleId(),complete,Timestamp.from(fetchedAt));
        });
    }

    public void petCollectionFailed(String code, boolean restart) {
        if (restart) jdbc.update("UPDATE pawbridge_animal.pet_travel_pet_collection_state SET next_page=1,expected_total=NULL,cycle_id=?,error_code=? WHERE id=1",
                java.util.UUID.randomUUID().toString(),code);
        else jdbc.update("UPDATE pawbridge_animal.pet_travel_pet_collection_state SET error_code=? WHERE id=1",code);
    }

    public CollectionState collectionState() {
        return jdbc.queryForObject("SELECT * FROM pawbridge_animal.pet_travel_collection_state WHERE id=1",
                (r, i) -> new CollectionState(r.getString("phase"), r.getInt("next_page"),
                        r.getString("error_code"), instant(r, "completed_at")));
    }

    public void checkpoint(String phase, int page) {
        jdbc.update("UPDATE pawbridge_animal.pet_travel_collection_state SET phase=?,next_page=? WHERE id=1", phase, page);
    }

    public boolean reserveRequest(String operation, java.time.LocalDate day, int limit) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            jdbc.update("INSERT INTO pawbridge_animal.pet_travel_request_budgets(request_day,operation,used) VALUES (?,?,0) "
                    + "ON CONFLICT (request_day,operation) DO NOTHING", java.sql.Date.valueOf(day), operation);
            return jdbc.update("UPDATE pawbridge_animal.pet_travel_request_budgets SET used=used+1 "
                    + "WHERE request_day=? AND operation=? AND used<?", java.sql.Date.valueOf(day), operation, limit) == 1;
        }));
    }

    public void startRun(String id, Instant now) {
        Integer running = jdbc.queryForObject("SELECT COUNT(*) FROM pawbridge_animal.pet_travel_collection_runs WHERE status='RUNNING'", Integer.class);
        if (running != null && running > 0) throw new IllegalStateException("TRAVEL_ORPHAN_RUN");
        jdbc.update("INSERT INTO pawbridge_animal.pet_travel_collection_runs(id,started_at,status) VALUES (?,?,'RUNNING')", id, Timestamp.from(now));
    }

    public void finishRun(String id, String state, String error, int requests, int discovered, int published, Instant now) {
        transaction.executeWithoutResult(status -> {
            jdbc.update("UPDATE pawbridge_animal.pet_travel_collection_runs SET finished_at=?,status=?,error_code=?,requests=?,discovered=?,published=? WHERE id=?",
                    Timestamp.from(now), state, error, requests, discovered, published, id);
            jdbc.update("UPDATE pawbridge_animal.pet_travel_collection_state SET error_code=? WHERE id=1", error);
        });
    }

    public void completeCycle(Instant now) {
        transaction.executeWithoutResult(status -> {
            for (var region : regions()) completeRegion(region.code(), now);
            jdbc.update("UPDATE pawbridge_animal.pet_travel_collection_state SET phase='HIDDEN',next_page=1,completed_at=?,error_code=NULL WHERE id=1",
                    Timestamp.from(now));
        });
    }

    /** Start a refresh wave only after existing work drains; discovery never waits for it. */
    public void beginDetails(Instant cutoff) {
        transaction.executeWithoutResult(status -> {
            // Failed rows stay retryable, but must not hold every other place's refresh indefinitely.
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pawbridge_animal.pet_travel_targets "
                    + "WHERE provider=? AND shown=TRUE AND pending=TRUE AND area_code IS NOT NULL AND detail_error IS NULL "
                    + "AND (" + "(basic_data->'contenttypeid')" + " IS NULL OR "
                    + "(basic_data->>'contenttypeid')" + " IN " + DISCOVERY_CONTENT_TYPES + "))",
                    Boolean.class,PROVIDER))) return;
            jdbc.update("UPDATE pawbridge_animal.pet_travel_targets t SET pending=TRUE FROM pawbridge_animal.pet_travel_places p "
                    + "WHERE p.provider=t.provider AND p.content_id=t.content_id AND t.shown=TRUE AND p.published_at<? "
                    + "AND (COALESCE(" + "(t.basic_data->>'contenttypeid')" + ","
                    + "(p.common_data->>'contenttypeid')" + ") IS NULL OR "
                    + "COALESCE(" + "(t.basic_data->>'contenttypeid')" + ","
                    + "(p.common_data->>'contenttypeid')" + ") IN " + DISCOVERY_CONTENT_TYPES + ")",
                    Timestamp.from(cutoff));
        });
    }

    public List<Region> regions() {
        return jdbc.query("SELECT * FROM pawbridge_animal.pet_travel_regions ORDER BY " + "CAST(code AS INTEGER)",
                (row, index) -> new Region(row.getString("code"), row.getString("name"),
                        instant(row, "fetched_at"), instant(row, "completed_at")));
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
            jdbc.update("UPDATE pawbridge_animal.pet_travel_targets SET basic_data=" + "CAST(? AS jsonb)" + ",basic_fetched_at=? WHERE provider=? AND content_id=?",
                    json(row),Timestamp.from(now),PROVIDER,current.contentId());
        });
    }

    public void detailFailed(Target target, Instant now) {
        jdbc.update("UPDATE pawbridge_animal.pet_travel_targets SET detail_error='DETAIL_FAILED',detail_attempted_at=? "
                + "WHERE provider=? AND content_id=? AND generation=? AND shown=TRUE",
                Timestamp.from(now),PROVIDER,target.contentId(),target.generation());
    }

    public void saveRegions(Map<String, String> regions, Instant fetchedAt) {
        regions.forEach((code, name) -> {
            if (!code.matches("[0-9]{2,5}") || name.isBlank()) throw new IllegalArgumentException("Invalid region");
        });
        transaction.executeWithoutResult(status -> regions.forEach((code, name) -> jdbc.update(
                "INSERT INTO pawbridge_animal.pet_travel_regions(code,name,fetched_at) VALUES (?,?,?) "
                        + "ON CONFLICT (code) DO UPDATE SET name=EXCLUDED.name,fetched_at=EXCLUDED.fetched_at",
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
            // Insert (or wait for a concurrent insert), then lock the existing target before comparing revisions.
            jdbc.update("INSERT INTO pawbridge_animal.pet_travel_targets(provider,content_id,area_code,modified_time,shown,"
                    + "image_url,copyright_type,generation,pending,observed_at) VALUES (?,?,?,?,?,?,?,0,TRUE,?) "
                    + "ON CONFLICT (provider,content_id) DO NOTHING", PROVIDER, contentId, areaCode, modifiedTime,
                    shown, imageUrl, copyrightType, Timestamp.from(observedAt));
            var previous = lockedTarget(contentId);
            if (modifiedTime.compareTo(previous.modifiedTime()) < 0) return;
            boolean pending = shown && (previous.pending() || refreshDetail || !previous.shown()
                    || !modifiedTime.equals(previous.modifiedTime()) || !java.util.Objects.equals(areaCode, previous.areaCode()));
            if (shown!=previous.shown())
                jdbc.update("UPDATE pawbridge_animal.pet_travel_targets SET pet_valid_after=? WHERE provider=? AND content_id=?",
                        Timestamp.from(observedAt),PROVIDER,contentId);
            if (!modifiedTime.equals(previous.modifiedTime()))
                jdbc.update("UPDATE pawbridge_animal.pet_travel_targets SET pet_changed_at=? WHERE provider=? AND content_id=?",
                        Timestamp.from(observedAt),PROVIDER,contentId);
            jdbc.update("UPDATE pawbridge_animal.pet_travel_targets SET area_code=?,modified_time=?,shown=?,image_url=?,"
                    + "copyright_type=?,generation=generation+1,pending=?,observed_at=?,detail_error=CASE WHEN ? THEN NULL ELSE detail_error END WHERE provider=? AND content_id=?",
                    areaCode, modifiedTime, shown, imageUrl, copyrightType, pending, Timestamp.from(observedAt),
                    shown && !previous.shown(), PROVIDER, contentId);
            // Reappearance cannot republish stale hidden details; publish() alone makes them visible.
            jdbc.update("UPDATE pawbridge_animal.pet_travel_places SET visible=CASE WHEN ? THEN visible ELSE FALSE END, image_url=?,copyright_type=? "
                    + "WHERE provider=? AND content_id=?", shown, imageUrl, copyrightType, PROVIDER, contentId);
        });
    }

    public List<Target> pending(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Target limit must be 1..100");
        return jdbc.query("SELECT * FROM pawbridge_animal.pet_travel_targets WHERE provider=? AND pending=TRUE AND shown=TRUE AND area_code IS NOT NULL "
                + "AND (" + "(basic_data->'contenttypeid')" + " IS NULL OR "
                + "(basic_data->>'contenttypeid')" + " IN " + DISCOVERY_CONTENT_TYPES + ") "
                + "ORDER BY (detail_attempted_at IS NOT NULL),detail_attempted_at,observed_at,content_id LIMIT ?",
                this::target, PROVIDER, limit);
    }

    public List<VisitTarget> pendingVisitDetails(VisitResource resource, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Visit detail limit must be 1..100");
        String sql="SELECT t.content_id," + "(t.basic_data->>'contenttypeid')" + " content_type_id,"
                + "t.modified_time,t.generation FROM pawbridge_animal.pet_travel_targets t "
                + "LEFT JOIN pawbridge_animal.pet_travel_visit_details v ON v.provider=t.provider AND v.content_id=t.content_id "
                + "WHERE t.provider=? AND t.shown=TRUE AND t.area_code IS NOT NULL "
                + "AND " + "(t.basic_data->>'contenttypeid')" + " IN " + DISCOVERY_CONTENT_TYPES + " "
                + "AND (v." + resource.revisionColumn + " IS NULL OR v." + resource.revisionColumn + "<>t.modified_time) "
                + "ORDER BY (v." + resource.attemptedColumn + " IS NOT NULL),v." + resource.attemptedColumn + ",t.observed_at,t.content_id LIMIT ?";
        return jdbc.query(sql,(row,index)->new VisitTarget(row.getString("content_id"),row.getString("content_type_id"),
                row.getString("modified_time"),row.getLong("generation")),PROVIDER,limit);
    }

    public boolean hasPendingVisitDetails() {
        for (var resource : VisitResource.values()) if (!pendingVisitDetails(resource,1).isEmpty()) return true;
        return false;
    }

    public boolean saveVisitIntro(VisitTarget expected, List<Map<String,String>> rows, Instant fetchedAt) {
        if (rows.size()>1) throw new IllegalArgumentException("Invalid intro response");
        validateVisitRows(expected,rows,true);
        return saveVisitResource(expected,VisitResource.INTRO,json(rows.isEmpty()?Map.of():rows.get(0)),fetchedAt);
    }

    public boolean saveVisitInformation(VisitTarget expected, List<Map<String,String>> rows, Instant fetchedAt) {
        validateVisitRows(expected,rows,true);
        return saveVisitResource(expected,VisitResource.INFO,jsonList(rows),fetchedAt);
    }

    public boolean saveVisitImages(VisitTarget expected, List<Map<String,String>> rows, Instant fetchedAt) {
        validateVisitRows(expected,rows,false);
        if (rows.stream().map(row->row.get("serialnum")).distinct().count()!=rows.size())
            throw new IllegalArgumentException("Duplicate image identity");
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (!matchesLockedTarget(expected)) return false;
            ensureVisitDetails(expected);
            jdbc.update("DELETE FROM pawbridge_animal.pet_travel_images WHERE provider=? AND content_id=?",PROVIDER,expected.contentId());
            int order=0;
            for (var row : rows) {
                String copyright=row.get("cpyrhtDivCd");
                String original=row.get("originimgurl");
                if (!("Type1".equals(copyright) || "Type3".equals(copyright)) || original==null || original.isBlank()) continue;
                jdbc.update("INSERT INTO pawbridge_animal.pet_travel_images(provider,content_id,serial_number,image_name,original_url,"
                                + "thumbnail_url,copyright_type,display_order,fetched_at) VALUES (?,?,?,?,?,?,?,?,?)",
                        PROVIDER,expected.contentId(),row.get("serialnum"),row.get("imgname"),original,
                        row.get("smallimageurl"),copyright,order++,Timestamp.from(fetchedAt));
            }
            jdbc.update("UPDATE pawbridge_animal.pet_travel_visit_details SET image_source_modified_time=?,image_fetched_at=?,"
                            + "image_attempted_at=?,image_error=NULL WHERE provider=? AND content_id=?",
                    expected.modifiedTime(),Timestamp.from(fetchedAt),Timestamp.from(fetchedAt),PROVIDER,expected.contentId());
            return true;
        }));
    }

    public void visitDetailFailed(VisitResource resource, VisitTarget target, Instant attemptedAt) {
        transaction.executeWithoutResult(status -> {
            if (!matchesLockedTarget(target)) return;
            ensureVisitDetails(target);
            jdbc.update("UPDATE pawbridge_animal.pet_travel_visit_details SET " + resource.attemptedColumn + "=?,"
                            + resource.errorColumn + "='DETAIL_FAILED' WHERE provider=? AND content_id=?",
                    Timestamp.from(attemptedAt),PROVIDER,target.contentId());
        });
    }

    private boolean saveVisitResource(VisitTarget expected, VisitResource resource, String json, Instant fetchedAt) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (!matchesLockedTarget(expected)) return false;
            ensureVisitDetails(expected);
            String dataColumn=resource==VisitResource.INTRO?"intro_data":"info_data";
            String fetchedColumn=resource==VisitResource.INTRO?"intro_fetched_at":"info_fetched_at";
            jdbc.update("UPDATE pawbridge_animal.pet_travel_visit_details SET " + dataColumn + "=" + "CAST(? AS jsonb)" + "," + resource.revisionColumn + "=?,"
                            + fetchedColumn + "=?," + resource.attemptedColumn + "=?," + resource.errorColumn
                            + "=NULL WHERE provider=? AND content_id=?",json,expected.modifiedTime(),Timestamp.from(fetchedAt),
                    Timestamp.from(fetchedAt),PROVIDER,expected.contentId());
            return true;
        }));
    }

    private void validateVisitRows(VisitTarget expected, List<Map<String,String>> rows, boolean requireContentType) {
        for (var row : rows) if (!expected.contentId().equals(row.get("contentid"))
                || (requireContentType && !expected.contentTypeId().equals(row.get("contenttypeid"))))
            throw new IllegalArgumentException("Mismatched visit detail");
    }

    private boolean matchesLockedTarget(VisitTarget expected) {
        var current=lockedTarget(expected.contentId());
        return current.shown() && current.generation()==expected.generation()
                && current.modifiedTime().equals(expected.modifiedTime());
    }

    private void ensureVisitDetails(VisitTarget target) {
        jdbc.update("INSERT INTO pawbridge_animal.pet_travel_visit_details(provider,content_id,content_type_id) VALUES (?,?,?) "
                        + "ON CONFLICT (provider,content_id) DO UPDATE SET content_type_id=EXCLUDED.content_type_id",
                PROVIDER,target.contentId(),target.contentTypeId());
    }

    /** Returns false if discovery changed while HTTP was in flight. An empty pet map is valid. */
    public boolean publish(Target expected, Map<String, String> common, Map<String, String> pet, Instant fetchedAt) {
        return publish(expected,common,pet,fetchedAt,true);
    }

    public boolean publishCommon(Target expected, Map<String,String> common, Instant fetchedAt) {
        return publish(expected,common,Map.of(),fetchedAt,false);
    }

    private boolean publish(Target expected, Map<String,String> common, Map<String,String> pet, Instant fetchedAt, boolean includesPet) {
        if (!expected.contentId().equals(common.get("contentid")) || common.getOrDefault("title", "").isBlank()
                || (!pet.isEmpty() && !expected.contentId().equals(pet.get("contentid")))) {
            throw new IllegalArgumentException("Mismatched or incomplete detail");
        }
        String commonJson = json(common);
        String petJson = json(pet);
        return Boolean.TRUE.equals(transaction.execute(status -> {
            var current = lockedTarget(expected.contentId());
            if (!current.shown() || !current.pending() || current.areaCode() == null || current.generation() != expected.generation()) return false;
            jdbc.update("INSERT INTO pawbridge_animal.pet_travel_places(provider,content_id,area_code,title,common_data,pet_data,"
                    + "image_url,copyright_type,visible,published_at) VALUES (?,?,?,?," + "CAST(? AS jsonb)" + ","
                    + "CAST(? AS jsonb)" + ",?,?,TRUE,?) " + "ON CONFLICT (provider,content_id) DO UPDATE SET area_code=EXCLUDED.area_code,title=EXCLUDED.title,"
                    + "common_data=EXCLUDED.common_data,pet_data=CASE WHEN ? THEN EXCLUDED.pet_data ELSE pet_travel_places.pet_data END,"
                    + "image_url=EXCLUDED.image_url,copyright_type=EXCLUDED.copyright_type,visible=TRUE,published_at=EXCLUDED.published_at", PROVIDER, current.contentId(), current.areaCode(),
                    common.get("title"), commonJson, petJson, current.imageUrl(), current.copyrightType(), Timestamp.from(fetchedAt),includesPet);
            if (includesPet)
                jdbc.update("INSERT INTO pawbridge_animal.pet_travel_pet_details(provider,content_id,pet_data,source,fetched_at) VALUES (?,?," + "CAST(? AS jsonb)" + ",'LEGACY',?) "
                        + "ON CONFLICT (provider,content_id) DO UPDATE SET pet_data=EXCLUDED.pet_data,source='LEGACY',"
                        + "fetched_at=EXCLUDED.fetched_at,valid_until=NULL,cycle_id=NULL",
                        PROVIDER,current.contentId(),petJson,Timestamp.from(fetchedAt));
            jdbc.update("UPDATE pawbridge_animal.pet_travel_targets SET pending=FALSE,detail_error=NULL,detail_attempted_at=? WHERE provider=? AND content_id=?",
                    Timestamp.from(fetchedAt),PROVIDER,current.contentId());
            return true;
        }));
    }

    public boolean hasUnresolvedRegions() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM pawbridge_animal.pet_travel_targets WHERE provider=? AND shown=TRUE AND area_code IS NULL)",
                Boolean.class, PROVIDER));
    }

    /** Called only after a complete region inventory pass, never after a partial/failed scan. */
    public boolean completeRegion(String areaCode, Instant completedAt) {
        return jdbc.update("UPDATE pawbridge_animal.pet_travel_regions SET completed_at=? WHERE code=?",
                Timestamp.from(completedAt), areaCode) == 1;
    }

    private Target lockedTarget(String contentId) {
        return jdbc.queryForObject("SELECT * FROM pawbridge_animal.pet_travel_targets WHERE provider=? AND content_id=? FOR UPDATE",
                this::target, PROVIDER, contentId);
    }

    private Target target(ResultSet row, int index) throws SQLException {
        return new Target(row.getString("content_id"), row.getString("area_code"), row.getString("modified_time"),
                row.getBoolean("shown"), row.getString("image_url"), row.getString("copyright_type"),
                row.getLong("generation"), row.getBoolean("pending"));
    }

    private String json(Map<String, String> fields) {
        try { return mapper.writeValueAsString(fields); }
        catch (Exception exception) { throw new IllegalArgumentException("Invalid travel fields"); }
    }

    private String jsonList(List<Map<String,String>> fields) {
        try { return mapper.writeValueAsString(fields); }
        catch (Exception exception) { throw new IllegalArgumentException("Invalid travel fields"); }
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        var timestamp = row.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
