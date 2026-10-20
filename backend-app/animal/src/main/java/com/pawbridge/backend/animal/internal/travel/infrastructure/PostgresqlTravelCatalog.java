package com.pawbridge.backend.animal.internal.travel.infrastructure;

import com.pawbridge.backend.animal.internal.travel.application.PetTravelException;
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
import org.springframework.jdbc.core.JdbcOperations;

/** Selected original PostgreSQL SELECTs only. No metadata probing, collector, write or scheduler. */
final class PostgresqlTravelCatalog {
    private static final String PROVIDER = "KOREA_TOURISM_ORGANIZATION";
    private static final String DISCOVERY_CONTENT_TYPES = "('12','14','28')";
    private static final TypeReference<Map<String, String>> FIELDS = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, String>>> FIELD_LIST = new TypeReference<>() {};
    private final JdbcOperations jdbc;
    private final ObjectMapper mapper;
    PostgresqlTravelCatalog(JdbcOperations jdbc, ObjectMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }

    public record Region(String code, String name, Instant fetchedAt, Instant completedAt) {}

    public record Image(String serialNumber, String name, String originalUrl, String thumbnailUrl,
                        String copyrightType, int displayOrder) {}

    public record Place(Map<String, String> common, Map<String, String> pet, Instant publishedAt,
                        Instant basicFetchedAt, String detailStatus, Map<String,String> intro,
                        List<Map<String,String>> information, List<Image> images,
                        Instant introFetchedAt, Instant informationFetchedAt, Instant imagesFetchedAt,
                        String visitInformationStatus, String imagesStatus) {
        public Place(Map<String,String> common, Map<String,String> pet, Instant publishedAt) {
            this(common,pet,publishedAt,publishedAt,publishedAt == null ? "PREPARING" : "READY",
                    Map.of(),List.of(),List.of(),null,null,null,"PREPARING","PREPARING");
        }
        public Place(Map<String,String> common, Map<String,String> pet, Instant publishedAt,
                     Instant basicFetchedAt, String detailStatus) {
            this(common,pet,publishedAt,basicFetchedAt,detailStatus,Map.of(),List.of(),List.of(),
                    null,null,null,"PREPARING","PREPARING");
        }
    }

    public record CollectionState(String phase, int nextPage, String errorCode, Instant completedAt) {}

    public CollectionState collectionState() {
        return jdbc.queryForObject("SELECT * FROM pawbridge_animal.pet_travel_collection_state WHERE id=1",
                (r, i) -> new CollectionState(r.getString("phase"), r.getInt("next_page"),
                        r.getString("error_code"), instant(r, "completed_at")));
    }

    public List<Region> regions() {
        return jdbc.query("SELECT * FROM pawbridge_animal.pet_travel_regions ORDER BY " + "CAST(code AS INTEGER)",
                (row, index) -> new Region(row.getString("code"), row.getString("name"),
                        instant(row, "fetched_at"), instant(row, "completed_at")));
    }

    public List<Place> places(String areaCode, int page) {
        return jdbc.query(publicQuery() + discoveryTypes() + " AND t.area_code=? ORDER BY "
                + "COALESCE(" + "(t.basic_data->>'title')" + ",p.title),t.content_id LIMIT 10 OFFSET ?",
                this::place, PROVIDER, areaCode, (long) page * 10);
    }

    public long countPlaces(String areaCode) {
        return jdbc.queryForObject("SELECT COUNT(*) " + publicFrom() + discoveryTypes()
                + " AND t.area_code=?", Long.class, PROVIDER, areaCode);
    }

    private String discoveryTypes() {
        return " AND (CASE WHEN t.basic_data IS NOT NULL "
                + "THEN " + "(t.basic_data->>'contenttypeid')" + " "
                + "ELSE " + "(p.common_data->>'contenttypeid')" + " END) IN " + DISCOVERY_CONTENT_TYPES;
    }

    public Optional<Place> detail(String contentId) {
        return jdbc.query(publicQuery() + " AND t.content_id=?", this::place, PROVIDER, contentId).stream()
                .findFirst().map(place -> new Place(place.common(),place.pet(),place.publishedAt(),place.basicFetchedAt(),
                        place.detailStatus(),place.intro(),place.information(),images(contentId),place.introFetchedAt(),
                        place.informationFetchedAt(),place.imagesFetchedAt(),place.visitInformationStatus(),place.imagesStatus()));
    }

    private String publicQuery() {
        return "SELECT t.basic_data,t.basic_fetched_at,t.image_url,t.copyright_type,t.pending,t.detail_error,t.modified_time,"
                + "p.common_data,q.pet_data,q.fetched_at AS pet_fetched_at,q.source AS pet_source,"
                + "q.valid_until AS pet_valid_until,t.pet_changed_at,v.intro_data,v.info_data,"
                + "v.intro_source_modified_time,v.info_source_modified_time,v.image_source_modified_time,"
                + "v.intro_fetched_at,v.info_fetched_at,v.image_fetched_at,v.intro_error,v.info_error,v.image_error "
                + publicFrom();
    }

    private String publicFrom() {
        return "FROM pawbridge_animal.pet_travel_targets t "
                + "LEFT JOIN pawbridge_animal.pet_travel_places p ON p.provider=t.provider AND p.content_id=t.content_id AND p.visible=TRUE "
                + "LEFT JOIN pawbridge_animal.pet_travel_pet_details q ON q.provider=t.provider AND q.content_id=t.content_id "
                + "AND (t.pet_valid_after IS NULL OR q.fetched_at>=t.pet_valid_after) "
                + "LEFT JOIN pawbridge_animal.pet_travel_visit_details v ON v.provider=t.provider AND v.content_id=t.content_id "
                + "WHERE t.provider=? AND t.shown=TRUE AND t.area_code IS NOT NULL "
                + "AND (t.basic_data IS NOT NULL OR p.content_id IS NOT NULL)";
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
        var published=instant(row,"pet_fetched_at");
        boolean bulk="BULK".equals(row.getString("pet_source"));
        var validUntil=instant(row,"pet_valid_until");
        var changedAt=instant(row,"pet_changed_at");
        // Compare Instants rather than the database session's wall clock/time zone.
        boolean stale=bulk && ((validUntil!=null && !validUntil.isAfter(Instant.now()))
                || (changedAt!=null && published.isBefore(changedAt)));
        String detailStatus=bulk ? (stale ? "STALE" : "READY")
                : row.getString("detail_error") != null ? (published == null ? "FAILED" : "STALE")
                : published == null ? "PREPARING" : row.getBoolean("pending") ? "STALE" : "READY";
        var intro=row.getString("intro_data")==null?Map.<String,String>of():fields(row.getString("intro_data"));
        var information=row.getString("info_data")==null?List.<Map<String,String>>of():fieldList(row.getString("info_data"));
        var introFetched=instant(row,"intro_fetched_at");
        var infoFetched=instant(row,"info_fetched_at");
        var imageFetched=instant(row,"image_fetched_at");
        String modified=row.getString("modified_time");
        String introStatus=resourceStatus(introFetched,row.getString("intro_source_modified_time"),modified,row.getString("intro_error"));
        String infoStatus=resourceStatus(infoFetched,row.getString("info_source_modified_time"),modified,row.getString("info_error"));
        String visitStatus=combineStatuses(introStatus,infoStatus);
        String imageStatus=resourceStatus(imageFetched,row.getString("image_source_modified_time"),modified,row.getString("image_error"));
        return new Place(Map.copyOf(common),row.getString("pet_data") == null ? Map.of() : fields(row.getString("pet_data")),
                published,instant(row,"basic_fetched_at"),detailStatus,intro,information,List.of(),introFetched,infoFetched,
                imageFetched,visitStatus,imageStatus);
    }

    private List<Image> images(String contentId) {
        return jdbc.query("SELECT serial_number,image_name,original_url,thumbnail_url,copyright_type,display_order "
                        + "FROM pawbridge_animal.pet_travel_images WHERE provider=? AND content_id=? ORDER BY display_order,serial_number",
                (row,index)->new Image(row.getString("serial_number"),row.getString("image_name"),
                        row.getString("original_url"),row.getString("thumbnail_url"),row.getString("copyright_type"),
                        row.getInt("display_order")),PROVIDER,contentId);
    }

    private static String resourceStatus(Instant fetchedAt, String sourceModifiedTime, String currentModifiedTime, String error) {
        if (fetchedAt==null) return error==null?"PREPARING":"FAILED";
        if (!java.util.Objects.equals(sourceModifiedTime,currentModifiedTime) || error!=null) return "STALE";
        return "READY";
    }

    private static String combineStatuses(String first, String second) {
        if (first.equals("READY") && second.equals("READY")) return "READY";
        if (first.equals("STALE") || second.equals("STALE")) return "STALE";
        if ((first.equals("READY") && !second.equals("READY")) || (second.equals("READY") && !first.equals("READY")))
            return "PARTIAL";
        if (first.equals("FAILED") || second.equals("FAILED")) return "FAILED";
        return "PREPARING";
    }

    private Map<String, String> fields(String json) {
        try { return Map.copyOf(mapper.readValue(json, FIELDS)); }
        catch (Exception exception) { throw new IllegalStateException("Invalid stored travel fields"); }
    }

    private List<Map<String,String>> fieldList(String json) {
        try { return mapper.readValue(json,FIELD_LIST).stream().map(Map::copyOf).toList(); }
        catch (Exception exception) { throw new IllegalStateException("Invalid stored travel fields"); }
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        var timestamp = row.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
