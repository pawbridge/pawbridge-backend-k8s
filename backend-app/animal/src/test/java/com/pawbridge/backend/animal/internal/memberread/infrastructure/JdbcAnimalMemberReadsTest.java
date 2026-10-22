package com.pawbridge.backend.animal.internal.memberread.infrastructure;

import com.pawbridge.backend.animal.api.AnimalMemberReads.*;
import com.pawbridge.backend.animal.api.AnimalMemberReadUnavailableException;
import com.pawbridge.backend.animal.api.AnimalShelterReads;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionOperations;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JdbcAnimalMemberReadsTest {
    private final NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
    private final AnimalShelterReads shelters = mock(AnimalShelterReads.class);
    private final JdbcAnimalMemberReads reads = new JdbcAnimalMemberReads(jdbc, TransactionOperations.withoutTransaction(), shelters);
    private static Animal row(long id) { return new Animal(id,null,"DOG",null,null,null,null,null,"ADOPTED",null,null,null,null,null,null); }
    private void rows(List<Animal> rows) {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Animal>>any())).thenReturn(rows);
    }
    @Test void givenSourceSupportedCollectionSort__whenRegisteredPage__thenDeduplicateRootsAfterSqlLimitBeforeCountInference() {
        rows(List.of(row(8),row(8)));
        var result=reads.byShelter(7L,new PageRequest(0,2,"shelter.animals.id,asc"));
        assertThat(result).isEqualTo(new Page(List.of(row(8)),0,2,1,1,true));
        verify(jdbc).query(argThat((String sql)->sql.contains("LEFT JOIN pawbridge_animal.animals sort_0 ON sort_0.shelter_id=s.id")
                && sql.endsWith(" ORDER BY sort_0.id ASC LIMIT :limit OFFSET :offset")
                && !sql.contains("DISTINCT") && !sql.contains("GROUP BY") && !sql.contains("sort_0.api_source") && !sql.contains("sort_0.status")),
                any(MapSqlParameterSource.class),org.mockito.ArgumentMatchers.<RowMapper<Animal>>any());
        verify(jdbc,never()).queryForObject(anyString(),any(MapSqlParameterSource.class),eq(Long.class));
    }
    @ParameterizedTest @MethodSource("collectionSortPaths")
    void givenSourceAssociationPath__whenSorting__thenPreserveGrammarTerminalEntityAndNestedJoin(String sort,String order) {
        rows(List.of(row(8)));
        assertThat(reads.byShelter(7L,new PageRequest(0,2,sort)).content()).containsExactly(row(8));
        verify(jdbc).query(argThat((String sql)->sql.contains("LEFT JOIN pawbridge_animal.animals sort_0 ON sort_0.shelter_id=s.id")
                && sql.endsWith(" ORDER BY "+order+" LIMIT :limit OFFSET :offset")
                && !sql.contains("DISTINCT") && !sql.contains("NULLS") && !sql.contains("sort_0.api_source=") && !sql.contains("sort_0.status=")),
                any(MapSqlParameterSource.class),org.mockito.ArgumentMatchers.<RowMapper<Animal>>any());
    }
    static Stream<Arguments> collectionSortPaths() {
        return Stream.of(
                Arguments.of("shelter.animals.id,asc","sort_0.id ASC"),
                Arguments.of("shelter_animals_id,asc","sort_0.id ASC"),
                Arguments.of("shelterAnimalsId,asc","sort_0.id ASC"),
                Arguments.of("Shelter_Animals_Id,asc","sort_0.id ASC"),
                Arguments.of("shelter.animals,asc","sort_0.id ASC"),
                Arguments.of("shelter.animals.shelter.name,asc","sort_1.name ASC"),
                Arguments.of("shelterAnimalsShelterName,asc","sort_1.name ASC"),
                Arguments.of("shelter.animals.shelter,asc","sort_1.id ASC"),
                Arguments.of("shelter.animals.shelter.animals.breed,asc","sort_2.breed ASC"),
                Arguments.of("shelterAnimalsShelterAnimalsSpecies,asc","sort_2.species ASC"),
                Arguments.of("shelter.animals.weight,desc,ignorecase","lower(sort_0.weight) DESC"),
                Arguments.of("shelter.animals.status,desc,ignorecase","sort_0.status DESC"));
    }
    @Test void givenSharedCollectionPrefixes__whenOrderingMultipleProperties__thenReuseJoinsAndOriginalOrderWithoutExtraFilters() {
        rows(List.of(row(9),row(9),row(8)));
        var page=reads.byShelter(7L,new PageRequest(2,3,"shelter.animals.breed,shelter.animals.shelter.name,asc"));
        assertThat(page).isEqualTo(new Page(List.of(row(9),row(8)),2,3,8,3,true));
        verify(jdbc).query(argThat((String sql)->sql.contains("LEFT JOIN pawbridge_animal.animals sort_0 ON sort_0.shelter_id=s.id")
                && sql.contains("LEFT JOIN pawbridge_animal.shelters sort_1 ON sort_1.id=sort_0.shelter_id")
                && sql.endsWith(" ORDER BY sort_0.breed ASC,sort_1.name ASC LIMIT :limit OFFSET :offset")
                && !sql.contains("sort_2") && !sql.contains("DISTINCT") && !sql.contains("sort_0.api_source") && !sql.contains("sort_0.status")),
                argThat((MapSqlParameterSource params)->params.getValue("offset").equals(6L)),org.mockito.ArgumentMatchers.<RowMapper<Animal>>any());
        verify(jdbc,never()).queryForObject(anyString(),any(MapSqlParameterSource.class),eq(Long.class));
    }
    @Test void givenCollectionFullAndEmptyLaterPages__whenCounting__thenCountRootManualAnimalsWithoutSortJoins() {
        when(jdbc.queryForObject(anyString(),any(MapSqlParameterSource.class),eq(Long.class))).thenReturn(99L);
        rows(List.of(row(9),row(8)));
        var page=reads.byShelter(7L,new PageRequest(1,2,"shelter.animals.id,asc"));
        assertThat(page).isEqualTo(new Page(List.of(row(9),row(8)),1,2,99,50,false));
        rows(List.of()); page=reads.byShelter(7L,new PageRequest(50,2,"shelter.animals.id,asc"));
        assertThat(page).isEqualTo(new Page(List.of(),50,2,99,50,true));
        verify(jdbc,times(2)).queryForObject(eq("SELECT count(*) FROM pawbridge_animal.animals a WHERE a.shelter_id=:shelterId AND a.api_source='MANUAL'"),
                any(MapSqlParameterSource.class),eq(Long.class));
    }
    @Test void givenNullOrEmptyIds__whenBatch__thenNoQueryAndEmptyMutableList() {
        reads.batch(null).add(row(1));
        assertThat(reads.batch(List.of())).isEmpty();
        verifyNoInteractions(jdbc, shelters);
    }
    @Test void givenDuplicateMissingAndNullIds__whenBatch__thenUseOneInQueryWithoutSourceStatusOrOrderPolicy() {
        List<Long> ids = new ArrayList<>(); ids.add(8L); ids.add(8L); ids.add(404L); ids.add(null);
        rows(List.of(row(8)));
        assertThat(reads.batch(ids)).containsExactly(row(8));
        var sql = ArgumentCaptor.forClass(String.class); var params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(sql.capture(), params.capture(), org.mockito.ArgumentMatchers.<RowMapper<Animal>>any());
        assertThat(sql.getValue()).contains("LEFT JOIN pawbridge_animal.shelters", " WHERE a.id IN (:ids)")
                .doesNotContain("ORDER BY", "a.api_source=", "a.status=", "DISTINCT", "LIMIT");
        assertThat(params.getValue().getValue("ids")).isEqualTo(ids);
        verifyNoMoreInteractions(jdbc);
    }
    @Test void givenNullableAndPopulatedRow__whenMapping__thenRetainAll15FieldsAndMapperAge() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        assertThat(JdbcAnimalMemberReads.animal(rs,0)).isEqualTo(new Animal(null,null,null,null,null,null,null,null,null,null,null,null,null,null,null));
        LocalDate end=LocalDate.of(2026,12,31); LocalDateTime created=LocalDateTime.of(2026,10,20,2,3,4,123456000);
        when(rs.getObject("id",Long.class)).thenReturn(9L); when(rs.getString("apms_notice_no")).thenReturn("공고");
        when(rs.getString("species")).thenReturn("CAT"); when(rs.getString("breed")).thenReturn("품종");
        when(rs.getString("gender")).thenReturn("UNKNOWN"); when(rs.getObject("birth_year",Integer.class)).thenReturn(2020);
        when(rs.getString("special_mark")).thenReturn("특징"); when(rs.getString("status")).thenReturn("ADOPTION_PENDING");
        when(rs.getObject("notice_end_date",LocalDate.class)).thenReturn(end); when(rs.getString("image_url")).thenReturn("image");
        when(rs.getObject("favorite_count",Integer.class)).thenReturn(3); when(rs.getObject("shelter_id",Long.class)).thenReturn(7L);
        when(rs.getString("shelter_name")).thenReturn("센터"); when(rs.getObject("created_at",LocalDateTime.class)).thenReturn(created);
        assertThat(JdbcAnimalMemberReads.animal(rs,0)).isEqualTo(new Animal(9L,"공고","CAT","품종","UNKNOWN",2020,
                LocalDate.now().getYear()-2020,"특징","ADOPTION_PENDING",end,"image",3,7L,"센터",created));
    }
    @Test void givenPartialLaterPage__whenByShelter__thenExactManualPredicateAndCountInferredWithoutStatusFilter() {
        rows(List.of(row(8)));
        var result=reads.byShelter(7L,new PageRequest(2,20,"createdAt,desc"));
        assertThat(result).isEqualTo(new Page(List.of(row(8)),2,20,41,3,true));
        verify(jdbc).query(argThat((String s)->s.contains("a.shelter_id=:shelterId AND a.api_source='MANUAL'")
                && s.endsWith(" ORDER BY a.created_at DESC LIMIT :limit OFFSET :offset") && !s.contains("a.status=")),
                argThat((MapSqlParameterSource p)->p.getValue("shelterId").equals(7L) && p.getValue("offset").equals(40L)
                        && p.getValue("limit").equals(20)),org.mockito.ArgumentMatchers.<RowMapper<Animal>>any());
        verify(jdbc,never()).queryForObject(anyString(),any(MapSqlParameterSource.class),eq(Long.class));
    }
    @Test void givenFullOrEmptyLaterPage__whenByShelter__thenUseSameManualCountAndPreservePageImplMetadata() {
        rows(List.of(row(8),row(9)));
        when(jdbc.queryForObject(anyString(),any(MapSqlParameterSource.class),eq(Long.class))).thenReturn(99L);
        var result=reads.byShelter(7L,new PageRequest(1,2,"id,asc"));
        assertThat(result.totalElements()).isEqualTo(99); assertThat(result.totalPages()).isEqualTo(50); assertThat(result.last()).isFalse();
        rows(List.of()); result=reads.byShelter(7L,new PageRequest(50,2,"id,asc"));
        assertThat(result.content()).isEmpty(); assertThat(result.totalElements()).isEqualTo(99); assertThat(result.last()).isTrue();
        verify(jdbc,times(2)).queryForObject(eq("SELECT count(*) FROM pawbridge_animal.animals a WHERE a.shelter_id=:shelterId AND a.api_source='MANUAL'"),any(MapSqlParameterSource.class),eq(Long.class));
    }
    @Test void givenEmptyFirstAndStaleCountFinalPage__whenByShelter__thenKeepSourceInferenceAndTotalAdaptation() {
        rows(List.of()); assertThat(reads.byShelter(7L,new PageRequest(0,2,"id,asc"))).isEqualTo(new Page(List.of(),0,2,0,0,true));
        verify(jdbc,never()).queryForObject(anyString(),any(MapSqlParameterSource.class),eq(Long.class));
        rows(List.of(row(8),row(9))); when(jdbc.queryForObject(anyString(),any(MapSqlParameterSource.class),eq(Long.class))).thenReturn(1L);
        assertThat(reads.byShelter(7L,new PageRequest(2,2,"id,asc")).totalElements()).isEqualTo(6);
    }
    @Test void givenSingleWireSort__whenOrdering__thenRetainAll25ScalarsAndImmediateShelterFieldsWithoutTieOrNullPolicy() {
        String[][] fields={{"id","id"},{"apmsDesertionNo","apms_desertion_no"},{"apmsNoticeNo","apms_notice_no"},
                {"species","species"},{"breed","breed"},{"birthYear","birth_year"},{"weight","weight"},{"color","color"},
                {"gender","gender"},{"neuterStatus","neuter_status"},{"specialMark","special_mark"},{"apmsProcessState","apms_process_state"},
                {"noticeStartDate","notice_start_date"},{"noticeEndDate","notice_end_date"},{"apmsUpdatedAt","apms_updated_at"},
                {"happenDate","happen_date"},{"happenPlace","happen_place"},{"imageUrl","image_url"},{"imageUrl2","image_url2"},
                {"status","status"},{"apiSource","api_source"},{"favoriteCount","favorite_count"},{"description","description"},
                {"createdAt","created_at"},{"updatedAt","updated_at"}};
        for(String[] field:fields) for(String direction:List.of("asc","desc"))
            assertThat(JdbcAnimalMemberReads.ordering(field[0]+","+direction)).isEqualTo(" ORDER BY a."+field[1]+" "+direction.toUpperCase(java.util.Locale.ROOT));
        assertThat(JdbcAnimalMemberReads.ordering("BirthYear,asc")).isEqualTo(" ORDER BY a.birth_year ASC");
        assertThat(JdbcAnimalMemberReads.ordering("Shelter.Name,asc")).isEqualTo(" ORDER BY s.name ASC");
        assertThat(JdbcAnimalMemberReads.ordering("shelter.name,desc")).isEqualTo(" ORDER BY s.name DESC");
        assertThat(JdbcAnimalMemberReads.ordering("shelterName,asc")).isEqualTo(" ORDER BY s.name ASC");
        assertThat(JdbcAnimalMemberReads.ordering("breed,id,desc,ignorecase")).isEqualTo(" ORDER BY lower(a.breed) DESC,a.id DESC");
        assertThat(JdbcAnimalMemberReads.ordering("species,asc,ignorecase")).isEqualTo(" ORDER BY a.species ASC");
        assertThat(JdbcAnimalMemberReads.ordering(null)).isEqualTo(" ORDER BY a.created_at DESC");
        assertThat(JdbcAnimalMemberReads.ordering(" ")).isEmpty();
    }
    @Test void givenFlattenedMultipleSortsOrInjectedPath__whenByShelter__thenFailBeforeSqlWithoutInventingPairParsing() {
        for(String sort:List.of("breed,desc,createdAt,asc","id;drop table animals,asc"))
            assertThatThrownBy(()->reads.byShelter(7L,new PageRequest(0,20,sort))).isInstanceOf(AnimalMemberReadUnavailableException.class);
        verifyNoInteractions(jdbc);
    }
    @Test void givenMaximumJpaOffset__whenCalculatingLast__thenPreserveSourceIntOverflow() {
        rows(List.of()); when(jdbc.queryForObject(anyString(),any(MapSqlParameterSource.class),eq(Long.class))).thenReturn(99L);
        var result=reads.byShelter(7L,new PageRequest(Integer.MAX_VALUE,1,"id,asc"));
        assertThat(result.number()).isEqualTo(Integer.MAX_VALUE);
        assertThat(result.totalElements()).isEqualTo(99);
        // PageImpl.hasNext uses int page+1, including the existing overflow at MAX_VALUE.
        assertThat(result.last()).isFalse();
    }
    @Test void givenQueryFailure__whenBatchOrCount__thenPropagateUnavailableInsteadOfEmpty() {
        var cause=new IllegalStateException("selected read failed");
        when(jdbc.query(anyString(),any(MapSqlParameterSource.class),org.mockito.ArgumentMatchers.<RowMapper<Animal>>any())).thenThrow(cause);
        assertThatThrownBy(()->reads.batch(List.of(8L))).isInstanceOf(AnimalMemberReadUnavailableException.class).hasCause(cause);
        rows(List.of()); when(jdbc.queryForObject(anyString(),any(MapSqlParameterSource.class),eq(Long.class))).thenThrow(cause);
        assertThatThrownBy(()->reads.byShelter(7L,new PageRequest(1,20,"id,asc"))).isInstanceOf(AnimalMemberReadUnavailableException.class).hasCause(cause);
    }
    @Test void givenRegistrationLookup__whenDelegating__thenReuseFrozenShelterProjectionAndFailure() {
        var value=new AnimalShelterReads.Shelter(7L,"r","센터"); when(shelters.findByCareRegNo(" r ")).thenReturn(value);
        assertThat(reads.findByCareRegNo(" r ")).isSameAs(value); verifyNoInteractions(jdbc);
    }
    @Test void givenPublicConstructor__whenReadScopeStarts__thenRequestDefaultReadOnlyTxBeforeValidation() {
        PlatformTransactionManager manager=mock(PlatformTransactionManager.class); DataSource source=mock(DataSource.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var publicReads=new JdbcAnimalMemberReads(source,manager,shelters);
        assertThatThrownBy(()->publicReads.byShelter(7L,new PageRequest(Integer.MAX_VALUE,20,"id,asc"))).isInstanceOf(AnimalMemberReadUnavailableException.class);
        var definition=ArgumentCaptor.forClass(TransactionDefinition.class); verify(manager).getTransaction(definition.capture());
        assertThat(definition.getValue().isReadOnly()).isTrue();
        assertThat(definition.getValue().getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
        assertThat(definition.getValue().getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT);
        assertThat(definition.getValue().getTimeout()).isEqualTo(TransactionDefinition.TIMEOUT_DEFAULT);
        verify(manager).rollback(any()); verifyNoInteractions(source);
    }
}
