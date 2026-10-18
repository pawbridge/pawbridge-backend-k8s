package com.pawbridge.communityservice.curation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.pawbridge.communityservice.curation.HomeVideoModels.*;
import com.pawbridge.communityservice.curation.HomeVideoModels.Order;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.web.server.ResponseStatusException;

@Tag("postgresql")
@EnabledIfEnvironmentVariable(named="HOME_VIDEOS_PG_TEST_PORT", matches="[0-9]{1,5}")
class HomeVideoPostgresqlTest {
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager manager;
    static final Instant NOW=Instant.parse("2026-10-07T00:00:00Z");
    HomeVideoRepository repository;
    YouTubeVideoClient youtube;
    HomeVideoService service;
    @BeforeAll static void migrate() {
        var source=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:"+System.getenv("HOME_VIDEOS_PG_TEST_PORT")
                +"/pawbridge","postgres","local_pg_test_only");
        jdbc=new JdbcTemplate(source);
        assertThat(jdbc.queryForList("SELECT marker FROM migration_test_guard.guard",String.class))
                .containsExactly("services-pg-disposable");
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS pawbridge_community");
        var flyway=Flyway.configure().dataSource(source).defaultSchema("pawbridge_community")
                .locations("classpath:db/postgresql").cleanDisabled(true).outOfOrder(false).loggers(new String[0]).load();
        flyway.migrate();flyway.validate();
        manager=new DataSourceTransactionManager(source);
    }
    @BeforeEach void setup() {
        jdbc.execute("DELETE FROM pawbridge_community.home_videos");
        jdbc.execute("UPDATE pawbridge_community.home_video_board SET revision=0");
        repository=new HomeVideoRepository(jdbc);youtube=mock(YouTubeVideoClient.class);
        when(youtube.lookup(anyList())).thenAnswer(call -> {
            List<String> ids=call.getArgument(0);var map=new HashMap<String,Metadata>();
            for(String id:ids) map.put(id,new Metadata(id,"일상 "+id,"테스트 채널",
                    "https://i.ytimg.com/vi/"+id+"/hqdefault.jpg",204,true));
            return map;
        });
        service=new HomeVideoService(repository,youtube,Clock.fixed(NOW,ZoneOffset.UTC),manager);
    }
    Board add(String id,boolean publish) {
        return service.save(null,new Save("https://youtu.be/"+id,publish,repository.revision()));
    }
    @Test void givenSelectedVideo_whenRegisterHideAndPublish_thenPersistedAndPublicDbOnly() {
        var board=add("AbCdEfGhI_1",true);
        assertThat(service.home()).hasSize(1);
        clearInvocations(youtube);
        assertThat(service.home().get(0).title()).isEqualTo("일상 AbCdEfGhI_1");
        verifyNoInteractions(youtube);
        UUID id=board.videos().get(0).id();
        service.publish(id,new Publication(false,board.revision()));
        assertThat(service.home()).isEmpty();
        service.publish(id,new Publication(true,repository.revision()));
        assertThat(service.home()).hasSize(1);
    }
    @Test void givenConcurrentPublicationLimit_whenFourthPublished_thenConflictAndNoWrite() {
        add("AbCdEfGhI_1",true);add("AbCdEfGhI_2",true);add("AbCdEfGhI_3",true);
        assertThatThrownBy(() -> add("AbCdEfGhI_4",true)).isInstanceOf(ResponseStatusException.class);
        assertThat(repository.list()).hasSize(3);
    }
    @Test void givenDuplicateOrStaleRevision_whenSave_thenConflictWithoutExtraRows() {
        add("AbCdEfGhI_1",false);
        assertThatThrownBy(() -> add("AbCdEfGhI_1",false)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.save(null,new Save("https://youtu.be/AbCdEfGhI_2",false,0)))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(repository.list()).hasSize(1);
    }
    @Test void givenTwoAdministratorsSameRevision_whenCreateConcurrently_thenOnlyOneCommit() throws Exception {
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {
            var futures=List.of("AbCdEfGhI_1","AbCdEfGhI_2").stream().map(id -> pool.submit(() -> {
                gate.await();
                try {service.save(null,new Save("https://youtu.be/"+id,false,0));return true;}
                catch(ResponseStatusException conflict) {assertThat(conflict.getStatusCode().value()).isEqualTo(409);return false;}
            })).toList();gate.countDown();
            int success=0;for(var future:futures) if(future.get(15,TimeUnit.SECONDS)) success++;
            assertThat(success).isEqualTo(1);assertThat(repository.list()).hasSize(1);
        } finally {pool.shutdownNow();}
    }
    @Test void givenPublishedIds_whenReorderOrInvalidSet_thenExactSetAndStableOrder() {
        add("AbCdEfGhI_1",true);var board=add("AbCdEfGhI_2",true);
        var ids=board.videos().stream().map(Video::id).toList();
        service.reorder(new Order(List.of(ids.get(1),ids.get(0)),board.revision()));
        assertThat(service.home()).extracting(Video::id).containsExactly(ids.get(1),ids.get(0));
        assertThatThrownBy(() -> service.reorder(new Order(List.of(ids.get(1),ids.get(1)),repository.revision())))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.reorder(new Order(List.of(ids.get(1)),repository.revision())))
                .isInstanceOf(ResponseStatusException.class);
    }
    @Test void givenTransientRefreshFailure_whenRefresh_thenKeepFreshMetadataButClearExpiredValues() {
        var board=add("AbCdEfGhI_1",true);
        var tomorrow=new HomeVideoService(repository,youtube,Clock.fixed(NOW.plus(Duration.ofDays(2)),ZoneOffset.UTC),manager);
        when(youtube.lookup(anyList())).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(tomorrow::refresh).isInstanceOf(ResponseStatusException.class);
        assertThat(tomorrow.home()).hasSize(1);
        var expired=new HomeVideoService(repository,youtube,Clock.fixed(NOW.plus(Duration.ofDays(29)),ZoneOffset.UTC),manager);
        assertThatThrownBy(expired::refresh).isInstanceOf(ResponseStatusException.class);
        Video retained=repository.list().get(0);
        assertThat(retained.id()).isEqualTo(board.videos().get(0).id());
        assertThat(retained.videoId()).isEqualTo("AbCdEfGhI_1");assertThat(retained.published()).isTrue();
        assertThat(retained.title()).isNull();assertThat(retained.thumbnailUrl()).isNull();assertThat(retained.checkedAt()).isNull();
        assertThat(expired.home()).isEmpty();
    }
    @Test void givenConfirmedMissingVideo_whenRecheck_thenUnavailableWithoutChangingPublication() {
        var board=add("AbCdEfGhI_1",true);
        when(youtube.lookup(anyList())).thenReturn(Map.of());
        board=service.recheck(board.videos().get(0).id(),board.revision());
        assertThat(board.videos().get(0).available()).isFalse();
        assertThat(board.videos().get(0).published()).isTrue();
        assertThat(service.home()).isEmpty();
    }
    @Test void givenRefreshInFlight_whenAdministratorRechecks_thenNewerMetadataIsNotOverwritten() throws Exception {
        var board = add("AbCdEfGhI_1", true);
        var refreshClock = Clock.fixed(NOW.plus(Duration.ofDays(2)), ZoneOffset.UTC);
        var delayed = mock(YouTubeVideoClient.class);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(delayed.lookup(anyList())).thenAnswer(call -> {
            started.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return Map.of(); // Older provider response reports absence.
        });
        var refresher = new HomeVideoService(repository, delayed, refreshClock, manager);
        var manual = new HomeVideoService(repository, youtube, refreshClock, manager);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var refresh = pool.submit(refresher::refresh);
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            var newer = manual.recheck(board.videos().get(0).id(), repository.revision());
            release.countDown();
            refresh.get(10, TimeUnit.SECONDS);
            assertThat(repository.list().get(0).available()).isTrue();
            assertThat(repository.list().get(0).checkedAt()).isEqualTo(newer.videos().get(0).checkedAt());
            assertThat(manual.home()).hasSize(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
