package com.pawbridge.backend.user.internal.shelter.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.pawbridge.backend.user.internal.shelter.application.*;
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

class JdbcShelterMembershipTest {
    private static final LocalDateTime NOW=LocalDateTime.parse("2026-10-20T01:02:03.123456");
    @ParameterizedTest @ValueSource(strings={"ROLE_USER","ROLE_ADMIN","ROLE_SHELTER"})
    void given__a_current_role__when__submitting__then__lock_before_role_pending_text_and_insert(String role){
        var f=new Fixture();f.jdbc.actorRole=role;
        if(role.equals("ROLE_USER")){var result=f.service.submit(7,"  보호소  ");assertEquals("보호소",result.shelterName());assertEquals(Status.PENDING,result.status());assertEquals(91L,result.id());
            assertEquals(List.of("begin","actor-lock","count","now","insert","commit","cleanup"),f.events);assertEquals(1,f.nowCalls);}
        else {assertReason("FORBIDDEN",()->f.service.submit(7," "));assertEquals(List.of("begin","actor-lock","rollback","cleanup"),f.events);}
    }
    @Test void given__a_pending_request__when__submitting_invalid_text__then__preserve_conflict_before_text_validation(){
        var f=new Fixture();f.jdbc.pending=1;assertReason("CONFLICT",()->f.service.submit(7," "));assertEquals(0,f.nowCalls);assertFalse(f.events.contains("insert"));
    }
    @Test void given__a_pending_applicant__when__approving__then__lock_user_before_refreshed_application_and_call_animal_inside_the_same_transaction(){
        var f=new Fixture();var result=f.service.approve(1,91," reg "," 승인 사유 ");
        assertEquals(Status.APPROVED,result.application().status());assertNull(result.application().reason());assertEquals("승인 사유",result.reviewNote());assertEquals("reg",result.application().careRegNo());
        assertEquals(List.of("begin","actor","application","applicant-lock","application-lock","animal","now","application-update","now","user-update","commit","cleanup"),f.events);
        assertEquals(2,f.nowCalls);assertEquals(2,f.jdbc.writes.size());assertFalse(f.jdbc.writes.get(1).contains("nickname"));assertFalse(f.jdbc.writes.get(1).contains("password"));
    }
    @ParameterizedTest @ValueSource(strings={"processed","role","missing","vanished"})
    void given__a_refreshed_state_or_missing_applicant__when__approving__then__stop_before_animal_and_any_write(String state){
        var f=new Fixture();f.jdbc.state=state;
        if(state.equals("vanished"))assertThrows(org.springframework.dao.OptimisticLockingFailureException.class,()->f.service.approve(1,91,"reg","note"));
        else assertReason(state.equals("missing")?"NOT_FOUND":"CONFLICT",()->f.service.approve(1,91,"reg","note"));
        assertFalse(f.events.contains("animal"));assertTrue(f.jdbc.writes.isEmpty());assertTrue(f.events.contains("rollback"));
    }
    @ParameterizedTest @ValueSource(strings={"404","runtime","null","wrong"})
    void given__animal_failure_or_invalid_identity__when__approving__then__return_only_original_business_failure_without_writes(String phase){
        var f=new Fixture();f.animal=phase;assertReason(phase.equals("404")?"SHELTER_NOT_FOUND":"SHELTER_UNAVAILABLE",()->f.service.approve(1,91,"reg","note"));
        assertTrue(f.jdbc.writes.isEmpty());assertEquals(0,f.nowCalls);
    }
    @Test void given__a_pending_request__when__rejecting__then__validate_reason_after_locks_and_leave_user_role_untouched(){
        var invalid=new Fixture();assertReason("INVALID",()->invalid.service.reject(1,91," "));assertTrue(invalid.events.contains("application-lock"));assertTrue(invalid.jdbc.writes.isEmpty());
        var f=new Fixture();var result=f.service.reject(1,91," 이유 ");assertEquals("이유",result.application().reason());assertEquals(Status.REJECTED,result.application().status());
        assertEquals(1,f.jdbc.writes.size());assertFalse(f.events.contains("animal"));assertFalse(f.events.contains("user-update"));
    }
    @Test void given__a_changed_database_admin_role__when__calling_admin_operations__then__reject_before_target_or_input_queries(){
        var f=new Fixture();f.jdbc.actorRole="ROLE_USER";assertReason("FORBIDDEN",()->f.service.approve(7,91," "," "));
        assertEquals(List.of("begin","actor","rollback","cleanup"),f.events);
    }
    @Test void given__an_own_page_for_any_existing_role__when__listing__then__authenticate_before_page_validation_and_map_without_admin_requirement(){
        for(String role:List.of("ROLE_USER","ROLE_ADMIN","ROLE_SHELTER")){var f=new Fixture();f.jdbc.actorRole=role;var result=f.service.mine(7,0,20);assertEquals(1,result.total());assertEquals(1,result.content().size());
            assertEquals(List.of("begin","actor","application-page","commit","cleanup"),f.events);}
        var missing=new Fixture();missing.jdbc.actorMissing=true;assertReason("TOKEN_INVALID",()->missing.service.mine(7,-1,0));
    }
    @Test void given__an_admin_page_and_members__when__enriching__then__keep_one_user_batch_and_each_members_latest_approval_lookup(){
        var f=new Fixture();assertEquals("신청자",f.service.list(1,null,0,20).content().getFirst().applicantName());assertEquals(1,Collections.frequency(f.events,"user-batch"));
        var member=new Fixture();var result=member.service.members(1," reg ",0,20);assertEquals(91L,result.content().getFirst().approvalApplicationId());assertEquals(1,Collections.frequency(member.events,"latest-approval"));
    }
    @Test void given__a_seoul_day_and_sparse_rows__when__reading_statistics__then__use_repeatable_read_and_preserve_previous_day_current_pending_and_query_order(){
        var f=new Fixture();LocalDate day=LocalDate.parse("2026-10-20");var result=f.service.statistics(1,day,day);
        assertEquals(2,result.previousDayCount());assertEquals(List.of(new Daily(day,3)),result.daily());assertEquals(4,result.approvedCount());assertEquals(5,result.rejectedCount());
        assertEquals(List.of("begin","actor","daily","reviews","count","commit","cleanup"),f.events);assertTrue(f.manager.readOnly);assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ,f.manager.isolation);
        var accepted=new Fixture();accepted.service.statistics(1,day.minusDays(365),day);
    }
    @ParameterizedTest @ValueSource(strings={"366","future","reverse","null"})
    void given__invalid_statistics_dates__when__querying__then__reject_after_current_admin_before_aggregate_sql(String mode){
        var f=new Fixture();LocalDate day=LocalDate.parse("2026-10-20"),start=day,end=day;
        switch(mode){case "366"->start=day.minusDays(366);case "future"->end=day.plusDays(1);case "reverse"->start=day.plusDays(1);default->start=null;}
        LocalDate from=start,to=end;assertReason("INVALID_INPUT",()->f.service.statistics(1,from,to));assertEquals(List.of("begin","actor","rollback","cleanup"),f.events);
    }
    @ParameterizedTest @ValueSource(strings={"second-write","commit"})
    void given__a_late_write_or_commit_failure__when__approving__then__propagate_without_a_partial_response_or_retry(String phase){
        var f=new Fixture();RuntimeException failure=new org.springframework.transaction.TransactionSystemException("synthetic-private");
        if(phase.equals("commit"))f.manager.failure=failure;else f.jdbc.failure=failure;
        assertSame(failure,assertThrows(RuntimeException.class,()->f.service.approve(1,91,"reg","note")));assertEquals(1,Collections.frequency(f.events,"begin"));assertEquals("cleanup",f.events.getLast());
    }
    @Test void given__local_microseconds__when__binding_a_timestamp__then__use_utc_calendar_without_reading_unneeded_user_columns()throws Exception{
        var f=new Fixture();f.service.submit(7,"이름");var timestamp=(org.springframework.jdbc.support.SqlValue)f.jdbc.insertArgs[2];var statement=mock(PreparedStatement.class);timestamp.setValue(statement,1);
        var calendar=org.mockito.ArgumentCaptor.forClass(Calendar.class);verify(statement).setTimestamp(eq(1),eq(Timestamp.valueOf(NOW)),calendar.capture());assertEquals("UTC",calendar.getValue().getTimeZone().getID());
    }
    private static void assertReason(String reason,Runnable action){assertEquals(reason,assertThrows(ShelterMembershipRejectedException.class,action::run).reason().name());}
    private static final class Fixture {
        final List<String> events=new ArrayList<>();final FakeJdbc jdbc=new FakeJdbc(events);final Manager manager=new Manager(events);final JdbcShelterMembership service;String animal="ok";int nowCalls;
        Fixture(){var tx=new TransactionTemplate(manager);var read=new TransactionTemplate(manager);read.setReadOnly(true);var stats=new TransactionTemplate(manager);stats.setReadOnly(true);stats.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            service=new JdbcShelterMembership(jdbc,tx,read,stats,care->{events.add("animal");assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                return switch(animal){case "404"->throw new MissingShelter();case "runtime"->throw new IllegalStateException("private");case "null"->null;case "wrong"->new Shelter(8L,"other");default->new Shelter(8L,care);};},
                    Clock.fixed(Instant.parse("2026-10-19T15:30:00Z"),ZoneOffset.UTC),()->{events.add("now");nowCalls++;return NOW;});}
    }
    private static final class FakeJdbc extends JdbcTemplate {
        final List<String> events;final List<String> writes=new ArrayList<>();String actorRole="ROLE_USER",state="ok";boolean actorMissing;long pending;RuntimeException failure;Object[] insertArgs;
        FakeJdbc(List<String> events){this.events=events;}
        @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object...args){try{
            var rs=mock(ResultSet.class);
            if(sql.contains("FROM pawbridge_user.users")){
                boolean actor=!sql.contains(" IN (")&&!sql.contains("care_reg_no=?")&&((Number)args[0]).longValue()!=8;
                if(sql.contains(" IN ("))events.add("user-batch");else if(sql.contains("care_reg_no=?"))events.add("members");else events.add(actor?(sql.contains("FOR UPDATE")?"actor-lock":"actor"):"applicant-lock");
                if((actor&&actorMissing)||(!actor&&state.equals("missing")))return List.of();
                when(rs.getLong("user_id")).thenReturn(actor?((Number)args[0]).longValue():8L);when(rs.getString("name")).thenReturn("신청자");when(rs.getString("email")).thenReturn("fixture@example.invalid");
                when(rs.getString("role")).thenReturn(actor?(((Number)args[0]).longValue()==1?"ROLE_ADMIN":actorRole):(state.equals("role")?"ROLE_SHELTER":"ROLE_USER"));
            }else if(sql.contains("requested_date")){events.add("daily");var result=new ArrayList<T>();for(int day=19;day<=20;day++){when(rs.getObject("requested_date",LocalDate.class)).thenReturn(LocalDate.of(2026,10,day));when(rs.getLong("requested_count")).thenReturn(day==19?2L:3L);result.add(mapper.mapRow(rs,result.size()));}return result;
            }else if(sql.contains("review_count")){events.add("reviews");var result=new ArrayList<T>();for(String status:List.of("APPROVED","REJECTED")){when(rs.getString("status")).thenReturn(status);when(rs.getLong("review_count")).thenReturn(status.equals("APPROVED")?4L:5L);result.add(mapper.mapRow(rs,result.size()));}return result;
            }else if(sql.startsWith("SELECT id FROM")){events.add("latest-approval");when(rs.getLong("id")).thenReturn(91L);
            }else {boolean lock=sql.contains("FOR UPDATE");events.add(sql.contains("LIMIT")?"application-page":lock?"application-lock":"application");if(lock&&state.equals("vanished"))return List.of();
                when(rs.getLong("id")).thenReturn(91L);when(rs.getLong("user_id")).thenReturn(8L);when(rs.getString("shelter_name")).thenReturn("보호소");when(rs.getString("status")).thenReturn(lock&&state.equals("processed")?"REJECTED":"PENDING");
                when(rs.getTimestamp(eq("requested_at"),any(Calendar.class))).thenReturn(Timestamp.valueOf(NOW));}
            return List.of(mapper.mapRow(rs,0));
        }catch(SQLException e){throw new IllegalStateException(e);}}
        @Override public <T> T queryForObject(String sql,Class<T> type,Object...args){if(sql.startsWith("INSERT")){events.add("insert");insertArgs=new Object[]{args[0],args[1],args[2]};return type.cast(91L);}events.add("count");return type.cast(pending);}
        @Override public int update(String sql,Object...args){writes.add(sql);boolean user=sql.startsWith("UPDATE pawbridge_user.users");events.add(user?"user-update":"application-update");if(user&&failure!=null)throw failure;return 1;}
    }
    private static final class Manager extends AbstractPlatformTransactionManager {
        final List<String> events;RuntimeException failure;boolean readOnly;int isolation;
        Manager(List<String> events){this.events=events;}protected Object doGetTransaction(){return new Object();}
        protected void doBegin(Object tx,TransactionDefinition d){readOnly=d.isReadOnly();isolation=d.getIsolationLevel();events.add("begin");}
        protected void doCommit(DefaultTransactionStatus s){events.add("commit");if(failure!=null)throw failure;}protected void doRollback(DefaultTransactionStatus s){events.add("rollback");}
        protected void doCleanupAfterCompletion(Object tx){events.add("cleanup");}
    }
}
