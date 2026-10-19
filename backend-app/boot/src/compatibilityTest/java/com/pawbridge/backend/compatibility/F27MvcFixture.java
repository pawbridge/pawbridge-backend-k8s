package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.community.internal.note.application.SetPrivateNoteFavorite;
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteFavoriteMutation;
import com.pawbridge.backend.community.internal.note.web.NoteFavoriteController;
import com.pawbridge.backend.community.internal.note.web.NoteFavoriteExceptionHandler;
import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import com.pawbridge.communityservice.client.UserServiceClient;
import com.pawbridge.communityservice.contact.F27LegacyNoteFavoriteController;
import com.pawbridge.communityservice.contact.F27LegacyNoteFavoriteExceptionAdvice;
import com.pawbridge.communityservice.contact.F27LegacyNoteFavoriteService;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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

/** Recording JDBC and actual Spring synchronizations; no PG/JpaTM/JWT/registry thread or socket proof. */
final class F27MvcFixture {
    static final UUID NOTE = UUID.fromString("f2700000-0000-0000-0000-000000000001");
    static final Instant NOW = Instant.parse("2031-01-01T00:00:00.123456Z");
    static final Instant READ_AT = NOW.minusSeconds(5);
    static final Instant EXPIRES = NOW.plusSeconds(120);
    final Side source = new Side(), adapted = new Side();
    final UserServiceClient client = mock(UserServiceClient.class);
    final ContactMemberQuery members = mock(ContactMemberQuery.class);
    // Mockito does not invoke the raw original Stream constructor or create executors/heartbeats.
    final PrivateNoteStream sourceStream = mock(PrivateNoteStream.class);
    final F27LegacyNoteFavoriteService original;
    final SetPrivateNoteFavorite candidate;
    boolean inactive, memberFailure, hintFailure;
    int sourceReached, candidateReached;
    UUID sourceId, candidateId;
    Boolean sourceRequested, candidateRequested;

    F27MvcFixture() {
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
        original = spy(new F27LegacyNoteFavoriteService(new PrivateNoteRepository(source.jdbc), client,
                source.template, source.clock, sourceStream));
        candidate = spy(new SetPrivateNoteFavorite(members,
                new JdbcNoteFavoriteMutation(adapted.jdbc, adapted.template, id -> hint(adapted, id)), adapted.clock));
        doAnswer(call -> {
            sourceReached++; sourceId = call.getArgument(1); sourceRequested = call.getArgument(2);
            call.callRealMethod(); return null;
        }).when(original).favorite(anyLong(), any(UUID.class), anyBoolean());
        doAnswer(call -> {
            candidateReached++; candidateId = call.getArgument(1); candidateRequested = call.getArgument(2);
            call.callRealMethod(); return null;
        }).when(candidate).set(anyLong(), any(UUID.class), anyBoolean());
    }

    private void hint(Side side, long current) {
        assertThat(current).isEqualTo(7L);
        assertThat(side.committed).as("doCommit marker must precede hint; Spring resources may remain bound").isTrue();
        side.phases.add("hint:" + current);
        if (hintFailure) throw new IllegalStateException("private-hint-detail");
    }

    void setDirection(String value) { source.direction = adapted.direction = value; }
    void setInitialFavorite(boolean value) { source.favorite = adapted.favorite = value; }
    void missing() { source.exists = adapted.exists = false; }
    void fail(String phase) { source.failure = adapted.failure = phase; }

    MockMvc sourceMvc(ObjectMapper mapper) {
        return MockMvcBuilders.standaloneSetup(new F27LegacyNoteFavoriteController(original))
                .setControllerAdvice(new F27LegacyNoteFavoriteExceptionAdvice(), new GlobalExceptionRestAdvice())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .addFilters(new PrivateNoteHttpBoundary()).build();
    }

    MockMvc candidateMvc(ObjectMapper mapper) {
        return MockMvcBuilders.standaloneSetup(new NoteFavoriteController(candidate))
                .setControllerAdvice(new NoteFavoriteExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }

    MvcResult favorite(MockMvc mvc, String path, String body, boolean candidateSide) throws Exception {
        var request = put("/api/v1/notes/" + path + "/favorite").header("X-User-Id", "7")
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON);
        if (body != null) request.content(body);
        if (candidateSide) {
            var authentication = new UsernamePasswordAuthenticationToken(7L, null, List.of());
            SecurityContextHolder.getContext().setAuthentication(authentication); request.principal(authentication);
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
        assertThat(adapted.jdbc.calls).isEqualTo(source.jdbc.calls);
        assertThat(adapted.jdbc.arguments).containsExactlyElementsOf(source.jdbc.arguments);
        assertThat(adapted.jdbc.updates).isEqualTo(source.jdbc.updates);
        assertThat(adapted.favorite).isEqualTo(source.favorite);
        assertThat(adapted.readAt).isEqualTo(source.readAt);
    }
    static Map<String, Object> observation(MvcResult result, Side side, int reached, UUID id, Boolean requested) throws Exception {
        var values = new LinkedHashMap<String, Object>();
        values.put("status", result.getResponse().getStatus()); values.put("body", body(result));
        values.put("cacheControl", result.getResponse().getHeader("Cache-Control"));
        values.put("setCookieCount", result.getResponse().getHeaders("Set-Cookie").size());
        values.put("contentType", result.getResponse().getContentType()); values.put("async", result.getRequest().isAsyncStarted());
        values.put("serviceReached", reached); values.put("boundId", id == null ? null : id.toString());
        values.put("boundFavorite", requested); values.put("phases", List.copyOf(side.phases));
        values.put("clockCalls", side.clock.calls); values.put("sqlCalls", side.jdbc.calls); values.put("updateCalls", side.jdbc.updates);
        values.put("favoriteAfter", side.favorite); values.put("readAtAfter", side.readAt.toString());
        return values;
    }

    static final class Side {
        final List<String> phases = new ArrayList<>();
        final RecordingJdbc jdbc = new RecordingJdbc(this);
        final RecordingTm manager = new RecordingTm(this);
        final TransactionTemplate template = new TransactionTemplate(manager);
        final SampleClock clock = new SampleClock(this);
        String direction = "INBOX", failure = "";
        Instant readAt = READ_AT;
        boolean favorite, exists = true, committed;
        Side() { template.setTimeout(15); }
    }
    static final class SampleClock extends Clock {
        final Side side; int calls;
        SampleClock(Side side) { this.side = side; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId ignored) { return this; }
        @Override public Instant instant() { side.phases.add("clock"); return NOW.plusSeconds(calls++); }
    }
    static final class RecordingTm extends AbstractPlatformTransactionManager {
        final Side side; boolean before;
        RecordingTm(Side side) { this.side = side; }
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            side.phases.add("begin"); assertThat(definition.getTimeout()).isEqualTo(15); assertThat(definition.isReadOnly()).isFalse();
            if (side.failure.equals("BEGIN")) throw new CannotCreateTransactionException("private-tx-detail");
            before = side.favorite; side.committed = false;
        }
        @Override protected void doCommit(DefaultTransactionStatus status) { side.phases.add("commit"); side.committed = true; }
        @Override protected void doRollback(DefaultTransactionStatus status) { side.phases.add("rollback"); side.favorite = before; }
    }
    static final class RecordingJdbc extends JdbcTemplate {
        final Side side; final List<List<Object>> arguments = new ArrayList<>(); int calls, updates;
        RecordingJdbc(Side side) { this.side = side; }
        @Override public int update(String sql, Object... args) {
            calls++; arguments.add(Arrays.asList(args.clone())); String normalized = sql.replaceAll("\\s+", " ").strip();
            if (normalized.startsWith("INSERT INTO pawbridge_community.private_note_members")) { side.phases.add("insert:" + args[0]); return 1; }
            assertThat(normalized).isEqualTo("UPDATE pawbridge_community.private_note_mailboxes SET favorite = ? WHERE member_id = ? AND note_id = ?");
            side.phases.add("favorite-update"); updates++;
            if (side.failure.equals("DAO")) throw new DataRetrievalFailureException("private-sql-detail");
            side.favorite = (Boolean) args[0]; return 1;
        }
        @Override public <T> T queryForObject(String sql, Class<T> type, Object... args) {
            calls++; arguments.add(Arrays.asList(args.clone())); side.phases.add("member-lock:" + args[0]); return type.cast(args[0]);
        }
        @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            calls++; arguments.add(Arrays.asList(args.clone())); boolean lock = sql.contains("FOR UPDATE");
            side.phases.add(lock ? "note-lock" : "find"); if (!side.exists) return List.of();
            try { return List.of(mapper.mapRow(resultSet(), 0)); } catch (SQLException failure) { throw new IllegalStateException(failure); }
        }
        private ResultSet resultSet() throws SQLException {
            var row = mock(ResultSet.class);
            when(row.getObject("note_id", UUID.class)).thenReturn(NOTE); when(row.getObject(1, UUID.class)).thenReturn(NOTE);
            when(row.getObject("sender_id")).thenReturn(8L); when(row.getObject("recipient_id")).thenReturn(7L);
            when(row.getString("body")).thenReturn("private-body-not-logged"); when(row.getString("direction")).thenReturn(side.direction);
            when(row.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW.minusSeconds(60)));
            when(row.getTimestamp("expires_at")).thenReturn(Timestamp.from(EXPIRES));
            when(row.getTimestamp("read_at")).thenReturn(Timestamp.from(side.readAt)); when(row.getBoolean("favorite")).thenReturn(side.favorite);
            return row;
        }
    }
}
