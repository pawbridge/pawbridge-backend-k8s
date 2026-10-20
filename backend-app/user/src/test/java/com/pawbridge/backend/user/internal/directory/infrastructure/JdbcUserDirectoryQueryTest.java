package com.pawbridge.backend.user.internal.directory.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.pawbridge.backend.user.internal.directory.application.UserDirectoryQuery;
import com.pawbridge.backend.user.internal.profile.application.UserProfile;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcUserDirectoryQueryTest {
    private static final UserProfile ROW=new UserProfile(Long.MAX_VALUE,"raw@example.invalid"," 한글 ",null,"GOOGLE","ROLE_USER",null);
    private static UserDirectoryQuery.Request request(int number,int size) {
        return new UserDirectoryQuery.Request(number,size,List.of(new UserDirectoryQuery.Order("createdAt",false,false)));
    }

    @Test void given__a_raw_nullable_profile__when__listing__then__map_only_the_existing_seven_columns_in_the_read_transaction() {
        var f=new Fixture();var timed=new UserProfile(7,null,null,"raw nickname","LOCAL","ROLE_ADMIN",LocalDateTime.parse("2026-10-20T01:02:03.123456"));
        f.jdbc.rows=List.of(ROW,timed);var result=f.query.page(null,null,request(0,20));
        assertEquals(List.of(ROW,timed),result.content());assertEquals(2,result.total());
        assertEquals(List.of("begin","content","commit","cleanup"),f.events);
        assertEquals("SELECT user_id, email, name, nickname, provider, role, created_at FROM pawbridge_user.users ORDER BY created_at DESC LIMIT ? OFFSET ?",f.jdbc.sql.getFirst());
        assertArrayEquals(new Object[]{20,0L},f.jdbc.arguments.getFirst());
        assertEquals(List.of("UTC","UTC"),f.jdbc.zones);assertTrue(f.manager.readOnly);assertEquals(3,f.manager.timeout);
    }

    @Test void given__raw_wildcards_spaces_and_role__when__searching__then__retain_three_like_operands_and_role_and_without_escaping_or_stripping() {
        var f=new Fixture();f.query.page(" %_\\한글 ","ROLE_SHELTER",request(0,1));
        assertTrue(f.jdbc.sql.getFirst().contains("lower(email) LIKE lower(?) ESCAPE '' OR lower(name) LIKE lower(?) ESCAPE '' OR lower(nickname) LIKE lower(?) ESCAPE ''"));
        assertTrue(f.jdbc.sql.getFirst().contains(") AND role = ? ORDER BY"));
        assertArrayEquals(new Object[]{"% %_\\한글 %","% %_\\한글 %","% %_\\한글 %","ROLE_SHELTER",1,0L},f.jdbc.arguments.getFirst());
        assertArrayEquals(new Object[]{"% %_\\한글 %","% %_\\한글 %","% %_\\한글 %","ROLE_SHELTER"},f.jdbc.arguments.get(1));
        var empty=new Fixture();empty.query.page("",null,request(0,20));assertFalse(empty.jdbc.sql.getFirst().contains(" WHERE "));
    }

    @ParameterizedTest @CsvSource({"0,20,0,false,0","0,20,1,false,1","2,20,1,false,41","2,20,0,true,49","0,1,1,true,49"})
    void given__full_partial_or_empty_pages__when__reading__then__match_original_count_elision(int number,int size,int rows,boolean counted,long total) {
        var f=new Fixture();if(rows==0)f.jdbc.rows=List.of();var result=f.query.page(null,null,request(number,size));
        assertEquals(total,result.total());assertEquals(counted?2:1,f.jdbc.sql.size());
        assertEquals(counted?List.of("begin","content","count","commit","cleanup"):List.of("begin","content","commit","cleanup"),f.events);
    }

    @Test void given__the_seven_projected_properties__when__sorting__then__use_fixed_identifiers_and_lower_only_source_string_types_without_an_added_tiebreak() {
        var f=new Fixture();var orders=List.of(new UserDirectoryQuery.Order("UserId",true,true),new UserDirectoryQuery.Order("email",false,true),
                new UserDirectoryQuery.Order("name",true,true),new UserDirectoryQuery.Order("nickname",true,true),new UserDirectoryQuery.Order("provider",true,true),
                new UserDirectoryQuery.Order("role",true,true),new UserDirectoryQuery.Order("createdAt",true,true));
        f.query.page(null,null,new UserDirectoryQuery.Request(0,20,orders));
        assertTrue(f.jdbc.sql.getFirst().endsWith("ORDER BY user_id ASC, lower(email) DESC, lower(name) ASC, lower(nickname) ASC, lower(provider) ASC, role ASC, created_at ASC LIMIT ? OFFSET ?"));
    }

    @Test void given__unprojected_source_fields_or_unmapped_identifiers__when__sorting__then__reject_in_the_transaction_before_any_sql() {
        // The first four are a known, deliberately narrower candidate capability than legacy User JPA sorting.
        for(String property:List.of("password","careRegNo","providerId","updatedAt","email DESC; DELETE FROM users","created_at","class")) {
            var f=new Fixture();assertThrows(IllegalArgumentException.class,()->f.query.page(null,null,
                    new UserDirectoryQuery.Request(0,20,List.of(new UserDirectoryQuery.Order(property,true,false)))));
            assertTrue(f.jdbc.sql.isEmpty());assertEquals(List.of("begin","rollback","cleanup"),f.events);
        }
        var f=new Fixture();assertThrows(org.springframework.dao.InvalidDataAccessApiUsageException.class,()->f.query.page(null,null,request(Integer.MAX_VALUE,20)));
        assertTrue(f.jdbc.sql.isEmpty());
    }

    @ParameterizedTest @ValueSource(strings={"content","count","commit"})
    void given__content_count_or_commit_failure__when__reading__then__propagate_without_a_partial_page_or_retry(String phase) {
        var f=new Fixture();RuntimeException failure=phase.equals("commit")?new TransactionSystemException("synthetic-private"):new DataAccessResourceFailureException("synthetic-private");
        if(phase.equals("commit"))f.manager.failure=failure;else f.jdbc.failurePhase=phase;
        f.jdbc.failure=failure;assertSame(failure,assertThrows(RuntimeException.class,()->f.query.page(null,null,request(0,1))));
        assertEquals(1,f.events.stream().filter("begin"::equals).count());assertEquals("cleanup",f.events.getLast());
        assertEquals(phase.equals("commit")?"commit":"rollback",f.events.get(f.events.size()-2));
    }

    private static final class Fixture {
        final List<String> events=new ArrayList<>();final RecordingJdbc jdbc=new RecordingJdbc(events);final Manager manager=new Manager(events);
        final JdbcUserDirectoryQuery query;
        Fixture(){var tx=new TransactionTemplate(manager);tx.setReadOnly(true);tx.setTimeout(3);query=new JdbcUserDirectoryQuery(jdbc,tx);}
    }
    private static final class RecordingJdbc extends JdbcTemplate {
        final List<String> events;final List<String> sql=new ArrayList<>();final List<Object[]> arguments=new ArrayList<>();final List<String> zones=new ArrayList<>();
        List<UserProfile> rows=List.of(ROW);String failurePhase;RuntimeException failure;
        RecordingJdbc(List<String> events){this.events=events;}
        @Override public <T> List<T> query(String query,RowMapper<T> mapper,Object... args) {
            events.add("content");sql.add(query);arguments.add(args);if("content".equals(failurePhase))throw failure;
            var mapped=new ArrayList<T>();
            try {for(var user:rows){var row=mock(ResultSet.class);when(row.getLong("user_id")).thenReturn(user.userId());
                when(row.getString("email")).thenReturn(user.email());when(row.getString("name")).thenReturn(user.name());when(row.getString("nickname")).thenReturn(user.nickname());
                when(row.getString("provider")).thenReturn(user.provider());when(row.getString("role")).thenReturn(user.role());
                when(row.getTimestamp(eq("created_at"),any(Calendar.class))).thenAnswer(call->{zones.add(((Calendar)call.getArgument(1)).getTimeZone().getID());return user.createdAt()==null?null:Timestamp.valueOf(user.createdAt());});
                mapped.add(mapper.mapRow(row,mapped.size()));}return mapped;
            }catch(SQLException error){throw new IllegalStateException(error);}
        }
        @Override public <T> T queryForObject(String query,Class<T> type,Object... args) {
            events.add("count");sql.add(query);arguments.add(args);if("count".equals(failurePhase))throw failure;return type.cast(49L);
        }
    }
    private static final class Manager extends AbstractPlatformTransactionManager {
        final List<String> events;RuntimeException failure;boolean readOnly;int timeout;
        Manager(List<String> events){this.events=events;}
        protected Object doGetTransaction(){return new Object();}
        protected void doBegin(Object transaction,TransactionDefinition definition){readOnly=definition.isReadOnly();timeout=definition.getTimeout();events.add("begin");}
        protected void doCommit(DefaultTransactionStatus status){events.add("commit");if(failure!=null)throw failure;}
        protected void doRollback(DefaultTransactionStatus status){events.add("rollback");}
        protected void doCleanupAfterCompletion(Object transaction){events.add("cleanup");}
    }
}
