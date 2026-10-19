package com.pawbridge.backend.community.internal.post.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.community.internal.post.application.PostSnapshot;
import com.pawbridge.backend.community.internal.post.application.PostNotFoundException;
import com.pawbridge.backend.community.internal.post.domain.PostNotOwnedException;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.support.*;

class JdbcPostModificationTest {
    private final JdbcOperations jdbc=mock(JdbcOperations.class);
    private final TransactionOperations tx=mock(TransactionOperations.class);
    private final Connection connection=mock(Connection.class);
    private final ResultSet result=mock(ResultSet.class);
    private final ObjectMapper mapper=new ObjectMapper();
    private final Clock clock=Clock.fixed(Instant.parse("2026-01-01T03:00:00.123456Z"),ZoneId.of("Asia/Seoul"));
    private final LocalDateTime previous=LocalDateTime.of(2026,1,1,0,0);
    private final List<Call> calls=new ArrayList<>();
    private boolean active,exists=true;
    private String images="[\"https://fixture.invalid/old\"]";
    private int updated=1;
    @BeforeEach void givenShortTransactionAndLockedRow() throws Exception {
        when(tx.execute(org.mockito.ArgumentMatchers.<TransactionCallback<PostSnapshot>>any())).thenAnswer(call->{
            active=true;try{return call.getArgument(0,TransactionCallback.class).doInTransaction(new SimpleTransactionStatus());}finally{active=false;}
        });
        when(connection.prepareStatement(anyString())).thenAnswer(call->{
            assertThat(active).isTrue();Call recorded=new Call(call.getArgument(0));calls.add(recorded);
            PreparedStatement statement=mock(PreparedStatement.class);
            doAnswer(binding->{recorded.values.put(binding.getArgument(0),binding.getArgument(1));return null;}).when(statement).setObject(anyInt(),any());
            doAnswer(binding->{recorded.values.put(binding.getArgument(0),binding.getArgument(1));assertThat(binding.getArgument(2,Calendar.class).getTimeZone().getID()).isEqualTo("UTC");return null;})
                    .when(statement).setTimestamp(anyInt(),any(Timestamp.class),any(Calendar.class));
            return statement;
        });
        when(result.next()).thenAnswer(call->exists);
        when(result.getLong("post_id")).thenReturn(42L);when(result.getLong("author_id")).thenReturn(7L);
        when(result.getString(anyString())).thenAnswer(call->switch(call.getArgument(0,String.class)){
            case "title"->"제목";case "content"->"본문";case "board_type"->"MISSING";case "image_urls"->images;default->null;
        });
        when(result.getTimestamp(anyString(),any(Calendar.class))).thenReturn(Timestamp.valueOf(previous));
        when(jdbc.query(any(PreparedStatementCreator.class),org.mockito.ArgumentMatchers.<ResultSetExtractor<PostSnapshot>>any()))
                .thenAnswer(call->{call.getArgument(0,PreparedStatementCreator.class).createPreparedStatement(connection);return call.getArgument(1,ResultSetExtractor.class).extractData(result);});
        when(jdbc.update(any(PreparedStatementCreator.class))).thenAnswer(call->{
            call.getArgument(0,PreparedStatementCreator.class).createPreparedStatement(connection);return updated;
        });
    }
    @Test void given__partial_title__when__updating__then__lock_merge_emit_exact_payload_and_project_in_one_transaction() throws Exception {
        PostSnapshot saved;
        try(var writer=new JdbcPostWriter(jdbc,tx,mapper,clock)){saved=writer.update(42,7,"수정",null,null);}
        assertThat(active).isFalse();assertThat(calls).hasSize(4);assertThat(calls.getFirst().sql).contains("FOR UPDATE","deleted_at IS NULL");
        assertThat(saved).isEqualTo(new PostSnapshot(42,7,"수정","본문","MISSING",List.of("https://fixture.invalid/old"),previous,previous));
        assertThat(calls.get(1).values).containsEntry(1,"수정").containsEntry(2,"본문").containsEntry(4,Timestamp.valueOf(LocalDateTime.now(clock))).containsEntry(5,42L).containsEntry(6,7L);
        Call event=calls.get(2);assertThat(event.values).containsEntry(2,"Post").containsEntry(3,"42").containsEntry(4,"POST_UPDATED");
        assertThat(UUID.fromString((String)event.values.get(1)).toString()).isEqualTo(event.values.get(1));
        assertThat(mapper.readTree((String)event.values.get(5))).isEqualTo(mapper.valueToTree(Map.of("postId",42,"authorId",7,"title","수정","content","본문","boardType","MISSING","imageUrls",saved.imageUrls())));
        assertThat(calls.get(3).sql).contains("post_search_documents","ON CONFLICT","setweight");
        assertThat(calls.get(3).values).containsEntry(1,42L).containsEntry(2,"수정").containsEntry(3,"본문");
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void given__omitted_or_identical_values__when__updating__then__retain_row_timestamp_but_still_emit_event_and_projection(boolean same) {
        try(var writer=new JdbcPostWriter(jdbc,tx,mapper,clock)){
            assertThat(writer.update(42,7,same?"제목":null,same?"본문":null,same?List.of("https://fixture.invalid/old"):null).updatedAt()).isEqualTo(previous);
        }
        assertThat(calls).hasSize(3);assertThat(calls.get(1).sql).contains("outbox_events");assertThat(calls.get(2).sql).contains("post_search_documents");
    }
    @Test void given__explicit_empty_fields_and_images__when__updating__then__write_empty_values_without_creation_board_restriction() {
        try(var writer=new JdbcPostWriter(jdbc,tx,mapper,clock)){
            var saved=writer.update(42,7,"","",List.of());assertThat(saved.title()).isEmpty();assertThat(saved.content()).isEmpty();assertThat(saved.imageUrls()).isEmpty();assertThat(saved.boardType()).isEqualTo("MISSING");
        }
        assertThat(calls.get(1).values).containsEntry(1,"").containsEntry(2,"").containsEntry(3,"[]");
    }
    @Test void given__json_literal_null_images__when__text_only_update__then__preserve_failure_before_db_mutation() {
        images="null";
        try(var writer=new JdbcPostWriter(jdbc,tx,mapper,clock)){assertThatThrownBy(()->writer.update(42,7,"수정",null,null)).isInstanceOf(NullPointerException.class);}
        assertThat(calls).hasSize(1);
    }
    @Test void given__foreign_author_after_lock__when__updating__then__deny_without_any_write() {
        try(var writer=new JdbcPostWriter(jdbc,tx,mapper,clock)){assertThatThrownBy(()->writer.update(42,8,"수정",null,null)).isInstanceOf(PostNotOwnedException.class);}
        assertThat(calls).hasSize(1);
    }
    @Test void given__missing_or_deleted_row__when__locking__then__404_without_any_write() {
        exists=false;
        try(var writer=new JdbcPostWriter(jdbc,tx,mapper,clock)){assertThatThrownBy(()->writer.update(42,7,"수정",null,null)).isInstanceOf(PostNotFoundException.class);}
        assertThat(calls).hasSize(1);
    }
    @Test void given__lost_update_predicate__when__writing__then__404_without_outbox_or_projection() {
        updated=0;
        try(var writer=new JdbcPostWriter(jdbc,tx,mapper,clock)){assertThatThrownBy(()->writer.update(42,7,"수정",null,null)).isInstanceOf(PostNotFoundException.class);}
        assertThat(calls).hasSize(2);
    }
    private static final class Call {final String sql;final Map<Integer,Object> values=new LinkedHashMap<>();Call(String sql){this.sql=sql;}}
}
