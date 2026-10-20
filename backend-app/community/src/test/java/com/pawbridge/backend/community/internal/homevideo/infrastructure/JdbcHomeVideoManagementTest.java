package com.pawbridge.backend.community.internal.homevideo.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideo;
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class JdbcHomeVideoManagementTest {
    static final String VIDEO="AbCdEfGhI_1",URL="https://youtu.be/"+VIDEO;
    static final UUID ID=UUID.fromString("00000000-0000-0000-0000-000000000001"),OTHER=UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final Instant NOW=Instant.parse("2030-01-01T00:00:00.123456Z");
    static final Metadata METADATA=new Metadata(VIDEO,"title","channel","https://i.ytimg.com/x.jpg",60,true);
    static HomeVideo video(UUID id,String videoId,boolean published,Instant checked){return new HomeVideo(id,videoId,published,0,NOW,"title","channel",null,60L,true,checked);}
    @Test void given__an_existing_board__when__reading__then__lock_and_snapshot_in_one_source_default_transaction(){
        var f=new Fixture();assertThat(f.service.board()).isEqualTo(new Board(5,List.of()));
        var order=inOrder(f.repo);order.verify(f.repo).lockBoard();order.verify(f.repo).revision();order.verify(f.repo).list();
        assertThat(f.events).containsExactly("begin","commit");assertThat(f.manager.timeout).isEqualTo(-1);verifyNoInteractions(f.youtube);
    }
    @Test void given__new_available_video__when__saving__then__lookup_before_transaction_then_insert_metadata_and_revision_once(){
        var f=new Fixture();f.service.save(null,new Save(URL,true,5));
        assertThat(f.events).containsExactly("lookup","begin","commit");
        var id=ArgumentCaptor.forClass(UUID.class);var order=inOrder(f.repo);
        order.verify(f.repo).lockBoard();order.verify(f.repo).list();order.verify(f.repo).insert(id.capture(),eq(VIDEO),eq(true),eq(0),eq(NOW));
        order.verify(f.repo).metadata(id.getValue(),METADATA,NOW);order.verify(f.repo).changed();order.verify(f.repo).revision();order.verify(f.repo).list();
        verify(f.clock,times(2)).instant();
    }
    @ParameterizedTest @ValueSource(strings={"revision","duplicate","capacity","slots","unavailable"})
    void given__a_conflicting_or_unavailable_selection__when__saving__then__preserve_source_rejection_before_any_mutation(String mode){
        var f=new Fixture();
        switch(mode){
            case "revision"->when(f.repo.lockBoard()).thenReturn(6L);
            case "duplicate"->when(f.repo.list()).thenReturn(List.of(video(OTHER,VIDEO,false,NOW)));
            case "capacity"->{var rows=new ArrayList<HomeVideo>();for(int i=0;i<100;i++)rows.add(video(new UUID(0,i+1),"other"+i,false,NOW));when(f.repo.list()).thenReturn(rows);}
            case "slots"->when(f.repo.list()).thenReturn(List.of(video(ID,"one",true,NOW),video(OTHER,"two",true,NOW),video(new UUID(0,3),"three",true,NOW)));
            default->doAnswer(call->{f.events.add("lookup");return Map.of(VIDEO,new Metadata(VIDEO,"title","channel",null,60,false));}).when(f.youtube).lookup(anyList());
        }
        assertThatThrownBy(()->f.service.save(null,new Save(URL,true,5))).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(mode.equals("unavailable")?400:409));
        verify(f.repo,never()).insert(any(),anyString(),anyBoolean(),anyInt(),any());verify(f.repo,never()).changed();
        assertThat(f.events).containsExactlyElementsOf(mode.equals("unavailable")?List.of("lookup"):List.of("lookup","begin","rollback"));
    }
    @Test void given__a_published_video_and_unavailable_provider__when__hiding__then__do_not_lookup_or_replace_metadata(){
        var f=new Fixture();when(f.repo.list()).thenReturn(List.of(video(ID,VIDEO,true,NOW)));
        f.service.publish(ID,new Publication(false,5));verifyNoInteractions(f.youtube);verify(f.repo).publication(ID,false);verify(f.repo,never()).metadata(any(),any(),any());
        assertThat(f.events).containsExactly("begin","commit");
    }
    @ParameterizedTest @ValueSource(strings={"publish","recheck"})
    void given__a_selection_changed_during_external_lookup__when__publishing_or_rechecking__then__reject_the_stale_metadata_after_lock(String operation){
        var f=new Fixture();when(f.repo.list()).thenReturn(List.of(video(ID,VIDEO,false,NOW)),List.of(video(ID,"OtherVideo1",false,NOW)));
        assertThatThrownBy(()->{if(operation.equals("publish"))f.service.publish(ID,new Publication(true,5));else f.service.recheck(ID,5);})
                .isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(409));
        verify(f.repo,never()).metadata(any(),any(),any());verify(f.repo,never()).changed();assertThat(f.events).containsExactly("lookup","begin","rollback");
    }
    @Test void given__all_published_ids_in_a_new_order__when__reordering__then__retain_hidden_items_behind_the_exact_published_set(){
        var f=new Fixture();UUID hidden=new UUID(0,3);when(f.repo.list()).thenReturn(List.of(video(ID,VIDEO,true,NOW),video(OTHER,"OtherVideo1",true,NOW),video(hidden,"hidden",false,NOW)));
        f.service.reorder(new Order(List.of(OTHER,ID),5));var order=inOrder(f.repo);
        order.verify(f.repo).lockBoard();order.verify(f.repo).list();order.verify(f.repo).position(OTHER,0);order.verify(f.repo).position(ID,1);order.verify(f.repo).list();order.verify(f.repo).position(hidden,2);order.verify(f.repo).changed();
        assertThat(f.events).containsExactly("begin","commit");verifyNoInteractions(f.youtube);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void given__duplicate_or_incomplete_published_ids__when__reordering__then__reject_without_partial_positions(boolean duplicate){
        var f=new Fixture();when(f.repo.list()).thenReturn(List.of(video(ID,VIDEO,true,NOW),video(OTHER,"OtherVideo1",true,NOW)));
        assertThatThrownBy(()->f.service.reorder(new Order(duplicate?List.of(ID,ID):List.of(ID),5))).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(409));
        verify(f.repo,never()).position(any(),anyInt());verify(f.repo,never()).changed();assertThat(f.events).containsExactly("begin","rollback");
    }
    @Test void given__provider_reports_the_selected_id_absent__when__rechecking__then__clear_metadata_without_changing_selection_or_publication(){
        var f=new Fixture();when(f.repo.list()).thenReturn(List.of(video(ID,VIDEO,true,NOW)));doReturn(Map.of()).when(f.youtube).lookup(anyList());
        f.service.recheck(ID,5);verify(f.repo).metadata(ID,null,NOW);verify(f.repo,never()).selection(any(),anyString(),anyBoolean());verify(f.repo,never()).publication(any(),anyBoolean());verify(f.repo).changed();
    }
    @Test void given__expired_metadata_and_provider_failure__when__refreshing__then__commit_expiry_before_external_failure(){
        var f=new Fixture();when(f.repo.expire(NOW.minus(Duration.ofDays(29)))).thenReturn(1);when(f.repo.list()).thenReturn(List.of(video(ID,VIDEO,true,null)));
        doAnswer(call->{f.events.add("lookup");throw new IllegalStateException("synthetic-provider");}).when(f.youtube).lookup(anyList());
        assertThatThrownBy(f.service::refresh).isInstanceOf(IllegalStateException.class);verify(f.repo).changed();verify(f.repo,never()).metadata(any(),any(),any());
        assertThat(f.events).containsExactly("begin","commit","lookup");
    }
    @Test void given__daily_boundary_and_stale_snapshot__when__refreshing__then__exclude_exact_day_and_update_only_unchanged_selected_rows(){
        var f=new Fixture();var unchecked=video(ID,VIDEO,true,null);var old=video(OTHER,"OtherVideo1",true,NOW.minus(Duration.ofDays(2)));var boundary=video(new UUID(0,3),"AtBoundary1",true,NOW.minus(Duration.ofDays(1)));
        when(f.repo.list()).thenReturn(List.of(unchecked,old,boundary),List.of(unchecked,video(OTHER,"OtherVideo1",true,NOW),boundary));
        f.service.refresh();verify(f.youtube).lookup(List.of(VIDEO,"OtherVideo1"));verify(f.repo).metadata(ID,METADATA,NOW);verify(f.repo,never()).metadata(eq(OTHER),any(),any());verify(f.repo).changed();
        assertThat(f.events).containsExactly("begin","commit","lookup","begin","commit");
    }
    private static final class Fixture {
        final List<String> events=new ArrayList<>();final Manager manager=new Manager(events);
        final JdbcHomeVideoRepository repo=mock(JdbcHomeVideoRepository.class);final YouTubeVideoClient youtube=mock(YouTubeVideoClient.class);final Clock clock=mock(Clock.class);
        final JdbcHomeVideoManagement service;
        Fixture(){when(clock.instant()).thenReturn(NOW);when(repo.lockBoard()).thenReturn(5L);when(repo.revision()).thenReturn(5L);when(repo.list()).thenReturn(List.of());
            when(youtube.lookup(anyList())).thenAnswer(call->{events.add("lookup");return Map.of(VIDEO,METADATA);});service=new JdbcHomeVideoManagement(repo,youtube,clock,new TransactionTemplate(manager));}
    }
    private static final class Manager extends AbstractPlatformTransactionManager {
        final List<String> events;int timeout;Manager(List<String> events){this.events=events;}
        protected Object doGetTransaction(){return new Object();}
        protected void doBegin(Object transaction,TransactionDefinition definition){timeout=definition.getTimeout();events.add("begin");}
        protected void doCommit(DefaultTransactionStatus status){events.add("commit");}
        protected void doRollback(DefaultTransactionStatus status){events.add("rollback");}
    }
}
