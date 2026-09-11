package com.pawbridge.animalservice.reconciliation;

import com.pawbridge.animalservice.batch.processor.AnimalItemProcessor;
import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.entity.Shelter;
import java.sql.*;
import java.time.*;
import java.util.*;

/** All writes and the APMS named lock use the same physical session: losing it prevents further writes. */
public final class HistoryStore implements AutoCloseable {
    public static final String LOCK_NAME = "pawbridge_animal.apmsAnimalSyncJob";
    private final Connection connection;
    private boolean locked;
    public HistoryStore(Connection connection) { this.connection = connection; }
    public String identity() throws SQLException {
        try (var statement = connection.prepareStatement("SELECT CONCAT(@@server_uuid, '/', DATABASE())");
             var rows = statement.executeQuery()) { rows.next(); return rows.getString(1); }
    }
    public void acquire() throws SQLException {
        if (locked) HistoryPlan.fail("Execution lock already acquired by this runner");
        try (var statement = connection.prepareStatement("SELECT GET_LOCK(?, 0)")) {
            statement.setString(1, LOCK_NAME);
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getInt(1) != 1 || rows.wasNull()) HistoryPlan.fail("APMS execution lock unavailable");
            }
        }
        locked = true;
        try (var statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM BATCH_JOB_EXECUTION e JOIN BATCH_JOB_INSTANCE i
                ON e.JOB_INSTANCE_ID = i.JOB_INSTANCE_ID
                WHERE i.JOB_NAME = 'apmsAnimalSyncJob' AND e.END_TIME IS NULL
                """); var rows = statement.executeQuery()) {
            rows.next();
            if (rows.getInt(1) != 0) HistoryPlan.fail("Unfinished APMS job metadata requires inspection");
        }
    }
    public HistoryPlan plan(YearMonth month, HistoryCollector.Snapshot snapshot, String search) throws Exception {
        connection.setReadOnly(true);
        connection.setAutoCommit(false);
        try {
            var states = new HashMap<String, HistoryPlan.State>();
            try (var statement = connection.prepareStatement("SELECT * FROM animals WHERE apms_desertion_no IS NOT NULL");
                 var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (!"APMS_ANIMAL".equals(rows.getString("api_source"))) continue;
                    states.put(rows.getString("apms_desertion_no"), state(rows));
                }
            }
            var shelters = new HashMap<String, Long>();
            try (var statement = connection.prepareStatement("SELECT id, care_reg_no FROM shelters");
                 var rows = statement.executeQuery()) {
                while (rows.next()) shelters.put(rows.getString(2), rows.getLong(1));
            }
            var entries = new ArrayList<HistoryPlan.Entry>();
            for (var item : snapshot.items()) {
                var before = states.get(item.getDesertionNo());
                var after = HistoryPlan.after(item, before == null ? null : before.id());
                boolean changed = before == null || !Objects.equals(before.status(), after.status())
                        || !Objects.equals(before.processState(), after.processState())
                        || !Objects.equals(before.happenDate(), after.happenDate());
                if (!changed) continue;
                Long shelter = shelters.get(item.getCareRegNo());
                if (shelter == null) HistoryPlan.fail("Source shelter is absent; reconcile shelters before planning animals");
                entries.add(new HistoryPlan.Entry(item, before, shelter));
            }
            entries.sort(Comparator.comparing(e -> e.source().getDesertionNo()));
            var plan = new HistoryPlan(HistoryPlan.CONTRACT, month.toString(), Instant.now(), identity(), search,
                    snapshot.reported(), snapshot.items().size(), snapshot.warnings(), List.copyOf(entries));
            plan.validate();
            return plan;
        } finally {
            connection.rollback();
            connection.setAutoCommit(true);
            connection.setReadOnly(false);
        }
    }
    public record Applied(long id, String action) { }
    public Applied apply(HistoryPlan.Entry entry) throws Exception {
        if (!locked) HistoryPlan.fail("Execution lock required");
        connection.setAutoCommit(false);
        try {
            var current = find(entry.source().getDesertionNo(), true);
            Applied result;
            if (entry.before() == null) {
                if (current != null) result = new Applied(current.id(), "ALREADY_PRESENT");
                else result = new Applied(insert(entry), "INSERTED");
            } else {
                if (current == null || !current.id().equals(entry.before().id())) HistoryPlan.fail("Reviewed identity changed");
                var target = HistoryPlan.after(entry.source(), current.id());
                if (current.sameValues(target)) result = new Applied(current.id(), "ALREADY_APPLIED");
                else if (current.sourceUpdatedAt() != null && current.sourceUpdatedAt().isAfter(target.sourceUpdatedAt()))
                    result = new Applied(current.id(), "NEWER_DB_RETAINED");
                else {
                    if (!current.equals(entry.before())) HistoryPlan.fail("DB changed since planning; no overwrite");
                    try (var statement = connection.prepareStatement("""
                            UPDATE animals SET status=?, apms_process_state=?, happen_date=?, apms_updated_at=?
                            WHERE id=? AND apms_desertion_no=?
                            """)) {
                        statement.setString(1, target.status()); statement.setString(2, target.processState());
                        statement.setObject(3, target.happenDate()); statement.setObject(4, target.sourceUpdatedAt());
                        statement.setLong(5, current.id()); statement.setString(6, entry.source().getDesertionNo());
                        if (statement.executeUpdate() != 1) HistoryPlan.fail("Update count mismatch");
                    }
                    result = new Applied(current.id(), "UPDATED");
                }
            }
            connection.commit();
            return result;
        } catch (Exception failure) {
            connection.rollback();
            throw failure;
        } finally { connection.setAutoCommit(true); }
    }
    private long insert(HistoryPlan.Entry entry) throws Exception {
        try (var statement = connection.prepareStatement("SELECT care_reg_no FROM shelters WHERE id=? FOR SHARE")) {
            statement.setLong(1, entry.shelterId());
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !entry.source().getCareRegNo().equals(rows.getString(1)))
                    HistoryPlan.fail("Reviewed shelter changed");
            }
        }
        Animal animal = AnimalItemProcessor.createNewAnimal(entry.source(), Shelter.builder().id(entry.shelterId()).build());
        Map<String, Object> values = insertFields(animal);
        String sql = "INSERT INTO animals (" + String.join(",", values.keySet()) + ") VALUES ("
                + String.join(",", Collections.nCopies(values.size(), "?")) + ")";
        try (var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            int index = 1;
            for (Object value : values.values()) statement.setObject(index++, value);
            if (statement.executeUpdate() != 1) HistoryPlan.fail("Insert count mismatch");
            try (var keys = statement.getGeneratedKeys()) {
                if (!keys.next()) HistoryPlan.fail("Missing inserted identity");
                return keys.getLong(1);
            }
        }
    }
    public void sync(long id, String apmsNo, HistorySearch search, String target) throws Exception {
        if (!locked) HistoryPlan.fail("Execution lock required");
        connection.setAutoCommit(false);
        try {
            // Hold the current DB row while projecting it. Do not replay a stale plan into Elasticsearch.
            var current = find(apmsNo, true);
            if (current == null || current.id() != id) HistoryPlan.fail("Search target identity changed");
            Map<String, Object> document = document(id);
            search.sync(target, id, document);
            connection.commit();
        } catch (Exception failure) {
            connection.rollback();
            throw failure;
        } finally { connection.setAutoCommit(true); }
    }
    private HistoryPlan.State find(String apmsNo, boolean forUpdate) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM animals WHERE apms_desertion_no=?" + (forUpdate ? " FOR UPDATE" : ""))) {
            statement.setQueryTimeout(10);
            statement.setString(1, apmsNo);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                if (!"APMS_ANIMAL".equals(rows.getString("api_source"))) HistoryPlan.fail("Non-APMS identity collision");
                return state(rows);
            }
        }
    }
    private static HistoryPlan.State state(ResultSet rows) throws SQLException {
        return new HistoryPlan.State(rows.getLong("id"), rows.getString("status"), rows.getString("apms_process_state"),
                rows.getObject("happen_date", LocalDate.class), rows.getObject("apms_updated_at", LocalDateTime.class));
    }
    private Map<String, Object> document(long id) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT a.*, s.name AS shelter_name, s.address AS shelter_address, s.phone AS shelter_phone
                FROM animals a JOIN shelters s ON a.shelter_id=s.id WHERE a.id=?
                """)) {
            statement.setLong(1, id);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("Animal or shelter absent");
                var document = new LinkedHashMap<String, Object>();
                var columns = rows.getMetaData();
                for (int i = 1; i <= columns.getColumnCount(); i++) {
                    Object value = rows.getObject(i);
                    if (value instanceof Timestamp timestamp) value = timestamp.toLocalDateTime().toString();
                    if (value instanceof java.sql.Date date) value = date.toLocalDate().toString();
                    if (value instanceof LocalDateTime timestamp) value = timestamp.toString();
                    document.put(columns.getColumnLabel(i), value);
                }
                // Explicit projection matches the existing AnimalDocument contract, excluding AI-owned fields.
                document.keySet().retainAll(SEARCH_FIELDS);
                return document;
            }
        }
    }
    private static final Set<String> SEARCH_FIELDS = Set.of("id", "apms_desertion_no", "apms_notice_no", "species", "breed", "birth_year",
            "weight", "color", "gender", "neuter_status", "special_mark", "apms_process_state", "notice_start_date", "notice_end_date",
            "apms_updated_at", "happen_date", "happen_place", "image_url", "image_url2", "shelter_id", "shelter_name", "shelter_address",
            "shelter_phone", "status", "api_source", "favorite_count", "description", "created_at", "updated_at");
    static Map<String, Object> insertFields(Animal animal) {
        var values = new LinkedHashMap<String, Object>();
        values.put("apms_desertion_no", animal.getApmsDesertionNo()); values.put("apms_notice_no", animal.getApmsNoticeNo());
        values.put("species", animal.getSpecies().name()); values.put("breed", animal.getBreed()); values.put("birth_year", animal.getBirthYear());
        values.put("weight", animal.getWeight()); values.put("color", animal.getColor()); values.put("gender", animal.getGender().name());
        values.put("neuter_status", animal.getNeuterStatus().name()); values.put("special_mark", animal.getSpecialMark());
        values.put("apms_process_state", animal.getApmsProcessState()); values.put("notice_start_date", animal.getNoticeStartDate());
        values.put("notice_end_date", animal.getNoticeEndDate()); values.put("apms_updated_at", animal.getApmsUpdatedAt());
        values.put("happen_date", animal.getHappenDate()); values.put("happen_place", animal.getHappenPlace());
        values.put("image_url", animal.getImageUrl()); values.put("image_url2", animal.getImageUrl2());
        values.put("shelter_id", animal.getShelter().getId()); values.put("status", animal.getStatus().name());
        values.put("api_source", animal.getApiSource().name()); values.put("favorite_count", animal.getFavoriteCount());
        values.put("description", animal.getDescription());
        var now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        values.put("created_at", now); values.put("updated_at", now);
        return values;
    }
    @Override public void close() throws SQLException {
        try {
            if (!connection.getAutoCommit()) connection.rollback();
            if (locked) {
                try (var statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                    statement.setString(1, LOCK_NAME);
                    try (var result = statement.executeQuery()) {
                        if (!result.next() || result.getInt(1) != 1 || result.wasNull()) {
                            connection.abort(Runnable::run);
                            HistoryPlan.fail("Execution lock release uncertain; connection discarded");
                        }
                    }
                }
            }
        } finally { connection.close(); }
    }
}
