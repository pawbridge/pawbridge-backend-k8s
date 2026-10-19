package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.community.internal.note.application.SendPrivateNote;
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteSendStore;
import com.pawbridge.backend.community.internal.note.web.NoteSendController;
import com.pawbridge.backend.community.internal.note.web.NoteSendExceptionHandler;
import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import com.pawbridge.communityservice.client.UserServiceClient;
import com.pawbridge.communityservice.contact.*;
import com.pawbridge.communityservice.exception.common.GlobalExceptionRestAdvice;
import feign.FeignException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
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
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;

/** Selected actual MVC with recording SQL/TM and mocked Stream. Not PG/JPA/FK/JWT/SSE evidence. */
final class F29MvcFixture {
    static final UUID KEY=UUID.fromString("f2900000-0000-0000-0000-000000000001");
    static final UUID STORED=UUID.fromString("f2900000-0000-0000-0000-000000000002");
    static final UUID NORMALIZED=UUID.fromString("f2900000-0000-0000-0000-000000000003");
    static final Instant NOW=Instant.parse("2032-02-29T15:00:00.123456Z");
    static final String CANONICAL="{\"recipientId\":8,\"body\":\"  private\\n본문🦊  \",\"requestId\":\""+KEY+"\"}";
    final Side source=new Side(),adapted=new Side();
    final UserServiceClient client=mock(UserServiceClient.class);
    final ContactMemberQuery members=mock(ContactMemberQuery.class);
    final PrivateNoteStream stream=mock(PrivateNoteStream.class); // No original executor/constructor runs.
    final F29LegacyNoteSendService original;
    final SendPrivateNote candidate;
    int sourceReached,candidateReached;
    boolean memberFailure,hintFailure;
    F29MvcFixture(){
        when(client.getContactMember(anyLong())).thenAnswer(call->{long id=call.getArgument(0);source.phases.add("current:"+id);if(memberFailure){var e=mock(FeignException.class);when(e.status()).thenReturn(500);throw e;}return new PrivateNoteModels.ContactMember(id," raw ",true,false);});
        when(members.get(anyLong())).thenAnswer(call->{long id=call.getArgument(0);adapted.phases.add("current:"+id);if(memberFailure)throw new ContactMemberUnavailableException(new IllegalStateException("private-member"));return new ContactMember(id," raw ",true,false);});
        doAnswer(call->{publish(source,call.getArgument(0),call.getArgument(1));return null;}).when(stream).publish(anyLong(),any(PrivateNoteModels.Notification.class));
        original=spy(new F29LegacyNoteSendService(new PrivateNoteRepository(source.jdbc),client,source.template,source.clock,stream));
        candidate=spy(new SendPrivateNote(members,new JdbcNoteSendStore(adapted.jdbc,adapted.template,(id,n)->publish(adapted,id,n)),adapted.clock,UUID::randomUUID));
        doAnswer(call->{sourceReached++;return call.callRealMethod();}).when(original).send(anyLong(),any());
        doAnswer(call->{candidateReached++;return call.callRealMethod();}).when(candidate).send(anyLong(),any());
    }
    private void publish(Side side,long id,Object notification){
        assertThat(side.committed).as("doCommit precedes publish; bound resources are not an early-commit oracle").isTrue();
        assertThat(id).isEqualTo(8);side.phases.add("publish:"+id);side.notification=new ObjectMapper().findAndRegisterModules().valueToTree(notification);
        if(hintFailure)throw new IllegalStateException("private-push");
    }
    void previous(String value){source.previous=adapted.previous=value;}
    void fail(String value){source.failure=adapted.failure=value;}
    MockMvc sourceMvc(ObjectMapper mapper){return MockMvcBuilders.standaloneSetup(new F29LegacyNoteSendController(original)).setControllerAdvice(new F29LegacyNoteSendExceptionAdvice(),new GlobalExceptionRestAdvice()).setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).addFilters(new PrivateNoteHttpBoundary()).build();}
    MockMvc candidateMvc(ObjectMapper mapper){return MockMvcBuilders.standaloneSetup(new NoteSendController(candidate)).setControllerAdvice(new NoteSendExceptionHandler()).setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();}
    MvcResult send(MockMvc mvc,String body,boolean candidateSide)throws Exception{
        var request=post("/api/v1/notes").header("X-User-Id","7").accept(MediaType.APPLICATION_JSON).contentType(MediaType.APPLICATION_JSON);if(body!=null)request.content(body);
        if(candidateSide){var auth=new UsernamePasswordAuthenticationToken(7L,null,List.of());SecurityContextHolder.getContext().setAuthentication(auth);request.principal(auth);}return mvc.perform(request).andReturn();
    }
    static String body(MvcResult r)throws Exception{return r.getResponse().getContentAsString(StandardCharsets.UTF_8);}
    static void cache(MvcResult r){assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");assertThat(r.getResponse().getHeaders("Set-Cookie")).isEmpty();}
    void agree(){assertThat(adapted.phases).containsExactlyElementsOf(source.phases);assertThat(adapted.clock.calls).isEqualTo(source.clock.calls);assertThat(adapted.jdbc.calls).isEqualTo(source.jdbc.calls);assertThat(adapted.jdbc.inserts).isEqualTo(source.jdbc.inserts);assertThat(adapted.noteExists).isEqualTo(source.noteExists);}
    static Map<String,Object> observation(MvcResult r,Side side,int reached)throws Exception{
        String wire=body(r);if(r.getResponse().getStatus()==200){var tree=new ObjectMapper().readTree(wire);UUID id=UUID.fromString(tree.path("data").path("noteId").asText());
            if(side.previous.isEmpty()){
                assertThat(side.jdbc.noteId).isEqualTo(id);assertThat(side.jdbc.mailboxId).isEqualTo(id);assertThat(side.jdbc.requestNoteId).isEqualTo(id);assertThat(side.notification.path("noteId").asText()).isEqualTo(id.toString());assertThat(side.notification.path("href").asText()).isEqualTo("/notes/"+id);assertThat(side.notification.size()).isEqualTo(7);
            }else{assertThat(id).isEqualTo(STORED);assertThat(side.jdbc.inserts).isZero();assertThat(side.notification).isNull();}
            wire=wire.replace(id.toString(),NORMALIZED.toString());
        }
        var v=new LinkedHashMap<String,Object>();v.put("status",r.getResponse().getStatus());v.put("body",wire);v.put("cacheControl",r.getResponse().getHeader("Cache-Control"));v.put("setCookieCount",r.getResponse().getHeaders("Set-Cookie").size());v.put("contentType",r.getResponse().getContentType());v.put("async",r.getRequest().isAsyncStarted());v.put("serviceReached",reached);v.put("phases",List.copyOf(side.phases));v.put("clockCalls",side.clock.calls);v.put("sqlCalls",side.jdbc.calls);v.put("insertCalls",side.jdbc.inserts);v.put("committed",side.committed);return v;
    }
    static final class Side{
        final List<String>phases=new ArrayList<>();final RecordingJdbc jdbc=new RecordingJdbc(this);final TransactionTemplate template=new TransactionTemplate(new RecordingTm(this));final Samples clock=new Samples(this);
        String previous="",failure="";boolean committed,noteExists;com.fasterxml.jackson.databind.JsonNode notification;
        Side(){template.setTimeout(15);}
    }
    static final class Samples extends Clock{final Side side;int calls;Samples(Side s){side=s;}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){side.phases.add("clock");calls++;return NOW;}}
    static final class RecordingTm extends AbstractPlatformTransactionManager{
        final Side side;RecordingTm(Side s){side=s;}protected Object doGetTransaction(){return new Object();}protected void doBegin(Object o,TransactionDefinition d){side.phases.add("begin");assertThat(d.getTimeout()).isEqualTo(15);assertThat(d.isReadOnly()).isFalse();side.committed=false;if(side.failure.equals("BEGIN"))throw new CannotCreateTransactionException("private-tx");}protected void doCommit(DefaultTransactionStatus s){side.phases.add("commit");side.committed=true;}protected void doRollback(DefaultTransactionStatus s){side.phases.add("rollback");side.noteExists=false;}
    }
    static final class RecordingJdbc extends JdbcTemplate{
        final Side side;int calls,inserts;UUID noteId,mailboxId,requestNoteId;RecordingJdbc(Side s){side=s;}
        public int update(String sql,Object...a){calls++;String s=sql.replaceAll("\\s+"," ").strip();if(s.startsWith("INSERT INTO pawbridge_community.private_note_members")){side.phases.add("insert:"+a[0]);return 1;}
            inserts++;if(s.startsWith("INSERT INTO pawbridge_community.private_notes(")){side.phases.add("note-insert");noteId=(UUID)a[0];side.noteExists=true;assertThat(a[3]).isEqualTo("private\n본문🦊");assertThat(a[7]).isEqualTo(Timestamp.from(NOW));assertThat(a[8]).isEqualTo(Timestamp.from(NOW.atZone(ZoneOffset.UTC).plusYears(1).toInstant()));return 1;}
            if(s.startsWith("INSERT INTO pawbridge_community.private_note_mailboxes(")){side.phases.add("mailbox-insert");mailboxId=(UUID)a[1];if(side.failure.equals("DAO"))throw new DataRetrievalFailureException("private-sql");return 2;}
            assertThat(s).startsWith("INSERT INTO pawbridge_community.private_note_requests(");side.phases.add("request-insert");requestNoteId=(UUID)a[3];assertThat(a[1]).isEqualTo(KEY);assertThat(a[2]).isEqualTo(hash());return 1;
        }
        public <T>T queryForObject(String sql,Class<T>t,Object...a){calls++;if(sql.contains("FOR UPDATE")){side.phases.add("member-lock:"+a[0]);return t.cast(a[0]);}if(sql.contains("SELECT count(*)")){side.phases.add("rate");return t.cast(0L);}side.phases.add("blocked");return t.cast(Boolean.FALSE);}
        public <T>List<T>query(String sql,RowMapper<T>mapper,Object...a){calls++;side.phases.add("request");if(side.previous.isEmpty())return List.of();try{var r=mock(ResultSet.class);when(r.getObject("note_id",UUID.class)).thenReturn(side.previous.equals("gone")?null:STORED);when(r.getString("request_hash")).thenReturn(side.previous.equals("conflict")?"different":hash());return List.of(mapper.mapRow(r,0));}catch(SQLException e){throw new IllegalStateException(e);}}
        static String hash(){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("8\nnull\nnull\nnull\nprivate\n본문🦊".getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    }
}
