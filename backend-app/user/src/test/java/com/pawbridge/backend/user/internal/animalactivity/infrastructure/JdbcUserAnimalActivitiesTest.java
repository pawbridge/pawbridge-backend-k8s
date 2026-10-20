package com.pawbridge.backend.user.internal.animalactivity.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.user.internal.animalactivity.application.*;
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities.*;
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

class JdbcUserAnimalActivitiesTest {
    private static final LocalDateTime NOW=LocalDateTime.parse("2026-10-20T01:02:03.123456789");
    @Test void given__a_new_favorite__when__adding__then__keep_exists_user_insert_and_distinct_outbox_ids_in_one_write_transaction()throws Exception{
        var f=new Fixture();var row=f.service.add(7,-8);
        assertEquals(new Favorite(91L,7L,-8L,NOW),row);
        assertEquals(List.of("begin","exists","user","now","favorite-insert","uuid","now","uuid","now","outbox","commit","cleanup"),f.events);
        assertEquals("00000000-0000-0000-0000-000000000002",f.jdbc.outbox[0]);assertEquals("7",f.jdbc.outbox[1]);assertEquals("FAVORITE_ADDED",f.jdbc.outbox[2]);
        var payload=new ObjectMapper().readTree((String)f.jdbc.outbox[3]);assertEquals(5,payload.size());assertEquals("00000000-0000-0000-0000-000000000001",payload.get("eventId").asText());assertEquals(7,payload.get("userId").longValue());assertEquals(-8,payload.get("animalId").longValue());
        assertEquals("FAVORITE_ADDED",payload.get("eventType").asText());assertEquals(NOW.toString(),payload.get("timestamp").asText());
        assertTrue(f.jdbc.outboxSql.contains("'Favorite'"));assertTrue(f.jdbc.outboxSql.contains("'user.favorite.events'"));
        assertTrue(f.jdbc.sql.stream().noneMatch(sql->sql.contains("FOR UPDATE")));assertEquals(0,f.animalCalls);
        var statement=mock(PreparedStatement.class);((org.springframework.jdbc.support.SqlValue)f.jdbc.outbox[4]).setValue(statement,1);
        var calendar=org.mockito.ArgumentCaptor.forClass(Calendar.class);verify(statement).setTimestamp(eq(1),eq(Timestamp.valueOf(NOW)),calendar.capture());assertEquals("UTC",calendar.getValue().getTimeZone().getID());
    }
    @Test void given__a_duplicate_and_missing_user__when__adding__then__reject_duplicate_before_user_lookup_and_no_events(){
        var f=new Fixture();f.jdbc.exists=true;f.jdbc.user=false;assertReason("ALREADY_EXISTS",()->f.service.add(7,8));assertEquals(List.of("begin","exists","rollback","cleanup"),f.events);assertNull(f.jdbc.outbox);
    }
    @Test void given__a_missing_user__when__adding_or_listing__then__retain_source_user400_before_insert_or_animal(){
        var add=new Fixture();add.jdbc.user=false;assertThrows(UserNotFoundException.class,()->add.service.add(7,8));assertNull(add.jdbc.outbox);
        var list=new Fixture();list.jdbc.user=false;assertThrows(UserNotFoundException.class,()->list.service.favorites(7));assertEquals(0,list.animalCalls);
    }
    @ParameterizedTest @ValueSource(strings={"favorite","outbox","commit"})
    void given__a_unique_outbox_or_commit_failure__when__adding__then__distinguish_only_favorite409_and_propagate_other_failures_without_retry(String phase){
        var f=new Fixture();RuntimeException failure=new DataIntegrityViolationException("synthetic-private");
        if(phase.equals("commit"))f.manager.failure=failure;else f.jdbc.failure=phase;
        if(phase.equals("favorite"))assertReason("ALREADY_EXISTS",()->f.service.add(7,8));
        else if(phase.equals("commit"))assertSame(failure,assertThrows(RuntimeException.class,()->f.service.add(7,8)));
        else assertInstanceOf(DataIntegrityViolationException.class,assertThrows(IllegalStateException.class,()->f.service.add(7,8)).getCause());
        assertEquals(1,Collections.frequency(f.events,"begin"));assertEquals("cleanup",f.events.getLast());
    }
    @ParameterizedTest @ValueSource(ints={0,1})
    void given__an_existing_favorite__when__the_bulk_delete_races__then__return_success_and_emit_only_for_actual_deleted_rows(int deleted)throws Exception{
        var f=new Fixture();f.jdbc.exists=true;f.jdbc.deleted=deleted;f.service.remove(7,8);assertTrue(f.events.contains("delete"));
        if(deleted==0){assertNull(f.jdbc.outbox);assertEquals(0,f.uuidCalls);}else assertEquals("FAVORITE_REMOVED",new ObjectMapper().readTree((String)f.jdbc.outbox[3]).get("eventType").asText());
        assertFalse(f.events.contains("user"));assertEquals(0,f.animalCalls);
    }
    @Test void given__no_favorite__when__removing__then__return_not_found_without_delete_or_user_lookup(){
        var f=new Fixture();assertReason("NOT_FOUND",()->f.service.remove(7,8));assertEquals(List.of("begin","exists","rollback","cleanup"),f.events);
    }
    @Test void given__no_favorites__when__listing__then__return_empty_without_animal_http(){
        var f=new Fixture();var result=f.service.favorites(7);assertEquals(new Favorites(7L,0,List.of()),result);assertEquals(0,f.animalCalls);assertTrue(f.manager.readOnly);
    }
    @Test void given__ordered_favorites_and_an_absent_animal__when__joining_one_batch__then__preserve_order_and_exact_deleted_fallback(){
        var f=new Fixture();f.jdbc.favoriteRows=List.of(8L,9L);f.batch=List.of(animal(8));var result=f.service.favorites(7);
        assertEquals(List.of(8L,9L),result.favorites().stream().map(FavoriteAnimal::animalId).toList());assertEquals("DOG",result.favorites().getFirst().species());assertEquals("DELETED",result.favorites().getLast().status());assertNull(result.favorites().getLast().breed());assertEquals(1,f.animalCalls);assertEquals(List.of(8L,9L),f.batchIds);assertTrue(f.jdbc.sql.getLast().endsWith("ORDER BY created_at DESC"));
    }
    @ParameterizedTest @ValueSource(strings={"http","duplicate","null"})
    void given__a_batch_failure_or_duplicate_identity__when__listing__then__propagate_without_turning_failure_into_deleted_animals(String phase){
        var f=new Fixture();f.jdbc.favoriteRows=List.of(8L);f.batchFailure=phase.equals("http");f.batch=phase.equals("null")?null:List.of(animal(8),animal(8));assertThrows(RuntimeException.class,()->f.service.favorites(7));assertEquals(1,f.animalCalls);
    }
    @ParameterizedTest @ValueSource(strings={"missing","ROLE_USER","ROLE_ADMIN","no-registration"})
    void given__missing_or_non_shelter_database_membership__when__reading_registered_animals__then__preserve_source_failure_before_animal_http(String state){
        var f=new Fixture();if(state.equals("missing"))f.jdbc.user=false;else if(state.equals("no-registration"))f.jdbc.care="";else f.jdbc.role=state;
        if(state.equals("missing"))assertThrows(UserNotFoundException.class,()->f.service.registered(7,request()));else assertThrows(IllegalStateException.class,()->f.service.registered(7,request()));assertEquals(0,f.animalCalls);
    }
    @Test void given__shelter_membership__when__reading_registered_animals__then__keep_both_http_calls_inside_readonly_transaction_and_forward_raw_sort(){
        var f=new Fixture();var result=f.service.registered(7,request());assertSame(f.page,result);assertEquals(request(),f.request);assertEquals(List.of("begin","current","shelter","animals","commit","cleanup"),f.events);assertTrue(f.manager.readOnly);
    }
    @ParameterizedTest @ValueSource(strings={"shelter","animals"})
    void given__external_lookup_failure__when__reading_registered_animals__then__propagate_generic_runtime_and_no_empty_page(String phase){var f=new Fixture();f.externalFailure=phase;assertThrows(IllegalStateException.class,()->f.service.registered(7,request()));assertTrue(f.events.contains("rollback"));}
    private static PageRequest request(){return new PageRequest(3,20,"name,desc,createdAt,asc");}
    private static Animal animal(long id){return new Animal(id,"notice","DOG","breed","UNKNOWN",2025,1,"mark","PROTECT",LocalDate.of(2026,11,1),"https://fixture.invalid/image",2,4L,"shelter",NOW);}
    private static void assertReason(String reason,Runnable action){assertEquals(reason,assertThrows(FavoriteActivityRejectedException.class,action::run).reason().name());}
    private static final class Fixture {
        final List<String> events=new ArrayList<>();final FakeJdbc jdbc=new FakeJdbc(events);final Manager manager=new Manager(events);final JdbcUserAnimalActivities service;
        List<Animal> batch=List.of();List<Long> batchIds;boolean batchFailure;String externalFailure="";int animalCalls,uuidCalls;PageRequest request;final AnimalPage page=new AnimalPage(List.of(),0,20,0,0,true);
        Fixture(){var write=new TransactionTemplate(manager);var read=new TransactionTemplate(manager);read.setReadOnly(true);service=new JdbcUserAnimalActivities(jdbc,write,read,new AnimalLookup(){
            public List<Animal> batch(List<Long> ids){animalCalls++;batchIds=ids;assertTrue(TransactionSynchronizationManager.isActualTransactionActive());if(batchFailure)throw new IllegalStateException("private");return batch;}
            public Shelter shelter(String care){events.add("shelter");animalCalls++;assertEquals(" reg ",care);assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());if(externalFailure.equals("shelter"))throw new IllegalStateException("private");return new Shelter(4L,"name");}
            public AnimalPage byShelter(Long id,PageRequest value){events.add("animals");animalCalls++;request=value;if(externalFailure.equals("animals"))throw new IllegalStateException("private");assertEquals(4L,id);assertTrue(TransactionSynchronizationManager.isActualTransactionActive());return page;}
        },()->{events.add("uuid");return new UUID(0,++uuidCalls);},()->{events.add("now");return NOW;});}
    }
    private static final class FakeJdbc extends JdbcTemplate {
        final List<String> events;final List<String> sql=new ArrayList<>();boolean exists,user=true;int deleted=1;String failure="",role="ROLE_SHELTER",care=" reg ",outboxSql;Object[] outbox;List<Long> favoriteRows=List.of();
        FakeJdbc(List<String> events){this.events=events;}
        @Override public <T> T queryForObject(String query,Class<T> type,Object...args){sql.add(query);if(query.startsWith("INSERT")){events.add("favorite-insert");if(failure.equals("favorite"))throw new DataIntegrityViolationException("private");return type.cast(91L);}
            boolean users=query.contains("FROM pawbridge_user.users");events.add(users?"user":"exists");return type.cast(users?user:exists);}
        @Override public int update(String query,Object...args){sql.add(query);if(query.startsWith("DELETE")){events.add("delete");return deleted;}events.add("outbox");outboxSql=query;outbox=args;if(failure.equals("outbox"))throw new DataIntegrityViolationException("private");return 1;}
        @Override public <T> List<T> query(String query,RowMapper<T> mapper,Object...args){sql.add(query);try{if(query.contains("FROM pawbridge_user.users")){events.add("current");if(!user)return List.of();var rs=mock(ResultSet.class);when(rs.getString("role")).thenReturn(role);when(rs.getString("care_reg_no")).thenReturn(care);return List.of(mapper.mapRow(rs,0));}
            events.add("favorites");var result=new ArrayList<T>();for(long id:favoriteRows){var rs=mock(ResultSet.class);when(rs.getLong("favorite_id")).thenReturn(id+100);when(rs.getLong("user_id")).thenReturn(7L);when(rs.getLong("animal_id")).thenReturn(id);when(rs.getTimestamp(eq("created_at"),any(Calendar.class))).thenReturn(Timestamp.valueOf(NOW));result.add(mapper.mapRow(rs,result.size()));}return result;
        }catch(SQLException e){throw new IllegalStateException(e);}}
    }
    private static final class Manager extends AbstractPlatformTransactionManager {
        final List<String> events;boolean readOnly;RuntimeException failure;Manager(List<String> events){this.events=events;}protected Object doGetTransaction(){return new Object();}protected void doBegin(Object tx,TransactionDefinition d){readOnly=d.isReadOnly();events.add("begin");}
        protected void doCommit(DefaultTransactionStatus s){events.add("commit");if(failure!=null)throw failure;}protected void doRollback(DefaultTransactionStatus s){events.add("rollback");}protected void doCleanupAfterCompletion(Object tx){events.add("cleanup");}
    }
}
