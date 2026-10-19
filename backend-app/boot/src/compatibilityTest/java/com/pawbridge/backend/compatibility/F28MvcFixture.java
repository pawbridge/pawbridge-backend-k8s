package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.community.internal.note.application.DeletePrivateNote;
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteDeletion;
import com.pawbridge.backend.community.internal.note.web.NoteDeletionController;
import com.pawbridge.backend.community.internal.note.web.NoteDeletionExceptionHandler;
import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import com.pawbridge.communityservice.client.UserServiceClient;
import com.pawbridge.communityservice.contact.F28LegacyNoteDeletionController;
import com.pawbridge.communityservice.contact.F28LegacyNoteDeletionExceptionAdvice;
import com.pawbridge.communityservice.contact.F28LegacyNoteDeletionService;
import com.pawbridge.communityservice.contact.PrivateNoteHttpBoundary;
import com.pawbridge.communityservice.contact.PrivateNoteModels;
import com.pawbridge.communityservice.contact.PrivateNoteRepository;
import com.pawbridge.communityservice.contact.PrivateNoteStream;
import com.pawbridge.communityservice.exception.common.GlobalExceptionRestAdvice;
import feign.FeignException;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** Selected real MVC/service/repository code with recording JDBC/TM. No PG/FK/lock/JpaTM/JWT/socket proof. */
final class F28MvcFixture {
    static final UUID NOTE = UUID.fromString("f2800000-0000-0000-0000-000000000001");
    static final Instant NOW = Instant.parse("2031-01-01T00:00:00.123456Z");
    final Side source = new Side(), adapted = new Side();
    final UserServiceClient client = mock(UserServiceClient.class);
    final ContactMemberQuery members = mock(ContactMemberQuery.class);
    final PrivateNoteStream sourceStream = mock(PrivateNoteStream.class); // Original constructor/executors never run.
    final F28LegacyNoteDeletionService original;
    final DeletePrivateNote candidate;
    boolean inactive, memberFailure, hintFailure;
    int sourceReached, candidateReached;
    UUID sourceId, candidateId;

    F28MvcFixture() {
        when(client.getContactMember(anyLong())).thenAnswer(call -> {
            long id = call.getArgument(0); source.phases.add("current:" + id);
            if (memberFailure) { var failure = mock(FeignException.class); when(failure.status()).thenReturn(500); throw failure; }
            return new PrivateNoteModels.ContactMember(id, " raw ", !inactive, false);
        });
        when(members.get(anyLong())).thenAnswer(call -> {
            long id = call.getArgument(0); adapted.phases.add("current:" + id);
            if (memberFailure) throw new ContactMemberUnavailableException(new IllegalStateException("private-member-detail"));
            return new ContactMember(id, " raw ", !inactive, false);
        });
        doAnswer(call -> { hint(source, call.getArgument(0, Long.class)); return null; }).when(sourceStream).resync(anyLong());
        original = spy(new F28LegacyNoteDeletionService(new PrivateNoteRepository(source.jdbc), client,
                source.template, source.clock, sourceStream));
        candidate = spy(new DeletePrivateNote(members,
                new JdbcNoteDeletion(adapted.jdbc, adapted.template, id -> hint(adapted, id)), adapted.clock));
        doAnswer(call -> { sourceReached++; sourceId = call.getArgument(1); call.callRealMethod(); return null; })
                .when(original).delete(anyLong(), any(UUID.class));
        doAnswer(call -> { candidateReached++; candidateId = call.getArgument(1); call.callRealMethod(); return null; })
                .when(candidate).delete(anyLong(), any(UUID.class));
    }
    private void hint(Side side, long id) {
        assertThat(id).isEqualTo(7L);
        assertThat(side.committed).as("doCommit marker precedes hint; Spring resources may remain bound").isTrue();
        side.phases.add("hint:" + id);
        if (hintFailure) throw new IllegalStateException("private-hint-detail");
    }
    void direction(String value) { source.direction = adapted.direction = value; }
    void missing() { source.ownMailbox = adapted.ownMailbox = false; }
    void fail(String value) { source.failure = adapted.failure = value; }
    MockMvc sourceMvc(ObjectMapper mapper) {
        return MockMvcBuilders.standaloneSetup(new F28LegacyNoteDeletionController(original))
                .setControllerAdvice(new F28LegacyNoteDeletionExceptionAdvice(), new GlobalExceptionRestAdvice())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .addFilters(new PrivateNoteHttpBoundary()).build();
    }
    MockMvc candidateMvc(ObjectMapper mapper) {
        return MockMvcBuilders.standaloneSetup(new NoteDeletionController(candidate))
                .setControllerAdvice(new NoteDeletionExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }
    MvcResult remove(MockMvc mvc, String path, boolean candidateSide) throws Exception {
        var request = delete("/api/v1/notes/" + path).header("X-User-Id", "7").accept(MediaType.APPLICATION_JSON);
        if (candidateSide) {
            var auth = new UsernamePasswordAuthenticationToken(7L, null, List.of());
            SecurityContextHolder.getContext().setAuthentication(auth); request.principal(auth);
        }
        return mvc.perform(request).andReturn();
    }
    static String body(MvcResult result) throws Exception { return result.getResponse().getContentAsString(StandardCharsets.UTF_8); }
    static void cache(MvcResult result) {
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
    }
    void agree() {
        assertThat(adapted.phases).containsExactlyElementsOf(source.phases);
        assertThat(adapted.clock.calls).isEqualTo(source.clock.calls);
        assertThat(adapted.jdbc.arguments).containsExactlyElementsOf(source.jdbc.arguments);
        assertThat(adapted.jdbc.calls).isEqualTo(source.jdbc.calls);
        assertThat(adapted.jdbc.deletes).isEqualTo(source.jdbc.deletes);
        assertThat(adapted.ownMailbox).isEqualTo(source.ownMailbox); assertThat(adapted.noteExists).isEqualTo(source.noteExists);
    }
    static Map<String,Object> observation(MvcResult result, Side side, int reached, UUID id) throws Exception {
        var v = new LinkedHashMap<String,Object>();
        v.put("status",result.getResponse().getStatus()); v.put("body",body(result));
        v.put("cacheControl",result.getResponse().getHeader("Cache-Control")); v.put("setCookieCount",result.getResponse().getHeaders("Set-Cookie").size());
        v.put("contentType",result.getResponse().getContentType()); v.put("async",result.getRequest().isAsyncStarted());
        v.put("serviceReached",reached); v.put("boundId",id==null?null:id.toString()); v.put("phases",List.copyOf(side.phases));
        v.put("clockCalls",side.clock.calls); v.put("sqlCalls",side.jdbc.calls); v.put("deleteCalls",side.jdbc.deletes);
        v.put("ownMailboxAfter",side.ownMailbox); v.put("noteExistsAfter",side.noteExists);
        return v;
    }
    static final class Side {
        final List<String> phases = new ArrayList<>();
        final RecordingJdbc jdbc = new RecordingJdbc(this);
        final TransactionTemplate template = new TransactionTemplate(new RecordingTm(this));
        final SampleClock clock = new SampleClock(this);
        String direction="INBOX", failure=""; boolean ownMailbox=true,noteExists=true,committed;
        Side(){template.setTimeout(15);}
    }
    static final class SampleClock extends Clock {
        final Side side; int calls; SampleClock(Side side){this.side=side;}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId ignored){return this;}
        @Override public Instant instant(){side.phases.add("clock");return NOW.plusSeconds(calls++);}
    }
    static final class RecordingTm extends AbstractPlatformTransactionManager {
        final Side side; boolean mailbox,note; RecordingTm(Side side){this.side=side;}
        @Override protected Object doGetTransaction(){return new Object();}
        @Override protected void doBegin(Object transaction,TransactionDefinition definition){
            side.phases.add("begin");assertThat(definition.getTimeout()).isEqualTo(15);assertThat(definition.isReadOnly()).isFalse();
            if(side.failure.equals("BEGIN"))throw new CannotCreateTransactionException("private-tx-detail");
            mailbox=side.ownMailbox;note=side.noteExists;side.committed=false;
        }
        @Override protected void doCommit(DefaultTransactionStatus status){side.phases.add("commit");side.committed=true;}
        @Override protected void doRollback(DefaultTransactionStatus status){side.phases.add("rollback");side.ownMailbox=mailbox;side.noteExists=note;}
    }
    static final class RecordingJdbc extends JdbcTemplate {
        final Side side; final List<List<Object>> arguments=new ArrayList<>(); int calls,deletes;
        RecordingJdbc(Side side){this.side=side;}
        @Override public int update(String sql,Object...args){
            calls++;arguments.add(Arrays.asList(args.clone()));String s=sql.replaceAll("\\s+"," ").strip();
            if(s.startsWith("INSERT INTO pawbridge_community.private_note_members")){side.phases.add("insert:"+args[0]);return 1;}
            deletes++;
            if(s.equals("DELETE FROM pawbridge_community.private_note_mailboxes WHERE member_id = ? AND note_id = ?")){
                side.phases.add("mailbox-delete");side.ownMailbox=false;return 1;
            }
            assertThat(s).isEqualTo("DELETE FROM pawbridge_community.private_notes n WHERE note_id = ? AND NOT EXISTS( SELECT 1 FROM pawbridge_community.private_note_mailboxes m WHERE m.note_id = n.note_id )");
            side.phases.add("note-delete");if(side.failure.equals("DAO"))throw new DataRetrievalFailureException("private-sql-detail");
            side.noteExists=false;return 1;
        }
        @Override public <T>T queryForObject(String sql,Class<T>type,Object...args){calls++;arguments.add(Arrays.asList(args.clone()));side.phases.add("member-lock:"+args[0]);return type.cast(args[0]);}
        @Override public <T>List<T>query(String sql,RowMapper<T>mapper,Object...args){
            calls++;arguments.add(Arrays.asList(args.clone()));boolean lock=sql.contains("FOR UPDATE");side.phases.add(lock?"note-lock":"find");
            if(!side.noteExists||!lock&&!side.ownMailbox)return List.of();
            try{return List.of(mapper.mapRow(row(),0));}catch(SQLException failure){throw new IllegalStateException(failure);}
        }
        private ResultSet row()throws SQLException{
            var r=mock(ResultSet.class);when(r.getObject("note_id",UUID.class)).thenReturn(NOTE);when(r.getObject(1,UUID.class)).thenReturn(NOTE);
            when(r.getObject("sender_id")).thenReturn(8L);when(r.getObject("recipient_id")).thenReturn(7L);
            when(r.getString("body")).thenReturn("private-body-not-logged");when(r.getString("direction")).thenReturn(side.direction);
            when(r.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW.minusSeconds(60)));when(r.getTimestamp("expires_at")).thenReturn(Timestamp.from(NOW.plusSeconds(120)));
            when(r.getTimestamp("read_at")).thenReturn(Timestamp.from(NOW.minusSeconds(5)));when(r.getBoolean("favorite")).thenReturn(true);return r;
        }
    }
}
