package com.pawbridge.animalservice.photo;

import java.net.*;
import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import com.sun.net.httpserver.HttpServer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("mysql")
class PhotoArchiveMysqlTest {
    private JdbcTemplate jdbc;
    private PhotoArchiveStore store;
    private static final String TEST_SCHEMA="pawbridge_photo_archive_test";
    private static final String PASSWORD="local_flyway_test_only";
    private final byte[] raw={1,2,3};
    private final ArchivedPhoto photo=new ArchivedPhoto(raw,ArchivedPhoto.sha256(raw),ArchivedPhoto.sha256(raw),"image/png",1,1,"original-v1");

    @BeforeAll static void migrate_guarded_test_database() throws Exception {
        String port=System.getenv("ANIMAL_MIGRATION_TEST_PORT");
        if(port==null || !port.matches("[0-9]{1,5}")) throw new IllegalStateException("Disposable MySQL test port required");
        try(var connection=DriverManager.getConnection("jdbc:mysql://127.0.0.1:"+port+"/flyway_test_guard","root",PASSWORD);
            var statement=connection.createStatement()) {
            try(var rows=statement.executeQuery("SELECT marker FROM guard")) {
                if(!rows.next() || !"animal-flyway-disposable".equals(rows.getString(1)))
                    throw new IllegalStateException("Missing disposable database marker");
            }
            statement.execute("DROP DATABASE IF EXISTS "+TEST_SCHEMA);
            statement.execute("CREATE DATABASE "+TEST_SCHEMA);
        }
        Flyway.configure().dataSource(url(),"root",PASSWORD).schemas(TEST_SCHEMA).createSchemas(false)
                .cleanDisabled(true).locations("classpath:db/migration").load().migrate();
    }
    private static String url(){return "jdbc:mysql://127.0.0.1:"+System.getenv("ANIMAL_MIGRATION_TEST_PORT")+"/"+TEST_SCHEMA;}
    @BeforeEach void fixtures() {
        var source=new DriverManagerDataSource(url(),"root",PASSWORD);
        jdbc=new JdbcTemplate(source); store=new PhotoArchiveStore(jdbc,new DataSourceTransactionManager(source));
        jdbc.update("DELETE FROM apms_photo_archive"); jdbc.update("DELETE FROM apms_photo_scan");
        jdbc.update("DELETE FROM animals"); jdbc.update("DELETE FROM shelters");
        jdbc.update("INSERT INTO shelters(id,created_at,care_reg_no,name) VALUES(1,NOW(),'photo-test','Photo test shelter')");
    }
    private void animal(long id,String first,String second) {
        jdbc.update("INSERT INTO animals(id,created_at,api_source,apms_desertion_no,apms_notice_no,favorite_count,gender,"
                +"neuter_status,notice_end_date,notice_start_date,species,status,shelter_id,image_url,image_url2) "
                +"VALUES(?,NOW(),'APMS_ANIMAL',?,'same-notice',0,'UNKNOWN','UNKNOWN',CURDATE(),CURDATE(),'DOG','PROTECT',1,?,?)",
                id,"desertion-"+id,first,second);
    }
    private void rescan(){for(int i=0;i<3;i++)store.discover(50);}
    private String state(){return jdbc.queryForObject("SELECT state FROM apms_photo_archive WHERE animal_id=1 AND slot=1",String.class);}

    @Test void discovery_tracks_two_slots_and_notice_reuse_without_manual_animals() {
        animal(1,"http://openapi.animal.go.kr/a","http://openapi.animal.go.kr/b");
        animal(2,"http://openapi.animal.go.kr/c",null);
        animal(3,"http://openapi.animal.go.kr/manual",null); jdbc.update("UPDATE animals SET api_source='MANUAL' WHERE id=3");
        store.discover(50); rescan();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apms_photo_archive",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT MAX(generation) FROM apms_photo_archive",Long.class)).isEqualTo(1);
    }
    @Test void new_lane_and_sweep_resume_without_losing_old_rows() {
        animal(1,"http://openapi.animal.go.kr/a",null); animal(2,"http://openapi.animal.go.kr/b",null);
        store.discover(1); animal(3,"http://openapi.animal.go.kr/c",null); store.discover(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apms_photo_archive",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT new_cursor FROM apms_photo_scan WHERE id=1",Long.class)).isEqualTo(3);
    }
    @Test void processing_alternates_recent_and_long_waiting_photos() {
        animal(1,"http://openapi.animal.go.kr/old",null);store.discover(50);
        animal(2,"http://openapi.animal.go.kr/new",null);store.discover(50);
        assertThat(store.claim(300,true).orElseThrow().animalId()).isEqualTo(2);
        assertThat(store.claim(300,false).orElseThrow().animalId()).isEqualTo(1);
    }
    @Test void failed_discovery_rolls_back_cursor_and_new_rows_together() {
        animal(1,"http://openapi.animal.go.kr/a",null);
        jdbc.execute("CREATE TRIGGER photo_discovery_fail BEFORE INSERT ON apms_photo_archive FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test failure'");
        try { assertThatThrownBy(()->store.discover(50)).isInstanceOf(RuntimeException.class); }
        finally { jdbc.execute("DROP TRIGGER photo_discovery_fail"); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apms_photo_scan",Integer.class)).isZero();
        store.discover(50); assertThat(store.claim(300)).isPresent();
    }
    @Test void expired_lease_is_recovered_and_old_token_cannot_complete_or_retry() {
        animal(1,"http://openapi.animal.go.kr/a",null); store.discover(50);
        var old=store.claim(300).orElseThrow(); assertThat(store.claim(300)).isEmpty();
        jdbc.update("UPDATE apms_photo_archive SET lease_until=NOW()-INTERVAL 1 SECOND");
        var current=store.claim(300).orElseThrow(); assertThat(current.token()).isNotEqualTo(old.token());
        assertThat(store.complete(old,photo,3600)).isFalse(); assertThat(store.retry(old,"OLD",60)).isFalse();
        assertThat(store.complete(current,photo,3600)).isTrue(); assertThat(state()).isEqualTo("READY");
    }
    @Test void changed_source_before_discovery_already_blocks_stale_completion() {
        animal(1,"http://openapi.animal.go.kr/a",null); store.discover(50); var old=store.claim(300).orElseThrow();
        jdbc.update("UPDATE animals SET image_url='http://openapi.animal.go.kr/new' WHERE id=1");
        assertThat(store.complete(old,photo,3600)).isFalse(); rescan();
        var current=store.claim(300).orElseThrow(); assertThat(current.generation()).isGreaterThan(old.generation());
        assertThat(current.sourceUrl()).endsWith("/new"); assertThat(store.retry(old,"OLD",60)).isFalse();
    }
    @Test void same_url_recheck_and_removed_url_keep_last_success_bytes_metadata() {
        animal(1,"http://openapi.animal.go.kr/a",null); store.discover(50);
        store.complete(store.claim(300).orElseThrow(),photo,3600); assertThat(store.claim(300)).isEmpty();
        jdbc.update("UPDATE apms_photo_archive SET next_attempt_at=NOW()-INTERVAL 1 SECOND");
        var repeated=store.claim(300).orElseThrow(); store.retry(repeated,"SOURCE_HTTP_404",86400);
        assertThat(jdbc.queryForObject("SELECT object_key FROM apms_photo_archive",String.class)).isEqualTo(photo.objectKey());
        jdbc.update("UPDATE animals SET image_url=NULL WHERE id=1"); rescan();
        assertThat(state()).isEqualTo("ABSENT"); assertThat(store.claim(300)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT object_key FROM apms_photo_archive",String.class)).isEqualTo(photo.objectKey());
    }
    @Test void concurrent_claimers_cannot_own_the_same_photo() throws Exception {
        animal(1,"http://openapi.animal.go.kr/a",null); store.discover(50);
        var pool=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
        try {
            Callable<Optional<PhotoArchiveStore.Claim>> task=()->{start.await();return store.claim(300);};
            var a=pool.submit(task);var b=pool.submit(task);start.countDown();
            assertThat(List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS)).stream().filter(Optional::isPresent).count()).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }

    @Test @Tag("photo-http") void real_optimizer_and_s3_http_readback_survive_db_failure_and_same_url_change() throws Exception {
        String port=System.getenv("PHOTO_OPTIMIZER_TEST_PORT");
        if(port==null || !port.matches("[0-9]{1,5}")) throw new IllegalStateException("Disposable photo optimizer port required");
        var objects=new ConcurrentHashMap<String,byte[]>(); var contentTypes=new ConcurrentHashMap<String,String>();
        var puts=new java.util.concurrent.atomic.AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            String path=exchange.getRequestURI().getPath();
            if(exchange.getRequestMethod().equals("PUT")) {
                objects.put(path,exchange.getRequestBody().readAllBytes());contentTypes.put(path,exchange.getRequestHeaders().getFirst("Content-Type"));
                puts.incrementAndGet();exchange.sendResponseHeaders(200,-1);
            } else if(objects.containsKey(path)) {
                byte[] data=objects.get(path);exchange.getResponseHeaders().set("Content-Type",contentTypes.get(path));
                exchange.sendResponseHeaders(200,data.length);exchange.getResponseBody().write(data);
            } else {
                byte[] error="<Error><Code>NoSuchKey</Code></Error>".getBytes();
                exchange.sendResponseHeaders(404,error.length);exchange.getResponseBody().write(error);
            }
            exchange.close();
        });server.start();
        try(var s3=S3Client.builder().endpointOverride(URI.create("http://127.0.0.1:"+server.getAddress().getPort()))
                .region(Region.US_EAST_1).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test","test")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).chunkedEncodingEnabled(false).build()).build()) {
            var properties=new PhotoArchiveProperties(); properties.setOptimizerUrl(URI.create("http://127.0.0.1:"+port+"/internal/photos/optimize"));
            properties.setInternalApiKey("photo-archive-test-only"); properties.setMaxPhotos(1);
            var http=spy(new PhotoArchiveHttpClient(properties));
            String url="http://openapi.animal.go.kr/local-fixture";
            var image=new BufferedImage(400,300,BufferedImage.TYPE_INT_RGB); var png=new ByteArrayOutputStream();ImageIO.write(image,"png",png);
            doReturn(png.toByteArray()).when(http).download(url);
            var storage=new PhotoArchiveObjectStorage(s3,"photo-test");
            animal(1,url,null);
            jdbc.execute("CREATE TRIGGER photo_completion_fail BEFORE UPDATE ON apms_photo_archive FOR EACH ROW BEGIN IF NEW.state='READY' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test completion failure'; END IF; END");
            try {new PhotoArchiveWorker(store,http,storage,properties).runOnce();}
            finally {jdbc.execute("DROP TRIGGER photo_completion_fail");}
            assertThat(state()).isEqualTo("RETRY");assertThat(puts.get()).isEqualTo(1);
            jdbc.update("UPDATE apms_photo_archive SET next_attempt_at=NOW()-INTERVAL 1 SECOND");
            var worker=new PhotoArchiveWorker(store,http,storage,properties);
            assertThat(worker.runOnce().completed()).isEqualTo(1);assertThat(puts.get()).isEqualTo(1);

            // Same URL now serves different bytes: periodic checks must replace the recorded version.
            image.setRGB(10,10,0xffffff);png.reset();ImageIO.write(image,"png",png);
            doReturn(png.toByteArray()).when(http).download(url);
            jdbc.update("UPDATE apms_photo_archive SET next_attempt_at=NOW()-INTERVAL 1 SECOND");
            assertThat(worker.runOnce().completed()).isEqualTo(1);
            String sourceHash=jdbc.queryForObject("SELECT source_sha256 FROM apms_photo_archive",String.class);
            assertThat(sourceHash).isEqualTo(ArchivedPhoto.sha256(png.toByteArray()));
            // Corrupt an existing content-addressed object: it must not be overwritten or reported successful.
            String key=jdbc.queryForObject("SELECT object_key FROM apms_photo_archive",String.class);
            byte[] damaged=objects.get("/photo-test/"+key).clone();damaged[0]^=1;objects.put("/photo-test/"+key,damaged);
            jdbc.update("UPDATE apms_photo_archive SET next_attempt_at=NOW()-INTERVAL 1 SECOND");
            int before=puts.get();assertThat(worker.runOnce().failed()).isEqualTo(1);assertThat(puts.get()).isEqualTo(before);
        } finally {server.stop(0);}
    }
}
