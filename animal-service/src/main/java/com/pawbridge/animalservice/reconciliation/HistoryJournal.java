package com.pawbridge.animalservice.reconciliation;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import static java.nio.file.StandardOpenOption.*;

/** Append-only, forced before/after each side effect. The plan remains the recovery authority. */
public final class HistoryJournal implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    public HistoryJournal(Path path, String planHash) throws Exception {
        if (Files.isSymbolicLink(path)) throw new IllegalArgumentException("Journal must not be a symlink");
        channel = FileChannel.open(path, CREATE, READ, WRITE);
        FileLock acquired = null;
        try {
            acquired = channel.tryLock();
            if (acquired == null) throw new IllegalStateException("Journal already in use");
            if (channel.size() > 0) {
                if (channel.size() > 64L * 1024 * 1024) throw new IllegalStateException("Journal exceeds limit");
                var header = ByteBuffer.allocate((int) Math.min(channel.size(), 8192));
                while (header.hasRemaining() && channel.read(header) > 0) { }
                String text = new String(header.array(), 0, header.position(), java.nio.charset.StandardCharsets.UTF_8);
                int newline = text.indexOf('\n');
                if (newline < 0) throw new IllegalStateException("Incomplete journal header");
                String first = text.substring(0, newline);
                if (!planHash.equals(HistoryPlan.JSON.readTree(first).path("planHash").asText()))
                    throw new IllegalStateException("Journal belongs to another plan");
                channel.position(channel.size());
                channel.write(ByteBuffer.wrap(new byte[]{'\n'})); // A torn final event cannot swallow the next event.
            }
            lock = acquired;
            event("RUN_STARTED", Map.of("planHash", planHash));
        } catch (Exception failure) {
            if (acquired != null) acquired.release();
            channel.close();
            throw failure;
        }
    }
    public void event(String phase, Map<String, ?> values) throws Exception {
        var record = new java.util.LinkedHashMap<String, Object>(values);
        record.put("phase", phase);
        record.put("at", java.time.Instant.now().toString());
        ByteBuffer bytes = ByteBuffer.wrap((HistoryPlan.JSON.writeValueAsString(record) + "\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        while (bytes.hasRemaining()) channel.write(bytes);
        channel.force(true);
    }
    public static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    public void close() throws Exception {
        try { lock.release(); } finally { channel.close(); }
    }
}
