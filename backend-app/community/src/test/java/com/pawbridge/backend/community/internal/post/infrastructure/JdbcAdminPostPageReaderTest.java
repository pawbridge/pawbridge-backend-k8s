package com.pawbridge.backend.community.internal.post.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.community.internal.post.application.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.*;

class JdbcAdminPostPageReaderTest {
    final JdbcOperations jdbc=mock(JdbcOperations.class);
    final TransactionOperations tx=mock(TransactionOperations.class);
    final JdbcPostReader reader=new JdbcPostReader(jdbc,tx,new ObjectMapper());
    boolean active;
    @BeforeEach void transaction(){when(tx.execute(ArgumentMatchers.<TransactionCallback<Object>>any())).thenAnswer(i->{active=true;try{return i.<TransactionCallback<Object>>getArgument(0).doInTransaction(new SimpleTransactionStatus());}finally{active=false;}});}
    static Stream<Arguments> pages(){return Stream.of(Arguments.of(0,0,0L,false),Arguments.of(0,1,1L,false),Arguments.of(1,1,3L,false),Arguments.of(0,2,41L,true),Arguments.of(4,0,41L,true));}
    @ParameterizedTest @MethodSource("pages")
    void given__partial_full_or_outside_page__when__querying__then__retain_spring_data_count_elision_and_scoped_one_transaction(int page,int rows,long total,boolean count){
        var values=new ArrayList<PostSnapshot>();for(int n=0;n<rows;n++)values.add(new PostSnapshot(n,7,"title","content","REPORT",List.of(),null,null));
        when(jdbc.query(anyString(),ArgumentMatchers.<RowMapper<PostSnapshot>>any(),eq(2),eq((long)page*2))).thenAnswer(i->{assertThat(active).isTrue();String sql=i.getArgument(0);assertThat(sql).contains("FROM pawbridge_community.posts WHERE deleted_at IS NULL","ORDER BY created_at DESC LIMIT ? OFFSET ?");return values;});
        when(jdbc.queryForObject("SELECT count(*) FROM pawbridge_community.posts WHERE deleted_at IS NULL",Long.class)).thenAnswer(i->{assertThat(active).isTrue();return 41L;});
        var result=reader.findPage(new PostPageReader.Request(page,2,List.of(new PostPageReader.Order("createdAt",false,false))));
        assertThat(result.content()).isEqualTo(values);assertThat(result.total()).isEqualTo(total);assertThat(active).isFalse();
        verify(jdbc,times(count?1:0)).queryForObject(anyString(),eq(Long.class));
    }
    @org.junit.jupiter.api.Test
    void given__jpa_integer_offset_boundary__when__reading__then__allow_exact_limit_and_reject_only_larger_offset_without_sql() {
        doReturn(List.of()).when(jdbc).query(anyString(),ArgumentMatchers.<RowMapper<PostSnapshot>>any(),eq(1),eq((long)Integer.MAX_VALUE));
        when(jdbc.queryForObject(anyString(),eq(Long.class))).thenReturn(0L);
        reader.findPage(new PostPageReader.Request(Integer.MAX_VALUE,1,List.of()));
        verify(jdbc).query(anyString(),ArgumentMatchers.<RowMapper<PostSnapshot>>any(),eq(1),eq((long)Integer.MAX_VALUE));
        clearInvocations(jdbc);
        assertThatThrownBy(()->reader.findPage(new PostPageReader.Request(Integer.MAX_VALUE,2,List.of())))
                .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
                .hasMessage("Page offset exceeds Integer.MAX_VALUE (2147483647)");
        verifyNoInteractions(jdbc);assertThat(active).isFalse();
    }
    static Stream<Arguments> sorts(){return Stream.of(Arguments.of("postId","post_id"),Arguments.of("authorId","author_id"),Arguments.of("title","lower(title)"),Arguments.of("content","lower(content)"),Arguments.of("boardType","board_type"),Arguments.of("imageUrls","image_urls"),Arguments.of("deletedAt","deleted_at"),Arguments.of("createdAt","created_at"),Arguments.of("updatedAt","updated_at"));}
    @ParameterizedTest @MethodSource("sorts")
    void given__each_mapped_sort_property__when__rendering__then__use_only_fixed_columns_and_native_null_json_order_without_tiebreak(String property,String sqlColumn){
        doReturn(List.of()).when(jdbc).query(anyString(),ArgumentMatchers.<RowMapper<PostSnapshot>>any(),eq(20),eq(0L));
        reader.findPage(new PostPageReader.Request(0,20,List.of(new PostPageReader.Order(property,true,true))));
        verify(jdbc).query(contains(" ORDER BY "+sqlColumn+" ASC LIMIT ? OFFSET ?"),ArgumentMatchers.<RowMapper<PostSnapshot>>any(),eq(20),eq(0L));assertThat(active).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"authorNickname","title;DELETE FROM posts","unknown"})
    void given__unmapped_or_injected_sort__when__reading__then__fail_without_sql_instead_of_new_sort_policy_or_identifier_interpolation(String property){
        assertThatThrownBy(()->reader.findPage(new PostPageReader.Request(0,20,List.of(new PostPageReader.Order(property,true,false))))).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(jdbc,tx);
    }
    @ParameterizedTest @ValueSource(strings={"query","count"})
    void given__database_failure__when__reading__then__propagate_it_after_transaction_unwinds(String phase){
        var fail=new DataAccessResourceFailureException("synthetic");
        if(phase.equals("query"))doThrow(fail).when(jdbc).query(anyString(),ArgumentMatchers.<RowMapper<PostSnapshot>>any(),eq(2),eq(2L));
        else{doReturn(List.of()).when(jdbc).query(anyString(),ArgumentMatchers.<RowMapper<PostSnapshot>>any(),eq(2),eq(2L));when(jdbc.queryForObject(anyString(),eq(Long.class))).thenThrow(fail);}
        assertThatThrownBy(()->reader.findPage(new PostPageReader.Request(1,2,List.of()))).isSameAs(fail);assertThat(active).isFalse();
    }
}
