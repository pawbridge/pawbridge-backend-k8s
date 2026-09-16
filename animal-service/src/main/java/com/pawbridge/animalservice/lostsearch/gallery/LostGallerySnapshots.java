package com.pawbridge.animalservice.lostsearch.gallery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.animalservice.enums.AnimalStatus;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Immutable, bounded disk snapshots; only the requested page is signed in memory. */
public final class LostGallerySnapshots implements AutoCloseable {
    static final String PROTOCOL = "pawbridge-gallery-pages-v2";
    static final int MAX_PAGE = 500, PAGE_BYTES = 2 * 1024 * 1024, ROW_BYTES = 256 * 1024;
    static final long FILE_BYTES = 128L * 1024 * 1024;
    static final Duration TTL = Duration.ofHours(6);
    static final List<String> METADATA = List.of("status", "happen_date", "happen_place", "color", "special_mark", "description");
    public record Entry(Map<String, Object> record, LostGalleryFeed.StoredPhoto photo) {}
    public record Descriptor(String protocol, String snapshotId, String snapshotSha256, int count,
                             String cursor, long expiresAt) {}
    record Page(String protocol, String snapshotId, String snapshotSha256, int total, int start,
                List<WireEntry> items, String nextCursor, boolean complete) {}
    record WirePhoto(String sha256, String key, long bytes, String mime, String url) {}
    record WireEntry(Map<String, Object> record, WirePhoto photo) {}
    private record Snapshot(Descriptor descriptor, Path file, String requestId) {}
    private record Position(long offset, int count) {}
    static final class Gone extends RuntimeException {}
    static final class BadCursor extends RuntimeException {}
    private final ObjectMapper mapper;
    private final Consumer<Consumer<Entry>> source;
    private final S3Presigner signer;
    private final Path root;
    private final Clock clock;
    private final Map<String, Snapshot> snapshots = new LinkedHashMap<>();
    private final byte[] secret = new byte[32];
    private final FileChannel ownerChannel;
    private final FileLock owner;

    public LostGallerySnapshots(ObjectMapper mapper, Consumer<Consumer<Entry>> source,
                                S3Presigner signer, Path root, Clock clock) throws IOException {
        this.mapper = mapper; this.source = source; this.signer = signer; this.root = root; this.clock = clock;
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) throw new IOException("Snapshot directory cannot be a symlink");
        try { Files.setPosixFilePermissions(root, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")); }
        catch (UnsupportedOperationException ignored) { /* Local Windows contract tests. */ }
        ownerChannel = FileChannel.open(root.resolve("owner.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try { acquired = ownerChannel.tryLock(); }
        catch (RuntimeException | IOException error) { ownerChannel.close(); throw error; }
        if (acquired == null) { ownerChannel.close(); throw new IOException("Snapshot directory already owned"); }
        owner = acquired;
        new SecureRandom().nextBytes(secret);
        // Exclusive ownership means these files can only be abandoned by a previous process.
        try (var paths = Files.list(root)) {
            for (Path p : paths.filter(p -> p.getFileName().toString().matches("[a-f0-9]{32}\\.(partial|snapshot)")).toList())
                Files.delete(p);
        } catch (IOException error) { owner.release(); ownerChannel.close(); throw error; }
    }

    Descriptor create() throws IOException { return create(UUID.randomUUID().toString().replace("-", "")); }

    public synchronized Descriptor create(String requestId) throws IOException {
        if (requestId == null || !requestId.matches("[a-f0-9]{32}")) throw new BadCursor();
        expire();
        for (Snapshot existing : snapshots.values()) {
            if (existing.requestId.equals(requestId)) return find(existing.descriptor.snapshotId()).descriptor;
        }
        if (snapshots.size() >= 2) throw new IllegalStateException("Snapshot retention limit reached");
        String id = UUID.randomUUID().toString().replace("-", "");
        Path partial = root.resolve(id + ".partial"), complete = root.resolve(id + ".snapshot");
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        int[] count = {0}; long[] size = {0}, last = {0}; byte[][] chain = {initialChain()};
        try {
            try (var output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(partial, StandardOpenOption.CREATE_NEW)))) {
                source.accept(entry -> {
                    validate(entry);
                    long current = ((Number) entry.record().get("id")).longValue();
                    if (current <= last[0] || ++count[0] > LostGalleryFeed.MAX_RECORDS || System.nanoTime() > deadline)
                        throw new IllegalStateException("Snapshot order, count or deadline exceeded");
                    byte[] bytes = json(entry);
                    if (bytes.length > ROW_BYTES || (size[0] += bytes.length + 4L) > FILE_BYTES)
                        throw new IllegalStateException("Snapshot storage limit exceeded");
                    try { output.writeInt(bytes.length); output.write(bytes); }
                    catch (IOException error) { throw new UncheckedIOException(error); }
                    last[0] = current; chain[0] = advance(chain[0], entry);
                });
            }
            if (count[0] == 0) throw new IllegalStateException("Empty snapshot is not publishable");
            String sha = HexFormat.of().formatHex(chain[0]);
            Files.move(partial, complete, StandardCopyOption.ATOMIC_MOVE);
            var descriptor = new Descriptor(PROTOCOL, id, sha, count[0], cursor(id, 0, 0), clock.millis() + TTL.toMillis());
            snapshots.put(id, new Snapshot(descriptor, complete, requestId));
            return descriptor;
        } finally { Files.deleteIfExists(partial); }
    }

    public synchronized Descriptor describe(String id) throws IOException { return find(id).descriptor; }

    public synchronized byte[] page(String id, String cursor, int limit) throws IOException {
        if (limit < 1 || limit > MAX_PAGE) throw new BadCursor();
        Snapshot snapshot = find(id); Position position = position(id, cursor);
        if (position.offset < 0 || position.offset >= Files.size(snapshot.file)
                || position.count < 0 || position.count >= snapshot.descriptor.count()) throw new BadCursor();
        var items = new ArrayList<WireEntry>(); long used = 2048, nextOffset = position.offset;
        try (var input = new RandomAccessFile(snapshot.file.toFile(), "r")) {
            input.seek(position.offset);
            while (items.size() < limit && position.count + items.size() < snapshot.descriptor.count()) {
                long start = input.getFilePointer();
                int length = input.readInt();
                if (length < 1 || length > ROW_BYTES) throw new IOException("Invalid snapshot row size");
                byte[] bytes = new byte[length]; input.readFully(bytes);
                Entry entry = mapper.readValue(bytes, Entry.class);
                var photo = entry.photo();
                String url = signer.presignGetObject(r -> r.signatureDuration(Duration.ofHours(1))
                        .getObjectRequest(o -> o.bucket("pawbridge-animal-originals").key(photo.key()))).url().toString();
                if (url.length() > 8192) throw new IOException("Photo URL exceeds limit");
                WireEntry item = new WireEntry(entry.record(), new WirePhoto(photo.sha256(), photo.key(), photo.bytes(), photo.mime(), url));
                int encoded = json(item).length + 1;
                if (used + encoded > PAGE_BYTES) {
                    if (items.isEmpty()) throw new IOException("Single item exceeds page limit");
                    nextOffset = start; break;
                }
                used += encoded; items.add(item); nextOffset = input.getFilePointer();
            }
        }
        int nextCount = position.count + items.size(); boolean done = nextCount == snapshot.descriptor.count();
        byte[] body = json(new Page(PROTOCOL, id, snapshot.descriptor.snapshotSha256(), snapshot.descriptor.count(),
                position.count, items, done ? null : cursor(id, nextOffset, nextCount), done));
        if (body.length > PAGE_BYTES) throw new IOException("Page exceeds transfer limit");
        return body;
    }

    public synchronized void release(String id) throws IOException {
        Snapshot value = find(id); Files.deleteIfExists(value.file); snapshots.remove(id);
    }
    private Snapshot find(String id) throws IOException {
        expire();
        if (id == null || !id.matches("[a-f0-9]{32}") || !snapshots.containsKey(id)) throw new Gone();
        Snapshot previous = snapshots.get(id);
        Descriptor value = previous.descriptor;
        Snapshot renewed = new Snapshot(new Descriptor(value.protocol(), value.snapshotId(), value.snapshotSha256(),
                value.count(), value.cursor(), clock.millis() + TTL.toMillis()), previous.file, previous.requestId);
        snapshots.put(id, renewed);
        return renewed;
    }
    private void expire() throws IOException {
        var iterator = snapshots.values().iterator();
        while (iterator.hasNext()) {
            Snapshot s = iterator.next();
            if (s.descriptor.expiresAt() <= clock.millis()) { Files.deleteIfExists(s.file); iterator.remove(); }
        }
    }
    private String cursor(String id, long offset, int count) {
        String value = id + ":" + offset + ":" + count;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)) + "." + HexFormat.of().formatHex(mac(value));
    }
    private Position position(String id, String token) {
        try {
            if (token == null || token.length() > 256) throw new BadCursor();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2) throw new BadCursor();
            String value = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(mac(value), HexFormat.of().parseHex(parts[1]))) throw new BadCursor();
            String[] fields = value.split(":", -1);
            if (fields.length != 3 || !fields[0].equals(id)) throw new BadCursor();
            return new Position(Long.parseLong(fields[1]), Integer.parseInt(fields[2]));
        } catch (IllegalArgumentException error) { throw new BadCursor(); }
    }
    private byte[] mac(String value) {
        try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret, "HmacSHA256")); return mac.doFinal(value.getBytes(StandardCharsets.UTF_8)); }
        catch (GeneralSecurityException error) { throw new IllegalStateException("Cursor signing unavailable"); }
    }
    private byte[] json(Object value) {
        try { return mapper.writeValueAsBytes(value); }
        catch (IOException error) { throw new UncheckedIOException(error); }
    }
    static byte[] initialChain() { return sha((PROTOCOL + "\n").getBytes(StandardCharsets.UTF_8)); }
    static byte[] advance(byte[] chain, Entry entry) {
        MessageDigest row = digest();
        var values = new ArrayList<Object>();
        values.add(entry.record().get("id")); values.add(entry.record().get("species")); values.add(entry.record().get("source_sha256"));
        for (String field : METADATA) values.add(entry.record().get(field));
        values.add(entry.photo().key()); values.add(entry.photo().bytes()); values.add(entry.photo().mime());
        for (Object value : values) {
            byte[] bytes = value == null ? null : value.toString().getBytes(StandardCharsets.UTF_8);
            row.update(ByteBuffer.allocate(4).putInt(bytes == null ? -1 : bytes.length).array());
            if (bytes != null) row.update(bytes);
        }
        MessageDigest result = digest(); result.update(chain); return result.digest(row.digest());
    }
    private static byte[] sha(byte[] value) { return digest().digest(value); }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    static void validate(Entry entry) {
        LostGalleryFeed.validatePhoto(entry.photo());
        Object id = entry.record().get("id"), species = entry.record().get("species");
        if (!(id instanceof Long || id instanceof Integer) || ((Number)id).longValue() <= 0
                || !("DOG".equals(species) || "CAT".equals(species))
                || !entry.photo().sha256().equals(entry.record().get("source_sha256"))) throw new IllegalStateException("Invalid gallery record");
        for (String field : METADATA) {
            Object value = entry.record().get(field);
            if (value != null && (!(value instanceof String) || ((String)value).length() > 10000)) throw new IllegalStateException("Invalid gallery metadata");
        }
        try { AnimalStatus.valueOf((String) entry.record().get("status")); }
        catch (RuntimeException error) { throw new IllegalStateException("Invalid gallery status"); }
    }
    @Override public synchronized void close() throws IOException {
        try { for (Snapshot value : snapshots.values()) Files.deleteIfExists(value.file); snapshots.clear(); }
        finally { owner.release(); ownerChannel.close(); }
    }
}
