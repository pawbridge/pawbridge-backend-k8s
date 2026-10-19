package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.community.internal.note.application.*;
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteReadMutation;
import com.pawbridge.backend.community.internal.note.web.*;
import com.pawbridge.backend.user.api.*;
import com.pawbridge.communityservice.client.UserServiceClient;
import com.pawbridge.communityservice.contact.*;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Recording SQL and Spring synchronizations only: no database, actual JpaTM, JWT validation or real SSE socket. */
final class F26MvcFixture {
    static final UUID NOTE = UUID.fromString("00000000-0000-0000-0000-000099800101");
    static final UUID CLIENT = UUID.fromString("00000000-0000-0000-0000-000099800102");
    static final Instant NOW = Instant.parse("2031-01-01T00:00:00.123456Z");
    static final Instant EXPIRES = NOW.plusSeconds(120);
    static final Instant STREAM_EXPIRES = Instant.ofEpochMilli(EXPIRES.toEpochMilli());
    final Side source = new Side(), adapted = new Side();
    final UserServiceClient client = mock(UserServiceClient.class);
    final ContactMemberQuery members = mock(ContactMemberQuery.class);
    final PrivateNoteStream sourceStream = mock(PrivateNoteStream.class);
    final PrivateNoteStreamRegistry streams = mock(PrivateNoteStreamRegistry.class);
    final F26LegacyNoteReadService original;
    final MarkPrivateNoteRead candidate;
    final List<SseEmitter> emitters = new ArrayList<>();
    boolean inactive, memberFailure, hintFailure;
    int sourceReadReached, candidateReadReached;
    UUID sourceReadId, candidateReadId;
    final List<UUID> sourceClientIds = new ArrayList<>(), candidateClientIds = new ArrayList<>();

    F26MvcFixture() {
        when(client.getContactMember(anyLong())).thenAnswer(call -> {
            long id=call.getArgument(0);source.phases.add("current:"+id);
            if(memberFailure){var e=mock(FeignException.class);when(e.status()).thenReturn(503);throw e;}
            return new PrivateNoteModels.ContactMember(id," raw ",!inactive,false);
        });
        when(members.get(anyLong())).thenAnswer(call -> {
            long id=call.getArgument(0);adapted.phases.add("current:"+id);
            if(memberFailure)throw new ContactMemberUnavailableException(new IllegalStateException("private-member-detail"));
            return new ContactMember(id," raw ",!inactive,false);
        });
        doAnswer(call->{hint(source);return null;}).when(sourceStream).resync(anyLong());
        original=spy(new F26LegacyNoteReadService(new PrivateNoteRepository(source.jdbc),client,source.template,source.clock,sourceStream));
        candidate=spy(new MarkPrivateNoteRead(members,new JdbcNoteReadMutation(adapted.jdbc,adapted.template,id->hint(adapted)),adapted.clock));
        doAnswer(call->{sourceReadReached++;sourceReadId=call.getArgument(1);call.callRealMethod();return null;}).when(original).read(anyLong(),any(UUID.class));
        doAnswer(call->{candidateReadReached++;candidateReadId=call.getArgument(1);call.callRealMethod();return null;}).when(candidate).read(anyLong(),any(UUID.class));
        when(sourceStream.open(anyLong(),any(Instant.class))).thenAnswer(call->opened(sourceClientIds,call.getArgument(0,Long.class),call.getArgument(1,Instant.class),null));
        when(sourceStream.open(anyLong(),any(Instant.class),any(UUID.class))).thenAnswer(call->opened(sourceClientIds,call.getArgument(0,Long.class),call.getArgument(1,Instant.class),call.getArgument(2,UUID.class)));
        when(streams.open(anyLong(),any(Instant.class))).thenAnswer(call->opened(candidateClientIds,call.getArgument(0,Long.class),call.getArgument(1,Instant.class),null));
        when(streams.open(anyLong(),any(Instant.class),any(UUID.class))).thenAnswer(call->opened(candidateClientIds,call.getArgument(0,Long.class),call.getArgument(1,Instant.class),call.getArgument(2,UUID.class)));
    }
    private SseEmitter opened(List<UUID> ids,long current,Instant expiry,UUID clientId)throws java.io.IOException{assertThat(current).isEqualTo(7L);assertThat(expiry).isEqualTo(STREAM_EXPIRES);ids.add(clientId);return emitter();}
    private SseEmitter emitter()throws java.io.IOException{
        var value=new SseEmitter(1000L);emitters.add(value);
        // Controlled initial write matches original ready format; not registry, executor or socket evidence.
        value.send(SseEmitter.event().name("ready").data("{}"));return value;
    }
    private void hint(Side side){assertThat(side.committed).as("Spring synchronization must run after doCommit").isTrue();side.phases.add("hint:7");if(hintFailure)throw new IllegalStateException("private-hint-detail");}
    void setDirection(String value){source.direction=adapted.direction=value;if("SENT".equals(value))source.readAt=adapted.readAt=NOW;}
    void setReadAt(Instant value){source.readAt=adapted.readAt=value;}
    void missing(){source.exists=adapted.exists=false;}
    void fail(String phase){source.failure=adapted.failure=phase;}
    MockMvc sourceMvc(ObjectMapper mapper){return MockMvcBuilders.standaloneSetup(new F26LegacyNoteReadController(original,sourceStream))
            .setControllerAdvice(new F26LegacyNoteReadExceptionAdvice(),new GlobalExceptionRestAdvice())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).addFilters(new PrivateNoteHttpBoundary()).build();}
    MockMvc candidateMvc(ObjectMapper mapper){return MockMvcBuilders.standaloneSetup(new NoteReadMutationController(candidate),new NoteStreamController(new RequireActiveNoteStream(members),streams))
            .setControllerAdvice(new NoteReadMutationExceptionHandler(),new NoteStreamExceptionHandler())
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();}
    static Authentication authentication(){var auth=new UsernamePasswordAuthenticationToken(7L,null,List.of());auth.setDetails(STREAM_EXPIRES);SecurityContextHolder.getContext().setAuthentication(auth);return auth;}
    MvcResult read(MockMvc mvc,String path,boolean candidateSide)throws Exception{
        var request=put("/api/v1/notes/"+path+"/read").header("X-User-Id","7");if(candidateSide)request.principal(authentication());return mvc.perform(request).andReturn();}
    MvcResult stream(MockMvc mvc,String clientId,String accept,String expiryHeader,boolean candidateSide)throws Exception{
        MockHttpServletRequestBuilder request=get("/api/v1/notes/stream").header("X-User-Id","7").header("X-Auth-Expires-At",expiryHeader).accept(accept);
        if(clientId!=null)request.param("clientId",clientId);if(candidateSide)request.principal(authentication());return mvc.perform(request).andReturn();}
    void closeStream(MockMvc mvc,MvcResult result)throws Exception{
        if(result.getRequest().isAsyncStarted()){
            // Plain emitters are deterministic MVC async fixtures, not registry/transport lifecycle evidence.
            for(SseEmitter emitter:emitters)emitter.complete();result.getAsyncResult(1000);mvc.perform(asyncDispatch(result)).andReturn();
        }
    }
    static String body(MvcResult result)throws Exception{return result.getResponse().getContentAsString(StandardCharsets.UTF_8);}
    static void cache(MvcResult result){assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();}
    void agree(){assertThat(adapted.phases).containsExactlyElementsOf(source.phases);assertThat(adapted.clock.calls).isEqualTo(source.clock.calls);assertThat(adapted.jdbc.calls).isEqualTo(source.jdbc.calls);assertThat(adapted.jdbc.arguments).containsExactlyElementsOf(source.jdbc.arguments);assertThat(adapted.jdbc.updates).isEqualTo(source.jdbc.updates);assertThat(adapted.readAt).isEqualTo(source.readAt);}
    static Map<String,Object> observation(MvcResult result,Side side,int reached,UUID bound,List<UUID> opened)throws Exception{
        var value=new LinkedHashMap<String,Object>();value.put("status",result.getResponse().getStatus());value.put("body",body(result));value.put("cacheControl",result.getResponse().getHeader("Cache-Control"));value.put("setCookieCount",result.getResponse().getHeaders("Set-Cookie").size());value.put("async",result.getRequest().isAsyncStarted());value.put("contentType",result.getResponse().getContentType());value.put("buffering",result.getResponse().getHeader("X-Accel-Buffering"));value.put("readReached",reached);value.put("boundId",bound==null?null:bound.toString());value.put("openedClientIds",opened.stream().map(id->id==null?null:id.toString()).toList());value.put("phases",List.copyOf(side.phases));value.put("clockCalls",side.clock.calls);value.put("sqlCalls",side.jdbc.calls);value.put("updateCalls",side.jdbc.updates);return value;
    }
    static final class Side {
        final List<String> phases=new ArrayList<>();final RecordingJdbc jdbc=new RecordingJdbc(this);final RecordingTm manager=new RecordingTm(this);final TransactionTemplate template=new TransactionTemplate(manager);final SampleClock clock=new SampleClock(this);
        String direction="INBOX",failure="";Instant readAt;boolean exists=true,committed;Side(){template.setTimeout(15);}
    }
    static final class SampleClock extends Clock {
        final Side side;int calls;SampleClock(Side side){this.side=side;}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId ignored){return this;}
        @Override public Instant instant(){calls++;side.phases.add("clock");return NOW;}
    }
    static final class RecordingTm extends AbstractPlatformTransactionManager {
        final Side side;Instant before;RecordingTm(Side side){this.side=side;}
        @Override protected Object doGetTransaction(){return new Object();}
        @Override protected void doBegin(Object transaction,TransactionDefinition definition){side.phases.add("begin");assertThat(definition.getTimeout()).isEqualTo(15);assertThat(definition.isReadOnly()).isFalse();if(side.failure.equals("BEGIN"))throw new CannotCreateTransactionException("private-tx-detail");before=side.readAt;side.committed=false;}
        @Override protected void doCommit(DefaultTransactionStatus status){side.phases.add("commit");side.committed=true;}
        @Override protected void doRollback(DefaultTransactionStatus status){side.phases.add("rollback");side.readAt=before;}
    }
    static final class RecordingJdbc extends JdbcTemplate {
        final Side side;final List<List<Object>> arguments=new ArrayList<>();int calls,updates;RecordingJdbc(Side side){this.side=side;}
        @Override public int update(String sql,Object...args){calls++;arguments.add(Arrays.asList(args.clone()));String normalized=sql.replaceAll("\\s+"," ").strip();
            if(normalized.startsWith("INSERT INTO pawbridge_community.private_note_members")){side.phases.add("insert:"+args[0]);return 1;}
            assertThat(normalized).startsWith("UPDATE pawbridge_community.private_note_mailboxes SET read_at = COALESCE(read_at, ?)");side.phases.add("read-update");updates++;
            if(side.failure.equals("DAO"))throw new DataRetrievalFailureException("private-sql-detail");if("INBOX".equals(side.direction)&&side.readAt==null)side.readAt=((Timestamp)args[0]).toInstant();return "INBOX".equals(side.direction)?1:0;
        }
        @Override public <T>T queryForObject(String sql,Class<T> type,Object...args){calls++;arguments.add(Arrays.asList(args.clone()));side.phases.add("member-lock:"+args[0]);return type.cast(args[0]);}
        @Override public <T>List<T> query(String sql,RowMapper<T> mapper,Object...args){calls++;arguments.add(Arrays.asList(args.clone()));String normalized=sql.replaceAll("\\s+"," ").strip();boolean lock=normalized.contains("FOR UPDATE");side.phases.add(lock?"note-lock":"find");if(!side.exists)return List.of();
            try{return List.of(mapper.mapRow(resultSet(),0));}catch(SQLException failure){throw new IllegalStateException(failure);}
        }
        private ResultSet resultSet()throws SQLException{var row=mock(ResultSet.class);when(row.getObject("note_id",UUID.class)).thenReturn(NOTE);when(row.getObject(1,UUID.class)).thenReturn(NOTE);when(row.getObject("sender_id")).thenReturn(8L);when(row.getObject("recipient_id")).thenReturn(7L);when(row.getString("body")).thenReturn("private-body-not-logged");when(row.getString("direction")).thenReturn(side.direction);when(row.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW.minusSeconds(1)));when(row.getTimestamp("expires_at")).thenReturn(Timestamp.from(EXPIRES));when(row.getTimestamp("read_at")).thenReturn(side.readAt==null?null:Timestamp.from(side.readAt));return row;}
    }
}
