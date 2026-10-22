package com.pawbridge.backend.user.internal.animalactivity.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.pawbridge.backend.animal.api.AnimalFavoriteCounts;
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
    @Test void given__a_new_favorite__when__adding__then__insert_the_favorite_and_increment_the_count_in_one_write_transaction(){
        var f=new Fixture();var row=f.service.add(7,-8);
        assertEquals(new Favorite(91L,7L,-8L,NOW),row);
        assertEquals(List.of("begin","exists","user","now","favorite-insert","count","commit","cleanup"),f.events);
        assertEquals(List.of(List.of(-8L,1)),f.adjustments);
        assertTrue(f.jdbc.sql.stream().noneMatch(sql->sql.contains("outbox_events")||sql.contains("FOR UPDATE")));assertEquals(0,f.animalCalls);
    }
    @Test void given__a_duplicate_and_missing_user__when__adding__then__reject_duplicate_before_user_lookup_and_no_count_change(){
        var f=new Fixture();f.jdbc.exists=true;f.jdbc.user=false;assertReason("ALREADY_EXISTS",()->f.service.add(7,8));assertEquals(List.of("begin","exists","rollback","cleanup"),f.events);assertTrue(f.adjustments.isEmpty());
    }
    @Test void given__a_missing_user__when__adding_or_listing__then__retain_source_user400_before_insert_or_animal(){
        var add=new Fixture();add.jdbc.user=false;assertThrows(UserNotFoundException.class,()->add.service.add(7,8));assertTrue(add.adjustments.isEmpty());assertFalse(add.events.contains("favorite-insert"));
        var list=new Fixture();list.jdbc.user=false;assertThrows(UserNotFoundException.class,()->list.service.favorites(7));assertEquals(0,list.animalCalls);
    }
    @Test void given__a_unique_violation_on_insert__when__adding__then__map_to_409_without_changing_the_count(){
        var f=new Fixture();f.jdbc.failure="favorite";assertReason("ALREADY_EXISTS",()->f.service.add(7,8));
        assertTrue(f.adjustments.isEmpty());assertTrue(f.events.contains("rollback"));assertFalse(f.events.contains("commit"));assertEquals("cleanup",f.events.getLast());
    }
    @Test void given__a_commit_failure__when__adding__then__propagate_it_unchanged_without_retry(){
        var f=new Fixture();RuntimeException failure=new DataIntegrityViolationException("synthetic-private");f.manager.failure=failure;
        assertSame(failure,assertThrows(RuntimeException.class,()->f.service.add(7,8)));assertEquals(1,Collections.frequency(f.events,"begin"));assertEquals("cleanup",f.events.getLast());
    }
    @Test void given__a_missing_animal__when__adding__then__reject_404_and_roll_back_the_favorite_row(){
        var f=new Fixture();f.animalExists=false;assertReason("ANIMAL_NOT_FOUND",()->f.service.add(7,8));
        assertEquals(List.of("begin","exists","user","now","favorite-insert","count","rollback","cleanup"),f.events);
        assertEquals(404,FavoriteActivityRejectedException.Reason.ANIMAL_NOT_FOUND.status());
    }
    @Test void given__a_count_failure__when__adding__then__propagate_it_as_is_not_as_409_and_roll_back(){
        var f=new Fixture();RuntimeException failure=new DataIntegrityViolationException("synthetic-private");f.countFailure=failure;
        assertSame(failure,assertThrows(RuntimeException.class,()->f.service.add(7,8)));assertTrue(f.events.contains("rollback"));assertFalse(f.events.contains("commit"));
    }
    @ParameterizedTest @ValueSource(ints={0,1})
    void given__an_existing_favorite__when__the_bulk_delete_races__then__return_success_and_decrement_only_for_actual_deleted_rows(int deleted){
        var f=new Fixture();f.jdbc.exists=true;f.jdbc.deleted=deleted;f.service.remove(7,8);assertTrue(f.events.contains("delete"));
        assertEquals(deleted==0?List.of():List.of(List.of(8L,-1)),f.adjustments);assertFalse(f.events.contains("user"));assertEquals(0,f.animalCalls);assertEquals("commit",f.events.get(f.events.size()-2));
    }
    @Test void given__a_favorite_of_a_deleted_animal__when__removing__then__still_delete_the_favorite_and_commit(){
        var f=new Fixture();f.jdbc.exists=true;f.animalExists=false;f.service.remove(7,8);
        assertEquals(List.of("begin","exists","delete","count","commit","cleanup"),f.events);
    }
    @Test void given__a_count_failure__when__removing__then__roll_back_the_delete_and_propagate(){
        var f=new Fixture();f.jdbc.exists=true;RuntimeException failure=new IllegalStateException("private");f.countFailure=failure;
        assertSame(failure,assertThrows(RuntimeException.class,()->f.service.remove(7,8)));assertTrue(f.events.contains("rollback"));assertFalse(f.events.contains("commit"));
    }
    @Test void given__no_favorite__when__removing__then__return_not_found_without_delete_or_user_lookup(){
        var f=new Fixture();assertReason("NOT_FOUND",()->f.service.remove(7,8));assertEquals(List.of("begin","exists","rollback","cleanup"),f.events);assertTrue(f.adjustments.isEmpty());
    }
    @Test void given__no_favorites__when__listing__then__return_empty_without_animal_http(){
        var f=new Fixture();var result=f.service.favorites(7);assertEquals(new Favorites(7L,0,List.of()),result);assertEquals(0,f.animalCalls);assertTrue(f.manager.readOnly);
    }
    @Test void given__ordered_favorites_and_an_absent_animal__when__joining_one_batch__then__preserve_order_and_exact_deleted_fallback(){
        var f=new Fixture();f.jdbc.favoriteRows=List.of(8L,9L);f.batch=List.of(animal(8));var result=f.service.favorites(7);
        assertEquals(List.of(8L,9L),result.favorites().stream().map(FavoriteAnimal::animalId).toList());assertEquals("DOG",result.favorites().getFirst().species());assertEquals("DELETED",result.favorites().getLast().status());assertNull(result.favorites().getLast().breed());assertEquals(1,f.animalCalls);assertEquals(List.of(8L,9L),f.batchIds);assertTrue(f.jdbc.sql.getLast().endsWith("ORDER BY created_at DESC"));
    }
    @Test void given__favorites__when__listing__then__read_the_animal_batch_only_after_the_user_read_transaction_completed(){
        var f=new Fixture();f.jdbc.favoriteRows=List.of(8L);f.batch=List.of(animal(8));f.service.favorites(7);
        assertEquals(List.of("begin","user","favorites","commit","cleanup"),f.events);assertEquals(1,f.animalCalls);
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
    @Test void given__shelter_membership__when__reading_registered_animals__then__run_both_lookups_after_the_readonly_transaction_and_forward_raw_sort(){
        var f=new Fixture();var result=f.service.registered(7,request());assertSame(f.page,result);assertEquals(request(),f.request);// Animal lookups are independent transactions; nesting them would hold two connections of the shared pool.
        assertEquals(List.of("begin","current","commit","cleanup","shelter","animals"),f.events);assertTrue(f.manager.readOnly);
    }
    @ParameterizedTest @ValueSource(strings={"shelter","animals"})
    void given__external_lookup_failure__when__reading_registered_animals__then__propagate_generic_runtime_and_no_empty_page(String phase){var f=new Fixture();f.externalFailure=phase;assertThrows(IllegalStateException.class,()->f.service.registered(7,request()));assertEquals(List.of("begin","current","commit","cleanup"),f.events.subList(0,4));}
    private static PageRequest request(){return new PageRequest(3,20,"name,desc,createdAt,asc");}
    private static Animal animal(long id){return new Animal(id,"notice","DOG","breed","UNKNOWN",2025,1,"mark","PROTECT",LocalDate.of(2026,11,1),"https://fixture.invalid/image",2,4L,"shelter",NOW);}
    private static void assertReason(String reason,Runnable action){assertEquals(reason,assertThrows(FavoriteActivityRejectedException.class,action::run).reason().name());}
    private static final class Fixture {
        final List<String> events=new ArrayList<>();final FakeJdbc jdbc=new FakeJdbc(events);final Manager manager=new Manager(events);final JdbcUserAnimalActivities service;
        List<Animal> batch=List.of();List<Long> batchIds;boolean batchFailure;String externalFailure="";int animalCalls;boolean animalExists=true;RuntimeException countFailure;final List<List<Object>> adjustments=new ArrayList<>();PageRequest request;final AnimalPage page=new AnimalPage(List.of(),0,20,0,0,true);
        Fixture(){var write=new TransactionTemplate(manager);var read=new TransactionTemplate(manager);read.setReadOnly(true);service=new JdbcUserAnimalActivities(jdbc,write,read,new AnimalLookup(){
            public List<Animal> batch(List<Long> ids){animalCalls++;batchIds=ids;assertFalse(TransactionSynchronizationManager.isActualTransactionActive());if(batchFailure)throw new IllegalStateException("private");return batch;}
            public Shelter shelter(String care){events.add("shelter");animalCalls++;assertEquals(" reg ",care);assertFalse(TransactionSynchronizationManager.isActualTransactionActive());if(externalFailure.equals("shelter"))throw new IllegalStateException("private");return new Shelter(4L,"name");}
            public AnimalPage byShelter(Long id,PageRequest value){events.add("animals");animalCalls++;request=value;if(externalFailure.equals("animals"))throw new IllegalStateException("private");assertEquals(4L,id);assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return page;}
        },(animal,delta)->{events.add("count");adjustments.add(List.of(animal,delta));if(countFailure!=null)throw countFailure;return animalExists;},()->{events.add("now");return NOW;});}
    }
    private static final class FakeJdbc extends JdbcTemplate {
        final List<String> events;final List<String> sql=new ArrayList<>();boolean exists,user=true;int deleted=1;String failure="",role="ROLE_SHELTER",care=" reg ";List<Long> favoriteRows=List.of();
        FakeJdbc(List<String> events){this.events=events;}
        @Override public <T> T queryForObject(String query,Class<T> type,Object...args){sql.add(query);if(query.startsWith("INSERT")){events.add("favorite-insert");if(failure.equals("favorite"))throw new DataIntegrityViolationException("private");return type.cast(91L);}
            boolean users=query.contains("FROM pawbridge_user.users");events.add(users?"user":"exists");return type.cast(users?user:exists);}
        @Override public int update(String query,Object...args){sql.add(query);if(query.startsWith("DELETE")){events.add("delete");return deleted;}throw new AssertionError("unexpected update: "+query);}
        @Override public <T> List<T> query(String query,RowMapper<T> mapper,Object...args){sql.add(query);try{if(query.contains("FROM pawbridge_user.users")){events.add("current");if(!user)return List.of();var rs=mock(ResultSet.class);when(rs.getString("role")).thenReturn(role);when(rs.getString("care_reg_no")).thenReturn(care);return List.of(mapper.mapRow(rs,0));}
            events.add("favorites");var result=new ArrayList<T>();for(long id:favoriteRows){var rs=mock(ResultSet.class);when(rs.getLong("favorite_id")).thenReturn(id+100);when(rs.getLong("user_id")).thenReturn(7L);when(rs.getLong("animal_id")).thenReturn(id);when(rs.getTimestamp(eq("created_at"),any(Calendar.class))).thenReturn(Timestamp.valueOf(NOW));result.add(mapper.mapRow(rs,result.size()));}return result;
        }catch(SQLException e){throw new IllegalStateException(e);}}
    }
    private static final class Manager extends AbstractPlatformTransactionManager {
        final List<String> events;boolean readOnly;RuntimeException failure;Manager(List<String> events){this.events=events;}protected Object doGetTransaction(){return new Object();}protected void doBegin(Object tx,TransactionDefinition d){readOnly=d.isReadOnly();events.add("begin");}
        protected void doCommit(DefaultTransactionStatus s){events.add("commit");if(failure!=null)throw failure;}protected void doRollback(DefaultTransactionStatus s){events.add("rollback");}protected void doCleanupAfterCompletion(Object tx){events.add("cleanup");}
    }
}
