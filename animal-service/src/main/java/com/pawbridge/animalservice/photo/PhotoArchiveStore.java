package com.pawbridge.animalservice.photo;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Only short database transactions; network I/O belongs to PhotoArchiveWorker. */
public class PhotoArchiveStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private static final String APMS = "api_source='APMS_ANIMAL' AND apms_desertion_no IS NOT NULL";
    public record Claim(long animalId, int slot, long generation, String token, String sourceUrl, int attempts) {}
    private record AnimalSource(long id, String desertionNo, String first, String second) {}

    public PhotoArchiveStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(15);
    }

    public int discover(int limit) {
        return Objects.requireNonNull(transaction.execute(tx -> {
            jdbc.update("INSERT IGNORE INTO apms_photo_scan(id,new_cursor,sweep_cursor) "
                    + "SELECT 1,COALESCE(MAX(id),0),COALESCE(MAX(id),0) FROM animals WHERE " + APMS);
            long[] cursors = jdbc.queryForObject("SELECT new_cursor,sweep_cursor FROM apms_photo_scan WHERE id=1 FOR UPDATE",
                    (row, n) -> new long[]{row.getLong(1), row.getLong(2)});
            List<AnimalSource> added = sources("id>? ORDER BY id ASC", cursors[0], limit);
            List<AnimalSource> sweep = sources("id<=? ORDER BY id DESC", cursors[1], limit);
            for (var animal : added) observe(animal);
            for (var animal : sweep) observe(animal);
            long newCursor = added.isEmpty() ? cursors[0] : added.get(added.size()-1).id();
            long sweepCursor = sweep.isEmpty()
                    ? jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM animals WHERE " + APMS, Long.class)
                    : sweep.get(sweep.size()-1).id()-1;
            jdbc.update("UPDATE apms_photo_scan SET new_cursor=?,sweep_cursor=? WHERE id=1", newCursor, sweepCursor);
            return added.size() + sweep.size();
        }));
    }

    private List<AnimalSource> sources(String bound, long cursor, int limit) {
        return jdbc.query("SELECT id,apms_desertion_no,image_url,image_url2 FROM animals WHERE " + APMS
                        + " AND " + bound + " LIMIT ?",
                (row, n) -> new AnimalSource(row.getLong(1), row.getString(2), row.getString(3), row.getString(4)), cursor, limit);
    }

    private void observe(AnimalSource animal) {
        observe(animal, 1, normalized(animal.first()));
        observe(animal, 2, normalized(animal.second()));
    }

    private static String normalized(String url) { return url == null || url.isBlank() ? null : url.trim(); }

    private void observe(AnimalSource animal, int slot, String source) {
        List<String> previous = jdbc.query("SELECT source_url FROM apms_photo_archive WHERE animal_id=? AND slot=? FOR UPDATE",
                (row, n) -> row.getString(1), animal.id(), slot);
        if (previous.isEmpty()) {
            if (source != null) jdbc.update("INSERT INTO apms_photo_archive(animal_id,slot,desertion_no,source_url,state) "
                    + "VALUES (?,?,?,?,'PENDING')", animal.id(), slot, animal.desertionNo(), source);
        } else if (!Objects.equals(previous.get(0), source)) {
            jdbc.update("UPDATE apms_photo_archive SET source_url=?,generation=generation+1,state=?,attempts=0,"
                            + "next_attempt_at=CURRENT_TIMESTAMP(6),lease_token=NULL,lease_until=NULL,error_code=NULL "
                            + "WHERE animal_id=? AND slot=?", source, source == null ? "ABSENT" : "PENDING", animal.id(), slot);
        }
    }

    public Optional<Claim> claim(int leaseSeconds) { return claim(leaseSeconds, false); }

    public Optional<Claim> claim(int leaseSeconds, boolean preferRecent) {
        String order = preferRecent ? "p.animal_id DESC,p.slot" : "p.next_attempt_at,p.animal_id,p.slot";
        return transaction.execute(tx -> {
            var candidates = jdbc.query("SELECT p.* FROM apms_photo_archive p JOIN animals a ON a.id=p.animal_id "
                    + "WHERE a.api_source='APMS_ANIMAL' AND p.source_url IS NOT NULL AND "
                    + "((p.state IN ('PENDING','RETRY','READY') AND p.next_attempt_at<=CURRENT_TIMESTAMP(6)) "
                    + "OR (p.state='PROCESSING' AND p.lease_until<=CURRENT_TIMESTAMP(6))) "
                    + "ORDER BY " + order + " LIMIT 1 FOR UPDATE SKIP LOCKED",
                    (row, n) -> new Claim(row.getLong("animal_id"), row.getInt("slot"), row.getLong("generation"),
                            UUID.randomUUID().toString(), row.getString("source_url"), row.getInt("attempts")));
            if (candidates.isEmpty()) return Optional.empty();
            var claim = candidates.get(0);
            jdbc.update("UPDATE apms_photo_archive SET state='PROCESSING',lease_token=?,"
                            + "lease_until=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)) WHERE animal_id=? AND slot=?",
                    claim.token(), leaseSeconds, claim.animalId(), claim.slot());
            return Optional.of(claim);
        });
    }

    public boolean complete(Claim claim, ArchivedPhoto photo, int recheckSeconds) {
        // Compare against the current Animal source as well as the discovered generation.
        // A batch update can have committed before discovery visits that animal again.
        return jdbc.update("UPDATE apms_photo_archive p JOIN animals a ON a.id=p.animal_id SET "
                        + "p.state='READY',p.attempts=0,p.error_code=NULL,p.lease_token=NULL,p.lease_until=NULL,"
                        + "p.next_attempt_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)),"
                        + "p.archived_source_url=p.source_url,p.source_sha256=?,p.stored_sha256=?,p.object_key=?,"
                        + "p.content_type=?,p.stored_bytes=?,p.width=?,p.height=?,p.recipe=?,p.archived_at=CURRENT_TIMESTAMP(6) "
                        + "WHERE p.animal_id=? AND p.slot=? AND p.generation=? AND p.lease_token=? "
                        + "AND p.lease_until>CURRENT_TIMESTAMP(6) AND a.api_source='APMS_ANIMAL' "
                        + "AND BINARY p.source_url=BINARY TRIM(CASE p.slot WHEN 1 THEN a.image_url ELSE a.image_url2 END)",
                recheckSeconds, photo.sourceHash(), photo.storedHash(), photo.objectKey(), photo.contentType(),
                photo.bytes().length, photo.width(), photo.height(), photo.recipe(),
                claim.animalId(), claim.slot(), claim.generation(), claim.token()) == 1;
    }

    public boolean retry(Claim claim, String code, int seconds) {
        return jdbc.update("UPDATE apms_photo_archive SET state='RETRY',attempts=attempts+1,error_code=?,"
                        + "next_attempt_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)),lease_token=NULL,lease_until=NULL "
                        + "WHERE animal_id=? AND slot=? AND generation=? AND lease_token=? AND lease_until>CURRENT_TIMESTAMP(6)",
                code, seconds, claim.animalId(), claim.slot(), claim.generation(), claim.token()) == 1;
    }
}
