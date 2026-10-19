package com.pawbridge.backend.community.internal.note.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pawbridge.backend.community.internal.note.application.NoteNotifications.Notification;
import com.pawbridge.backend.community.internal.note.application.NoteSendRejectedException;
import com.pawbridge.backend.community.internal.note.application.NoteSendStore;
import com.pawbridge.backend.community.internal.note.application.SendPrivateNote.Receipt;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcNoteSendStoreTest {
    private static final UUID NOTE=UUID.fromString("f2900000-0000-0000-0000-000000000001"),REQUEST=UUID.fromString("f2900000-0000-0000-0000-000000000002"),PARENT=UUID.fromString("f2900000-0000-0000-0000-000000000003");
    private static final Instant NOW=Instant.parse("2028-02-29T15:00:00.123456789Z"),EXPIRY=Instant.parse("2029-02-28T15:00:00.123456789Z");
    private static final String MEMBER_INSERT="INSERT INTO pawbridge_community.private_note_members(member_id) VALUES (?) ON CONFLICT DO NOTHING";
    private static final String MEMBER_LOCK="SELECT member_id FROM pawbridge_community.private_note_members WHERE member_id = ? FOR UPDATE";
    private static final String NOTE_INSERT="INSERT INTO pawbridge_community.private_notes( note_id, sender_id, recipient_id, body, reply_to, context_type, context_id, created_at, expires_at ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String MAILBOX_INSERT="INSERT INTO pawbridge_community.private_note_mailboxes( member_id, note_id, direction, read_at, created_at ) VALUES (?, ?, 'SENT', ?, ?), (?, ?, 'INBOX', NULL, ?)";
    private static final String REQUEST_INSERT="INSERT INTO pawbridge_community.private_note_requests( sender_id, request_id, request_hash, note_id, created_at ) VALUES (?, ?, ?, ?, ?)";
    private static final Notification HINT=new Notification(NOTE,"PRIVATE_NOTE",7L," raw ",NOW,false,"/notes/"+NOTE);
    private static final NoteSendStore.NewNote NEW_NOTE=new NoteSendStore.NewNote(NOTE,7L,8L,"raw body\n",PARENT,"REPORT",17L,REQUEST,"private-hash",NOW,EXPIRY);

    @ParameterizedTest @CsvSource({"9,3","3,3"})
    void given__reversed_or_same_member_pair__when__locking_in_the_callback__then__insert_and_lock_each_distinct_id_in_ascending_order(long first,long second){
        var f=new Fixture();
        assertEquals(new Receipt(NOTE),f.store.inTransaction(()->{f.store.lockMembers(first,second);return new Receipt(NOTE);}));
        List<Long>expected=first==second?List.of(3L):List.of(3L,9L);
        assertEquals(expected.size()*2,f.jdbc.calls.size());
        for(int index=0;index<expected.size();index++){
            assertEquals(MEMBER_INSERT,f.jdbc.calls.get(index*2).sql());assertEquals(MEMBER_LOCK,f.jdbc.calls.get(index*2+1).sql());
            assertArrayEquals(new Object[]{expected.get(index)},f.jdbc.calls.get(index*2).arguments());
            assertArrayEquals(new Object[]{expected.get(index)},f.jdbc.calls.get(index*2+1).arguments());
        }
        assertEquals(1,f.manager.commits);assertEquals(0,f.manager.rollbacks);assertTrue(f.published.isEmpty());f.returned();
    }

    @ParameterizedTest @ValueSource(strings={"present","null","absent"})
    void given__normal_nullable_or_absent_rows__when__reading_send_facts__then__keep_raw_projection_boundaries_direction_and_inclusive_parameterized_sql(String rows){
        var f=new Fixture();f.jdbc.rowKind=rows;
        f.store.inTransaction(()->{
            assertEquals(rows.equals("absent")?Optional.empty():Optional.of(new NoteSendStore.PreviousRequest(rows.equals("null")?null:NOTE,rows.equals("null")?null:"stored-hash")),f.store.request(7L,REQUEST));
            assertTrue(f.store.blocked(7L,8L));assertEquals(9L,f.store.rate(7L,NOW.minusSeconds(60)));
            assertEquals(rows.equals("absent")?Optional.empty():Optional.of(new NoteSendStore.Reply("INBOX",rows.equals("null")?null:8L)),f.store.reply(7L,PARENT,NOW));
            assertTrue(f.store.contextAuthor("POST",3L,8L));assertTrue(f.store.contextAuthor("REPORT",4L,8L));return new Receipt(NOTE);
        });
        assertEquals(6,f.jdbc.calls.size());
        assertCall(f,0,"SELECT note_id, request_hash FROM pawbridge_community.private_note_requests WHERE sender_id = ? AND request_id = ?",7L,REQUEST);
        assertCall(f,1,"SELECT EXISTS( SELECT 1 FROM pawbridge_community.private_note_blocks WHERE (member_id = ? AND blocked_id = ?) OR (member_id = ? AND blocked_id = ?) )",7L,8L,8L,7L);
        assertCall(f,2,"SELECT count(*) FROM pawbridge_community.private_note_requests WHERE sender_id = ? AND created_at >= ?",7L,Timestamp.from(NOW.minusSeconds(60)));
        assertCall(f,3,"SELECT m.direction, n.sender_id FROM pawbridge_community.private_note_mailboxes m JOIN pawbridge_community.private_notes n ON n.note_id = m.note_id WHERE m.member_id = ? AND n.note_id = ? AND n.expires_at > ?",7L,PARENT,Timestamp.from(NOW));
        assertCall(f,4,"SELECT EXISTS( SELECT 1 FROM pawbridge_community.posts WHERE post_id = ? AND author_id = ? AND deleted_at IS NULL )",3L,8L);
        assertCall(f,5,"SELECT EXISTS( SELECT 1 FROM pawbridge_community.animal_reports WHERE report_id = ? AND author_id = ? AND deleted_at IS NULL )",4L,8L);
        assertTrue(f.published.isEmpty());f.returned();
    }

    @Test
    void given__new_note_and_notification__when__the_callback_inserts__then__bind_three_statements_and_publish_only_recipient_after_commit(){
        var f=new Fixture();assertEquals(new Receipt(NOTE),f.insert());
        assertEquals(List.of("begin","callback","note","mailboxes","request","commit","publish:8","cleanup"),f.events);
        assertEquals(3,f.jdbc.calls.size());
        assertCall(f,0,NOTE_INSERT,NOTE,7L,8L,"raw body\n",PARENT,"REPORT",17L,Timestamp.from(NOW),Timestamp.from(EXPIRY));
        assertCall(f,1,MAILBOX_INSERT,7L,NOTE,Timestamp.from(NOW),Timestamp.from(NOW),8L,NOTE,Timestamp.from(NOW));
        assertCall(f,2,REQUEST_INSERT,7L,REQUEST,"private-hash",NOTE,Timestamp.from(NOW));
        assertEquals(List.of(8L),f.published);assertSame(HINT,f.notification);assertEquals(1,f.manager.commits);assertEquals(0,f.manager.rollbacks);f.returned();
    }

    @ParameterizedTest @EnumSource(Failure.class)
    void given__begin_callback_any_insert_or_commit_failure__when__running_send__then__propagate_without_retry_or_publication_and_release_synchronization(Failure phase){
        var f=new Fixture();RuntimeException failure=switch(phase){
            case BEGIN->new CannotCreateTransactionException("private begin");
            case CALLBACK->new NoteSendRejectedException(NoteSendRejectedException.Reason.BLOCKED);
            case COMMIT->new TransactionSystemException("private commit");
            default->new DataAccessResourceFailureException("private SQL");};
        switch(phase){case BEGIN->f.manager.beginFailure=failure;case COMMIT->f.manager.commitFailure=failure;
            case CALLBACK->f.callbackFailure=failure;default->{f.jdbc.failEvent=switch(phase){case NOTE->"note";case MAILBOXES->"mailboxes";case REQUEST->"request";default->throw new AssertionError();};f.jdbc.failure=failure;}}
        assertSame(failure,assertThrows(RuntimeException.class,f::insert));
        List<String>stages=List.of("begin","callback","note","mailboxes","request","commit");
        int last=switch(phase){case BEGIN->0;case CALLBACK->1;case NOTE->2;case MAILBOXES->3;case REQUEST->4;case COMMIT->5;};
        var expected=new ArrayList<>(stages.subList(0,last+1));if(phase!=Failure.BEGIN&&phase!=Failure.COMMIT)expected.add("rollback");if(phase!=Failure.BEGIN)expected.add("cleanup");
        assertEquals(expected,f.events);assertTrue(f.published.isEmpty());
        assertEquals(phase==Failure.COMMIT?1:0,f.manager.commits);assertEquals(phase==Failure.BEGIN||phase==Failure.COMMIT?0:1,f.manager.rollbacks);f.returned();
    }

    @Test
    void given__private_publication_runtime_after_commit__when__sending_the_hint__then__keep_saved_success_and_log_only_the_type_without_throwable(){
        var f=new Fixture();f.hintFailure=new IllegalStateException("Bearer private-token body hash",new SQLException("private SQL"));
        Logger logger=(Logger)LoggerFactory.getLogger(JdbcNoteSendStore.class);var appender=new ListAppender<ILoggingEvent>();appender.start();logger.addAppender(appender);
        try{
            assertEquals(new Receipt(NOTE),f.insert());assertEquals(List.of(8L),f.published);assertEquals(1,f.manager.commits);assertEquals(0,f.manager.rollbacks);
            assertEquals(1,appender.list.size());var event=appender.list.getFirst();
            assertEquals("Private note push failed (IllegalStateException); REST restoration remains available",event.getFormattedMessage());
            assertNull(event.getThrowableProxy());assertArrayEquals(new Object[]{"IllegalStateException"},event.getArgumentArray());f.returned();
        }finally{logger.detachAppender(appender);appender.stop();}
    }

    private static void assertCall(Fixture f,int index,String sql,Object...arguments){assertEquals(sql,f.jdbc.calls.get(index).sql());assertArrayEquals(arguments,f.jdbc.calls.get(index).arguments());}
    private enum Failure{BEGIN,CALLBACK,NOTE,MAILBOXES,REQUEST,COMMIT}
    private record Call(String sql,Object[]arguments){}
    /** Real Spring synchronization, recording JDBC/commit: not actual rollback, locks, FK or SSE evidence. */
    private static final class Fixture{
        final List<String>events=new ArrayList<>();final List<Long>published=new ArrayList<>();final Manager manager=new Manager(events);final RecordingJdbc jdbc=new RecordingJdbc(manager,events);final JdbcNoteSendStore store;
        RuntimeException callbackFailure,hintFailure;Notification notification;
        Fixture(){var tx=new TransactionTemplate(manager);tx.setTimeout(15);store=new JdbcNoteSendStore(jdbc,tx,(member,hint)->{assertEquals(1,manager.commits);assertEquals("commit",events.getLast());events.add("publish:"+member);published.add(member);notification=hint;if(hintFailure!=null)throw hintFailure;});}
        Receipt insert(){return store.inTransaction(()->{events.add("callback");if(callbackFailure!=null)throw callbackFailure;store.insert(NEW_NOTE,HINT);assertTrue(manager.active);assertTrue(published.isEmpty());return new Receipt(NOTE);});}
        void returned(){assertFalse(manager.active);assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertFalse(TransactionSynchronizationManager.isSynchronizationActive());}
    }
    private static final class RecordingJdbc extends JdbcTemplate{
        final Manager manager;final List<String>events;final List<Call>calls=new ArrayList<>();String rowKind="present",failEvent;RuntimeException failure;
        RecordingJdbc(Manager manager,List<String>events){this.manager=manager;this.events=events;}
        @Override public int update(String sql,Object...args){String normalized=normalize(sql);String event=normalized.startsWith("INSERT INTO pawbridge_community.private_note_members")?"member-insert":normalized.startsWith("INSERT INTO pawbridge_community.private_notes(")?"note":normalized.startsWith("INSERT INTO pawbridge_community.private_note_mailboxes(")?"mailboxes":"request";record(sql,args,event);return event.equals("mailboxes")?2:1;}
        @Override public <T>T queryForObject(String sql,Class<T>type,Object...args){String normalized=normalize(sql);record(sql,args,"query");if(type==Boolean.class)return type.cast(Boolean.TRUE);if(normalized.contains("count(*)"))return type.cast(9L);return type.cast(args[0]);}
        @Override public <T>List<T>query(String sql,RowMapper<T>mapper,Object...args){record(sql,args,"query");if(rowKind.equals("absent"))return List.of();
            try{ResultSet row=mock(ResultSet.class);when(row.getObject("note_id",UUID.class)).thenReturn(rowKind.equals("null")?null:NOTE);when(row.getString("request_hash")).thenReturn(rowKind.equals("null")?null:"stored-hash");when(row.getString("direction")).thenReturn("INBOX");when(row.getObject("sender_id")).thenReturn(rowKind.equals("null")?null:8L);return List.of(mapper.mapRow(row,0));}
            catch(SQLException failure){throw new AssertionError("Recording mapper failed",failure);}}
        void record(String sql,Object[]args,String event){assertTrue(manager.active);calls.add(new Call(normalize(sql),args.clone()));events.add(event);if(event.equals(failEvent))throw failure;}
        static String normalize(String sql){return sql.replaceAll("\\s+"," ").strip();}
    }
    private static final class Manager extends AbstractPlatformTransactionManager{
        final List<String>events;boolean active;int commits,rollbacks;RuntimeException beginFailure,commitFailure;
        Manager(List<String>events){this.events=events;}
        @Override protected Object doGetTransaction(){return new Object();}
        @Override protected void doBegin(Object transaction,TransactionDefinition definition){assertEquals(15,definition.getTimeout());assertFalse(definition.isReadOnly());events.add("begin");if(beginFailure!=null)throw beginFailure;active=true;}
        @Override protected void doCommit(DefaultTransactionStatus status){events.add("commit");commits++;active=false;if(commitFailure!=null)throw commitFailure;}
        @Override protected void doRollback(DefaultTransactionStatus status){events.add("rollback");rollbacks++;active=false;}
        @Override protected void doCleanupAfterCompletion(Object transaction){active=false;events.add("cleanup");}
    }
}
