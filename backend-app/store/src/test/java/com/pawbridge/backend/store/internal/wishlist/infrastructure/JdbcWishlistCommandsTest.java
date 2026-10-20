package com.pawbridge.backend.store.internal.wishlist.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.pawbridge.backend.store.internal.wishlist.application.WishlistCommands;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

class JdbcWishlistCommandsTest {
    private final NamedParameterJdbcOperations jdbc=mock(NamedParameterJdbcOperations.class);
    private final TransactionOperations write=mock(TransactionOperations.class);
    private final Clock clock=mock(Clock.class);
    private final JdbcWishlistCommands commands=new JdbcWishlistCommands(jdbc,write,clock);
    private boolean active,duplicate;
    private Long identity=9007199254740993L;
    private List<Long> matches=List.of(33L);
    private int deleted=1;
    private ResultSet sku;
    private final List<String> events=new ArrayList<>(),sql=new ArrayList<>();
    private final List<Map<String,?>> params=new ArrayList<>();
    private final Instant instant=Instant.parse("2026-10-20T00:00:01.123456789Z");
    private final LocalDateTime created=LocalDateTime.of(2026,10,20,9,0,1,123456789);

    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(instant);when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(write.execute(any())).thenAnswer(call->{active=true;try{return call.<TransactionCallback<?>>getArgument(0).doInTransaction(new SimpleTransactionStatus());}finally{active=false;}});
        doAnswer(call->{active=true;try{call.<Consumer<TransactionStatus>>getArgument(0).accept(new SimpleTransactionStatus());}finally{active=false;}return null;}).when(write).executeWithoutResult(any());
        when(jdbc.queryForObject(anyString(),anyMap(),eq(Boolean.class))).thenAnswer(call->{capture("exists",call.getArgument(0),call.getArgument(1));return duplicate;});
        when(jdbc.queryForObject(anyString(),anyMap(),eq(Long.class))).thenAnswer(call->{capture("insert",call.getArgument(0),call.getArgument(1));return identity;});
        when(jdbc.query(anyString(),anyMap(),any(ResultSetExtractor.class))).thenAnswer(call->{capture("sku",call.getArgument(0),call.getArgument(1));return call.<ResultSetExtractor<?>>getArgument(2).extractData(sku);});
        when(jdbc.query(anyString(),anyMap(),any(RowMapper.class))).thenAnswer(call->{capture("find",call.getArgument(0),call.getArgument(1));return matches;});
        when(jdbc.update(anyString(),anyMap())).thenAnswer(call->{capture("delete",call.getArgument(0),call.getArgument(1));return deleted;});
    }

    @Test void given__existing_sku_and_options__when__adding__then__preserve_precedence_generated_identity_local_audit_and_response() {
        sku=rows(map("sku_id",80L,"sku_code","SKU","product_id",9L,"product_name","상품","image_url",null,"price",9007199254740993L,
                "option_value_id",3L,"group_name","색상","value_name","파랑"),map("option_value_id",4L,"group_name","크기","value_name","대"));
        assertEquals(new WishlistCommands.Added(identity,7L,80L,"SKU",9L,"상품",null,9007199254740993L,Map.of("색상","파랑","크기","대"),created),commands.add(7,80L));
        assertEquals(List.of("exists","sku","insert"),events);
        assertTrue(sql.get(0).contains("user_id=:member AND sku_id=:sku"));assertTrue(sql.get(2).contains("RETURNING id"));
        assertTrue(sql.get(2).contains("VALUES (:member,:sku,:created,:created)"));
        assertEquals(Map.of("member",7L,"sku",80L,"created",created),params.get(2));
        assertFalse(sql.get(1).contains("status="));assertFalse(sql.get(1).contains("stock_quantity>"));
        verify(write,times(1)).execute(any());verify(clock,times(1)).instant();assertFalse(active);
    }

    @Test void given__sku_without_options__when__adding__then__return_empty_option_map() {
        sku=rows(map("sku_id",80L,"sku_code","SKU","product_id",9L,"product_name","상품","image_url","image","price",0L,"option_value_id",null));
        var added=commands.add(7,80L);assertEquals(Map.of(),added.options());assertEquals(0L,added.price());assertEquals("image",added.productImageUrl());
    }

    @Test void given__duplicate_pair__when__adding__then__preserve_original_state_error_before_sku_or_insert() {
        duplicate=true;
        assertEquals("이미 찜한 상품입니다.",assertThrows(IllegalStateException.class,()->commands.add(7,80L)).getMessage());
        assertEquals(List.of("exists"),events);verifyNoInteractions(clock);assertFalse(active);
    }

    @Test void given__missing_sku__when__adding__then__preserve_bad_input_and_do_not_insert() {
        sku=rows();
        assertEquals("SKU를 찾을 수 없습니다: 80",assertThrows(IllegalArgumentException.class,()->commands.add(7,80L)).getMessage());
        assertEquals(List.of("exists","sku"),events);verifyNoInteractions(clock);
    }

    @Test void given__null_sku__when__adding__then__keep_duplicate_first_and_fail_as_bad_input_without_sku_query() {
        assertThrows(IllegalArgumentException.class,()->commands.add(7,null));
        assertEquals(List.of("exists"),events);assertNull(params.getFirst().get("sku"));verifyNoInteractions(clock);
    }

    @Test void given__insert_failure__when__adding__then__propagate_original_failure_without_success_result() {
        sku=rows(map("sku_id",80L,"product_id",9L,"price",1L));var failure=new DataAccessResourceFailureException("synthetic SQL detail");
        doThrow(failure).when(jdbc).queryForObject(anyString(),anyMap(),eq(Long.class));
        assertSame(failure,assertThrows(DataAccessResourceFailureException.class,()->commands.add(7,80L)));assertFalse(active);
    }

    @Test void given__missing_generated_identity__when__adding__then__fail_instead_of_fabricating_an_id() {
        identity=null;sku=rows(map("sku_id",80L,"product_id",9L,"price",1L));
        assertThrows(IllegalStateException.class,()->commands.add(7,80L));assertFalse(active);
    }

    @Test void given__one_owned_pair__when__removing_by_sku__then__delete_only_selected_identity_member_and_sku() {
        commands.removeByMemberAndSku(7,80);
        assertEquals(List.of("find","delete"),events);assertTrue(sql.getFirst().contains("user_id=:member AND sku_id=:sku"));
        assertEquals("DELETE FROM pawbridge_store.wishlists WHERE id=:id AND user_id=:member AND sku_id=:sku",sql.get(1));
        assertEquals(Map.of("member",7L,"sku",80L,"id",33L),params.get(1));assertFalse(active);
    }

    @Test void given__pair_not_found__when__removing_by_sku__then__same_bad_input_and_no_delete() {
        matches=List.of();assertEquals("찜을 찾을 수 없습니다.",assertThrows(IllegalArgumentException.class,()->commands.removeByMemberAndSku(7,80)).getMessage());
        assertEquals(List.of("find"),events);
    }

    @Test void given__preexisting_duplicate_pair__when__removing_by_sku__then__fail_without_deleting_one_or_all() {
        matches=List.of(33L,34L);assertThrows(IllegalStateException.class,()->commands.removeByMemberAndSku(7,80));
        assertEquals(List.of("find"),events);
    }

    @Test void given__owned_identity__when__removing_by_id__then__one_atomic_owner_scoped_delete_without_lookup() {
        commands.removeById(7,33);
        assertEquals(List.of("delete"),events);
        assertEquals("DELETE FROM pawbridge_store.wishlists WHERE id=:id AND user_id=:member",sql.getFirst());
        assertEquals(Map.of("member",7L,"id",33L),params.getFirst());
        verify(write,times(1)).executeWithoutResult(any());
        verify(jdbc).update(sql.getFirst(),params.getFirst());verifyNoMoreInteractions(jdbc);verifyNoInteractions(clock);assertFalse(active);
    }

    @Test void given__wrong_owner_or_missing_identity__when__removing_by_id__then__zero_rows_is_the_same_noop_without_lookup() {
        deleted=0;
        assertDoesNotThrow(()->commands.removeById(8,33));
        assertEquals(List.of("delete"),events);assertEquals(Map.of("member",8L,"id",33L),params.getFirst());
        assertEquals("DELETE FROM pawbridge_store.wishlists WHERE id=:id AND user_id=:member",sql.getFirst());
        verify(jdbc).update(sql.getFirst(),params.getFirst());verifyNoMoreInteractions(jdbc);verifyNoInteractions(clock);assertFalse(active);
    }

    @Test void given__identity_already_removed__when__retrying_or_losing_a_delete_race__then__zero_rows_stays_a_noop() {
        commands.removeById(7,33);deleted=0;
        assertDoesNotThrow(()->commands.removeById(7,33));
        assertEquals(List.of("delete","delete"),events);
        assertEquals(List.of(Map.of("member",7L,"id",33L),Map.of("member",7L,"id",33L)),params);
        verify(write,times(2)).executeWithoutResult(any());verify(jdbc,times(2)).update(sql.getFirst(),params.getFirst());
        verifyNoMoreInteractions(jdbc);verifyNoInteractions(clock);assertFalse(active);
    }

    @Test void given__concurrent_disappearance__when__deleting_selected_pair__then__fail_instead_of_claiming_delete_success() {
        deleted=0;assertThrows(IllegalStateException.class,()->commands.removeByMemberAndSku(7,80));assertFalse(active);
    }

    @Test void given__database_failure__when__deleting_identity__then__propagate_without_lookup_or_retry() {
        var failure=new DataAccessResourceFailureException("synthetic SQL");doThrow(failure).when(jdbc).update(anyString(),anyMap());
        assertSame(failure,assertThrows(DataAccessResourceFailureException.class,()->commands.removeById(7,33)));
        verify(jdbc).update("DELETE FROM pawbridge_store.wishlists WHERE id=:id AND user_id=:member",Map.of("member",7L,"id",33L));
        verifyNoMoreInteractions(jdbc);assertFalse(active);
    }

    private void capture(String event,String query,Map<String,?> values) { assertTrue(active);events.add(event);sql.add(query);params.add(new HashMap<>(values)); }
    private static Map<String,Object> map(Object... values) { Map<String,Object> result=new HashMap<>();for(int i=0;i<values.length;i+=2) result.put((String)values[i],values[i+1]);return result; }
    @SafeVarargs private static ResultSet rows(Map<String,Object>... values) {
        AtomicInteger i=new AtomicInteger(-1);return mock(ResultSet.class,call->{
            String method=call.getMethod().getName();if(method.equals("next")) return i.incrementAndGet()<values.length;
            if(method.startsWith("get")&&call.getArgument(0) instanceof String column) { Object value=values[i.get()].get(column);return method.equals("getLong")?(value==null?0L:((Number)value).longValue()):value; }
            return RETURNS_DEFAULTS.answer(call);
        });
    }
}
