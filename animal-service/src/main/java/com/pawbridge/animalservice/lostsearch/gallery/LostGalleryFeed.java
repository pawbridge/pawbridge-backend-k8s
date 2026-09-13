package com.pawbridge.animalservice.lostsearch.gallery;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Complete read-only archive snapshot; never advances a DB cursor or writes an object. */
public class LostGalleryFeed {
    static final int MAX_RECORDS = 100_000;
    static final int MAX_BODY_BYTES = 128 * 1024 * 1024;
    static final String SQL = """
            SELECT a.id, a.species, a.happen_date, a.happen_place, a.color, a.special_mark, a.description,
                   p.object_key, p.stored_sha256, p.stored_bytes, p.content_type
            FROM apms_photo_archive p JOIN animals a ON a.id = p.animal_id
            WHERE p.state = 'READY' AND p.slot = 1
              AND p.source_url = p.archived_source_url AND p.source_url = a.image_url
              AND a.species IN ('DOG', 'CAT')
            ORDER BY a.id LIMIT 100001
            """;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final S3Presigner signer;

    public LostGalleryFeed(JdbcTemplate jdbc, ObjectMapper mapper, S3Presigner signer) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.signer = signer;
    }

    public record Result(String etag, byte[] body) {}
    record StoredPhoto(String sha256, String key, long bytes, String mime) {}
    record Photo(String sha256, String url, long bytes, String mime) {}
    record Content(List<Map<String, Object>> records, List<StoredPhoto> photos) {}
    record Snapshot(boolean complete, int count, List<Map<String, Object>> records, List<Photo> photos) {}

    @Transactional(readOnly = true, timeout = 120)
    public Result snapshot(String previousEtag) {
        Content content = readContent();
        String etag = fingerprint(json(content));
        if (etag.equals(previousEtag)) return new Result(etag, null);
        List<Photo> photos = content.photos().stream().map(photo -> {
            String url = signer.presignGetObject(request -> request.signatureDuration(Duration.ofHours(1))
                    .getObjectRequest(object -> object.bucket("pawbridge-animal-originals").key(photo.key())))
                    .url().toString();
            return new Photo(photo.sha256(), url, photo.bytes(), photo.mime());
        }).toList();
        byte[] body = json(new Snapshot(true, content.records().size(), content.records(), photos));
        if (body.length > MAX_BODY_BYTES) throw new IllegalStateException("Gallery snapshot exceeds transfer limit");
        return new Result(etag, body);
    }

    Content readContent() {
        return jdbc.query(connection -> {
            var statement = connection.prepareStatement(SQL);
            statement.setQueryTimeout(20);
            // MySQL cursor streaming bounds memory while assembling the response.
            statement.setFetchSize(Integer.MIN_VALUE);
            return statement;
        }, result -> {
            var records = new ArrayList<Map<String, Object>>();
            var photos = new LinkedHashMap<String, StoredPhoto>();
            long serializedBytes = 0;
            while (result.next()) {
                if (records.size() == MAX_RECORDS) throw new IllegalStateException("Gallery record limit exceeded");
                var photo = new StoredPhoto(result.getString("stored_sha256"), result.getString("object_key"),
                        result.getLong("stored_bytes"), result.getString("content_type"));
                validatePhoto(photo);
                var previous = photos.putIfAbsent(photo.sha256(), photo);
                if (previous != null && (previous.bytes() != photo.bytes() || !previous.mime().equals(photo.mime())))
                    throw new IllegalStateException("Conflicting photo integrity metadata");
                var row = new LinkedHashMap<String, Object>();
                row.put("id", result.getLong("id"));
                row.put("species", result.getString("species"));
                row.put("source_sha256", photo.sha256());
                for (String column : List.of("happen_date", "happen_place", "color", "special_mark", "description")) {
                    String value = result.getString(column);
                    if (value != null && value.length() > 10000) throw new IllegalStateException("Gallery metadata exceeds limit");
                    row.put(column, value);
                }
                serializedBytes += json(row).length + 1200L; // Reserve space for each bounded signed URL.
                if (serializedBytes > MAX_BODY_BYTES) throw new IllegalStateException("Gallery snapshot exceeds transfer limit");
                records.add(row);
            }
            if (records.isEmpty()) throw new IllegalStateException("Empty gallery snapshot is not publishable");
            return new Content(List.copyOf(records), List.copyOf(photos.values()));
        });
    }

    static void validatePhoto(StoredPhoto photo) {
        if (photo.sha256() == null || !photo.sha256().matches("[a-f0-9]{64}")
                || photo.key() == null || !photo.key().matches("apms/photos/[a-f0-9]{64}\\.(jpg|png|webp)")
                || photo.bytes() <= 0 || photo.bytes() > 10 * 1024 * 1024
                || photo.mime() == null || !List.of("image/jpeg", "image/png", "image/webp").contains(photo.mime()))
            throw new IllegalStateException("Invalid archived photo metadata");
    }

    private byte[] json(Object value) {
        try { return mapper.writeValueAsBytes(value); }
        catch (Exception error) { throw new IllegalStateException("Cannot serialize gallery snapshot"); }
    }

    private String fingerprint(byte[] value) {
        try { return "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)) + "\""; }
        catch (Exception error) { throw new IllegalStateException("Cannot fingerprint gallery snapshot"); }
    }
}
