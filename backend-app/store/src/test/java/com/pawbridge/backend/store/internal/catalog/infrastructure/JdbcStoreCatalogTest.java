package com.pawbridge.backend.store.internal.catalog.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.pawbridge.backend.store.internal.catalog.application.CatalogSearchUnavailableException;
import com.pawbridge.backend.store.internal.catalog.application.StoreCatalog;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

class JdbcStoreCatalogTest {
    private final NamedParameterJdbcOperations jdbc=mock(NamedParameterJdbcOperations.class);
    private final TransactionOperations tx=mock(TransactionOperations.class);
    private final JdbcStoreCatalog catalog=new JdbcStoreCatalog(jdbc,tx);
    private boolean active;
    private final List<String> sqls=new ArrayList<>();
    private final List<Map<String,?>> parameters=new ArrayList<>();
    private final LocalDateTime time=LocalDateTime.of(2026,10,20,1,2,3,123456000);

    @BeforeEach void transaction() {
        when(tx.execute(any())).thenAnswer(call -> {
            active=true;
            try { return call.<TransactionCallback<?>>getArgument(0).doInTransaction(new SimpleTransactionStatus()); }
            finally { active=false; }
        });
    }
    @AfterEach void close() { catalog.close(); }

    @ParameterizedTest @MethodSource("invalid")
    void given__invalid_search__when__reading__then__reject_before_transaction_or_sql(StoreCatalog.Search request) {
        assertThrows(IllegalArgumentException.class,()->catalog.search(request));
        verifyNoInteractions(jdbc,tx);
    }
    static Stream<Arguments> invalid() {
        return Stream.of(
            search(null,null,null,null,null,null,null,-1,20),search(null,null,null,null,null,null,null,0,0),
            search(null,null,null,null,null,null,null,0,101),search(null,null,null,null,null,null,null,5001,20),
            search(null,-1L,null,null,null,null,null,0,20),search(null,null,-1L,null,null,null,null,0,20),
            search(null,2L,1L,null,null,null,null,0,20),search(null,null,null,null,null,"price;DROP TABLE products",null,0,20),
            search(null,null,null,null,null,null,"desc;--",0,20),search("x".repeat(201),null,null,null,null,null,null,0,20),
            search("a\0b",null,null,null,null,null,null,0,20)).map(Arguments::of);
    }

    @Test void given__analyzer_only_punctuation__when__searching__then__empty_without_database() {
        assertEquals(new StoreCatalog.SearchResult(List.of(),0L,3,0,false),
                catalog.search(search("!!!",null,null,null,false,null,null,3,20)));
        verifyNoInteractions(jdbc,tx);
    }

    @Test void given__all_filters_and_terms__when__searching__then__retain_bound_cte_and_original_item_mapping() throws Exception {
        extractRows(searchRow());
        var result=catalog.search(search("alpha alpha beta",0L,9007199254740993L,8L,true,"minPrice","aSc",0,20));
        assertEquals(new StoreCatalog.SearchResult(List.of(new StoreCatalog.Item(7L,17L,"상품","Color: Blue", "상품",null,
                9007199254740993L,0,"ACTIVE",time,null)),21L,0,2,true),result);
        String sql=sqls.getFirst();
        assertTrue(sql.contains("s.status='ACTIVE'"));
        assertTrue(sql.contains("s.category_id=:category"));
        assertTrue(sql.contains("s.price>=:min"));
        assertTrue(sql.contains("s.price<=:max"));
        assertTrue(sql.contains("s.total_stock>0"));
        assertTrue(sql.contains("d.tokens @@ to_tsquery('simple',:query)"));
        assertTrue(sql.contains("d.source_option<>s.option_name OR d.analyzer_version<>:version"));
        assertTrue(sql.contains("LEFT JOIN sliced ON readiness.ready"));
        assertTrue(sql.endsWith("ORDER BY price ASC NULLS LAST,sku_id ASC"));
        assertFalse(sql.contains("alpha"));
        assertEquals(Map.of("offset",0L,"size",20,"query","'alpha' | 'beta'","version","nori-9.12.3-v1",
                "category",8L,"min",0L,"max",9007199254740993L),parameters.getFirst());
        assertFalse(active);
    }

    @Test void given__blank_keyword_and_null_defaults__when__searching__then__retain_unfiltered_stock_and_default_sort() throws Exception {
        extractRows(map("ready",true,"total_count",0L));
        assertEquals(new StoreCatalog.SearchResult(List.of(),0L,0,0,false),
                catalog.search(search(" ",null,null,null,null,null,null,null,null)));
        assertTrue(sqls.getFirst().contains("WITH readiness AS (SELECT true AS ready)"));
        assertTrue(sqls.getFirst().endsWith("ORDER BY sku_id DESC NULLS LAST,sku_id ASC"));
        assertFalse(sqls.getFirst().contains("s.total_stock>0"));
        assertFalse(sqls.getFirst().contains("NOT EXISTS"));
    }

    @ParameterizedTest @MethodSource("sorts")
    void given__allowed_sort_and_last_offset__when__searching__then__retain_sort_and_empty_page_total(String sort,String column) throws Exception {
        extractRows(map("ready",true,"total_count",3L));
        assertEquals(new StoreCatalog.SearchResult(List.of(),3L,1000,1,false),
                catalog.search(search(null,null,null,null,false,sort,"DESC",1000,100)));
        assertTrue(sqls.getFirst().endsWith("ORDER BY "+column+" DESC NULLS LAST,sku_id ASC"));
        assertEquals(100000L,parameters.getFirst().get("offset"));
    }
    static Stream<Arguments> sorts() { return Stream.of(Arguments.of("skuId","sku_id"),Arguments.of("price","price"),Arguments.of("createdAt","created_at")); }

    @Test void given__unready_projection__when__searching__then__fail_instead_of_returning_empty_success() throws Exception {
        extractRows(map("ready",false));
        assertThrows(CatalogSearchUnavailableException.class,()->catalog.search(search("사료",null,null,null,false,null,null,0,20)));
        assertFalse(active);
    }

    @Test void given__overflowing_stock__when__mapping__then__preserve_mapping_failure_instead_of_truncating() throws Exception {
        var row=searchRow();row.put("total_stock",2147483648L);extractRows(row);
        assertThrows(ArithmeticException.class,()->catalog.search(search(null,null,null,null,false,null,null,0,20)));
    }

    @Test void given__sql_failure__when__searching__then__translate_to_search_unavailable() {
        var failure=new DataAccessResourceFailureException("synthetic private DB detail");
        doThrow(failure).when(jdbc).query(anyString(),anyMap(),any(ResultSetExtractor.class));
        assertSame(failure,assertThrows(CatalogSearchUnavailableException.class,
                ()->catalog.search(search(null,null,null,null,false,null,null,0,20))).getCause());
        assertFalse(active);
    }

    @ParameterizedTest @MethodSource("txFailures")
    void given__transaction_unavailable__when__searching__then__return_search_failure(RuntimeException failure) {
        doThrow(failure).when(tx).execute(any());
        assertSame(failure,assertThrows(CatalogSearchUnavailableException.class,
                ()->catalog.search(search(null,null,null,null,false,null,null,0,20))).getCause());
        verifyNoInteractions(jdbc);
    }
    static Stream<Arguments> txFailures() { return Stream.of(Arguments.of(new CannotCreateTransactionException("offline")),Arguments.of(new TransactionTimedOutException("timeout"))); }

    @Test void given__unexpected_transaction_failure__when__searching__then__do_not_mask_as_503() {
        var failure=new TransactionSystemException("unexpected");doThrow(failure).when(tx).execute(any());
        assertSame(failure,assertThrows(TransactionSystemException.class,
                ()->catalog.search(search(null,null,null,null,false,null,null,0,20))));
    }

    @Test void given__deleted_product_nulls_and_skus__when__reading__then__retain_full_detail_and_options_in_one_transaction() throws Exception {
        mapRows(map("product_id",9L,"name","상품","description",null,"image_url",null,"status","DELETED",
                "category_id",null,"category_name",null,"view_count",2L,"created_at",time,"updated_at",null));
        extractRows(
            map("sku_id",31L,"sku_code","A","price",9007199254740993L,"stock_quantity",0,"option_value_id",3L,"group_name","색상","value_name","파랑"),
            map("sku_id",31L,"option_value_id",4L,"group_name","크기","value_name","대"),
            map("sku_id",32L,"sku_code","B","price",0L,"stock_quantity",4,"option_value_id",null));
        var product=catalog.product(9L);
        assertEquals(new StoreCatalog.Product(9L,"상품",null,null,"DELETED",null,null,2L,time,null,List.of(
            new StoreCatalog.Sku(31L,"A",9007199254740993L,0,Map.of("색상","파랑","크기","대")),
            new StoreCatalog.Sku(32L,"B",0L,4,Map.of()))),product);
        assertTrue(sqls.getFirst().contains("WHERE p.id=:id"));
        assertFalse(sqls.getFirst().contains("p.status="));
        assertEquals(List.of(Map.of("id",9L),Map.of("id",9L)),parameters);
        verify(tx,times(1)).execute(any());
        assertFalse(active);
    }

    @Test void given__missing_product__when__reading__then__preserve_bad_input_without_sku_query() throws Exception {
        mapRows();
        assertEquals("Product not found: 9",assertThrows(IllegalArgumentException.class,()->catalog.product(9L)).getMessage());
        assertEquals(1,sqls.size());
    }

    @Test void given__product_without_skus_and_category__when__reading__then__retain_category_and_empty_list() throws Exception {
        mapRows(map("product_id",9L,"name","상품","status","SOLD_OUT","category_id",8L,"category_name","사료","view_count",0L));
        extractRows();
        assertEquals(new StoreCatalog.Product(9L,"상품",null,null,"SOLD_OUT",8L,"사료",0L,null,null,List.of()),catalog.product(9L));
    }

    @Test void given__root_child_and_grandchild__when__reading_categories__then__return_roots_with_recursive_children() throws Exception {
        mapRows(map("id",2L,"name","자식","description",null,"parent_id",1L),
                map("id",1L,"name","루트","description","설명","parent_id",null),
                map("id",3L,"name","손자","description",null,"parent_id",2L));
        assertEquals(List.of(new StoreCatalog.Category(1L,"루트","설명",null,List.of(
                new StoreCatalog.Category(2L,"자식",null,1L,List.of(new StoreCatalog.Category(3L,"손자",null,2L,List.of())))))),catalog.categories());
    }

    @Test void given__groups_with_and_without_values__when__reading__then__retain_groups_and_value_group_fields() throws Exception {
        extractRows(map("group_id",1L,"group_name","색상","value_id",3L,"value_name","파랑"),
                map("group_id",1L,"group_name","색상","value_id",4L,"value_name","초록"),
                map("group_id",2L,"group_name","크기","value_id",null));
        assertEquals(List.of(new StoreCatalog.OptionGroup(1L,"색상",List.of(new StoreCatalog.OptionValue(3L,"파랑",1L,"색상"),
                new StoreCatalog.OptionValue(4L,"초록",1L,"색상"))),new StoreCatalog.OptionGroup(2L,"크기",List.of())),catalog.optionGroups());
    }

    @Test void given__one_group__when__reading_detail__then__bind_id_and_keep_empty_values() throws Exception {
        extractRows(map("group_id",2L,"group_name","크기","value_id",null));
        assertEquals(new StoreCatalog.OptionGroup(2L,"크기",List.of()),catalog.optionGroup(2));
        assertTrue(sqls.getFirst().endsWith(" WHERE g.id=:id"));assertEquals(Map.of("id",2L),parameters.getFirst());
    }

    @Test void given__missing_option_group__when__reading_detail__then__preserve_original_bad_input() throws Exception {
        extractRows();
        assertEquals("옵션 그룹을 찾을 수 없습니다: 77",assertThrows(IllegalArgumentException.class,()->catalog.optionGroup(77)).getMessage());
    }

    @Test void given__detail_database_failure__when__reading__then__do_not_convert_to_search_unavailable() {
        var failure=new DataAccessResourceFailureException("synthetic");
        doThrow(failure).when(jdbc).query(anyString(),anyMap(),any(RowMapper.class));
        assertSame(failure,assertThrows(DataAccessResourceFailureException.class,()->catalog.product(9)));
    }

    private static StoreCatalog.Search search(String k,Long min,Long max,Long category,Boolean stock,String sort,String direction,Integer page,Integer size) {
        return new StoreCatalog.Search(k,min,max,category,stock,sort,direction,page,size);
    }
    private Map<String,Object> searchRow() { return map("ready",true,"total_count",21L,"product_id",7L,"sku_id",17L,"name","상품",
            "option_name","Color: Blue","image_url",null,"price",9007199254740993L,"total_stock",0L,"status","ACTIVE","created_at",time,"updated_at",null); }
    private static Map<String,Object> map(Object... cells) {
        Map<String,Object> result=new HashMap<>();for(int i=0;i<cells.length;i+=2) result.put((String)cells[i],cells[i+1]);return result;
    }
    @SafeVarargs private final void extractRows(Map<String,Object>... values) throws Exception {
        when(jdbc.query(anyString(),anyMap(),any(ResultSetExtractor.class))).thenAnswer(call -> {
            assertTrue(active);sqls.add(call.getArgument(0));parameters.add(call.getArgument(1));
            return call.<ResultSetExtractor<?>>getArgument(2).extractData(rows(values));
        });
    }
    @SafeVarargs private final void mapRows(Map<String,Object>... values) throws Exception {
        when(jdbc.query(anyString(),anyMap(),any(RowMapper.class))).thenAnswer(call -> {
            assertTrue(active);sqls.add(call.getArgument(0));parameters.add(call.getArgument(1));
            ResultSet rs=rows(values);RowMapper<?> mapper=call.getArgument(2);List<Object> result=new ArrayList<>();int i=0;
            while(rs.next()) result.add(mapper.mapRow(rs,i++));return result;
        });
    }
    @SafeVarargs private static ResultSet rows(Map<String,Object>... values) throws Exception {
        AtomicInteger i=new AtomicInteger(-1);
        return mock(ResultSet.class,call -> {
            String method=call.getMethod().getName();
            if(method.equals("next")) return i.incrementAndGet()<values.length;
            if(method.startsWith("get") && call.getArgument(0) instanceof String column) {
                Object value=values[i.get()].get(column);
                return switch(method) {
                    case "getLong" -> value==null?0L:((Number)value).longValue();
                    case "getInt" -> value==null?0:((Number)value).intValue();
                    case "getBoolean" -> Boolean.TRUE.equals(value);
                    default -> value;
                };
            }
            return RETURNS_DEFAULTS.answer(call);
        });
    }
}
