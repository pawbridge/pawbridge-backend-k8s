package com.pawbridge.animalservice.lostsearch.gallery;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LostGallerySnapshotsTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper();
    static final String SHA = "a".repeat(64);
    S3Presigner signer() {
        return S3Presigner.builder().region(Region.of("auto"))
                .endpointOverride(URI.create("https://test.r2.cloudflarestorage.com"))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test-access", "test-secret"))).build();
    }
    LostGallerySnapshots.Entry entry(int id, String description) {
        var row=new LinkedHashMap<String,Object>(); row.put("id",(long)id);row.put("species","DOG");row.put("source_sha256",SHA);
        row.put("description",description);
        return new LostGallerySnapshots.Entry(row,new LostGalleryFeed.StoredPhoto(SHA,"apms/photos/"+SHA+".jpg",123,"image/jpeg"));
    }
    @Test void frozen_snapshot_keeps_original_metadata_and_pages_are_complete_without_duplicates() throws Exception {
        var text=new AtomicReference<>("원본");
        try(var signer=signer();var store=new LostGallerySnapshots(mapper, consume->{for(int id=1;id<=503;id++)consume.accept(entry(id,text.get()));},signer,directory,Clock.systemUTC())) {
            var snapshot=store.create();text.set("변경됨");String cursor=snapshot.cursor();int count=0;
            do {
                byte[] raw=store.page(snapshot.snapshotId(),cursor,100);assertTrue(raw.length<=LostGallerySnapshots.PAGE_BYTES);
                var page=mapper.readTree(raw);assertEquals(count,page.get("start").intValue());
                for(var item:page.get("items")){count++;assertEquals(count,item.get("record").get("id").intValue());assertEquals("원본",item.get("record").get("description").textValue());}
                cursor=page.get("nextCursor").isNull()?null:page.get("nextCursor").textValue();
                assertEquals(count==503,page.get("complete").booleanValue());
            } while(cursor!=null);
            assertEquals(503,count);assertNotEquals(snapshot.snapshotSha256(),store.create().snapshotSha256());
            assertThrows(IllegalStateException.class,store::create);
            store.release(snapshot.snapshotId());assertThrows(LostGallerySnapshots.Gone.class,()->store.describe(snapshot.snapshotId()));
        }
    }
    @Test void byte_limit_reduces_page_size_and_forged_or_foreign_cursors_are_rejected() throws Exception {
        try(var signer=signer();var store=new LostGallerySnapshots(mapper,c->{for(int i=1;i<=500;i++)c.accept(entry(i,"털".repeat(9000)));},signer,directory,Clock.systemUTC())) {
            var first=store.create();var second=store.create();
            var raw=store.page(first.snapshotId(),first.cursor(),500);var page=mapper.readTree(raw);
            assertTrue(raw.length<=LostGallerySnapshots.PAGE_BYTES);assertTrue(page.get("items").size()<500);assertFalse(page.get("complete").booleanValue());
            assertThrows(LostGallerySnapshots.BadCursor.class,()->store.page(first.snapshotId(),first.cursor()+"x",100));
            assertThrows(LostGallerySnapshots.BadCursor.class,()->store.page(second.snapshotId(),first.cursor(),100));
            assertThrows(LostGallerySnapshots.BadCursor.class,()->store.page(first.snapshotId(),first.cursor(),501));
        }
    }
    @Test void expired_snapshot_and_restarted_process_never_resume_from_stale_files() throws Exception {
        var now=new AtomicReference<>(Instant.parse("2026-09-15T00:00:00Z"));
        Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now.get();}};
        String id;
        try(var signer=signer();var store=new LostGallerySnapshots(mapper,c->c.accept(entry(1,"a")),signer,directory,clock)) {
            var snapshot=store.create();id=snapshot.snapshotId();now.set(now.get().plus(Duration.ofHours(7)));
            assertThrows(LostGallerySnapshots.Gone.class,()->store.page(id,snapshot.cursor(),100));
        }
        Files.writeString(directory.resolve("b".repeat(32)+".partial"),"incomplete");
        try(var signer=signer();var store=new LostGallerySnapshots(mapper,c->c.accept(entry(1,"a")),signer,directory,clock)) {
            assertThrows(LostGallerySnapshots.Gone.class,()->store.describe(id));
            assertFalse(Files.exists(directory.resolve("b".repeat(32)+".partial")));
        }
    }
    @Test void active_pages_renew_idle_expiration_without_changing_snapshot_identity() throws Exception {
        var now = new AtomicReference<>(Instant.parse("2026-09-15T00:00:00Z"));
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now.get(); }
        };
        try (var signer = signer(); var store = new LostGallerySnapshots(mapper,
                c -> c.accept(entry(1, "a")), signer, directory, clock)) {
            var snapshot = store.create();
            now.set(now.get().plus(Duration.ofHours(5)));
            store.page(snapshot.snapshotId(), snapshot.cursor(), 100);
            now.set(now.get().plus(Duration.ofHours(5)));
            var resumed = store.describe(snapshot.snapshotId());
            assertEquals(snapshot.snapshotSha256(), resumed.snapshotSha256());
            assertEquals(snapshot.cursor(), resumed.cursor());
            assertTrue(resumed.expiresAt() > snapshot.expiresAt());
        }
    }
    @Test void fingerprint_matches_python_protocol_fixture_including_unicode_and_nulls() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 7L); row.put("species", "DOG"); row.put("source_sha256", SHA);
        row.put("happen_date", "2026-09-15"); row.put("happen_place", "서울");
        row.put("color", "갈색"); row.put("special_mark", null); row.put("description", "");
        var entry = new LostGallerySnapshots.Entry(row,
                new LostGalleryFeed.StoredPhoto(SHA, "apms/photos/" + SHA + ".jpg", 123, "image/jpeg"));
        assertEquals("d5ed239d7a8903047d3b1ff24c5ac666d08ba05a38784fc6198723a67acab578", HexFormat.of().formatHex(LostGallerySnapshots.advance(LostGallerySnapshots.initialChain(), entry)));
    }
    @Test void failed_source_does_not_publish_or_leave_partial_snapshot() throws Exception {
        try(var signer=signer();var store=new LostGallerySnapshots(mapper,c->{c.accept(entry(1,"a"));c.accept(entry(1,"duplicate"));},signer,directory,Clock.systemUTC())) {
            assertThrows(IllegalStateException.class,store::create);
            try(var files=Files.list(directory)){assertEquals(1,files.count());}
        }
    }
    @Test void creation_retry_reuses_the_same_snapshot_instead_of_exhausting_retention() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        try (var signer = signer(); var store = new LostGallerySnapshots(mapper, c -> {
                calls.incrementAndGet(); c.accept(entry(1, "a"));
            }, signer, directory, Clock.systemUTC())) {
            var first = store.create("b".repeat(32));
            for (int i = 0; i < 4; i++) assertEquals(first.snapshotId(), store.create("b".repeat(32)).snapshotId());
            assertEquals(1, calls.get());
            assertNotEquals(first.snapshotId(), store.create("c".repeat(32)).snapshotId());
            assertThrows(LostGallerySnapshots.BadCursor.class, () -> store.create("invalid"));
        }
    }
    @Test void authentication_precedes_snapshot_work_and_expiry_has_explicit_response() throws Exception {
        var store=mock(LostGallerySnapshots.class);
        var mvc=MockMvcBuilders.standaloneSetup(new LostGalleryPagesController(store,"key")).build();
        mvc.perform(post("/internal/animals/lost-gallery/snapshots")).andExpect(status().isUnauthorized());verifyNoInteractions(store);
        when(store.describe("a".repeat(32))).thenThrow(new LostGallerySnapshots.Gone());
        mvc.perform(get("/internal/animals/lost-gallery/snapshots/"+"a".repeat(32)).header("X-Internal-Api-Key","key")).andExpect(status().isGone());
        when(store.create(any())).thenThrow(new IllegalStateException("private detail"));
        mvc.perform(post("/internal/animals/lost-gallery/snapshots").header("X-Internal-Api-Key","key")).andExpect(status().isServiceUnavailable()).andExpect(content().string(""));
    }
    @Test void generated_snapshots_fit_the_192_mib_heap_without_collecting_source_rows() throws Exception {
        for(int count:new int[]{5000,37605,100000}) {
            try(var signer=signer();var store=new LostGallerySnapshots(mapper,c->{for(int i=1;i<=count;i++)c.accept(entry(i,"갈색 강아지 보호소 기록"));},signer,directory,Clock.systemUTC())) {
                var descriptor=store.create();assertEquals(count,descriptor.count());
                assertTrue(Files.size(directory.resolve(descriptor.snapshotId()+".snapshot"))<=LostGallerySnapshots.FILE_BYTES);
                System.out.println("GALLERY_MEMORY count="+count+" maxHeap="+Runtime.getRuntime().maxMemory()+" usedHeap="+(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()));
                store.release(descriptor.snapshotId());
            }
        }
    }
}
