package com.pawbridge.animalservice.travel;

import java.sql.*;
import java.time.*;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PetTravelBulkCollectorTest {
    private final DataSource source=mock(DataSource.class);
    private final PetTravelCatalog catalog=mock(PetTravelCatalog.class);
    private final TourApiClient client=mock(TourApiClient.class);
    private final TourApiProperties properties=new TourApiProperties();
    private final Instant now=Instant.parse("2026-09-13T00:00:00Z");
    private final PetTravelCollector collector=new PetTravelCollector(source,catalog,client,properties,Clock.fixed(now,ZoneOffset.UTC));
    private final PetTravelCatalog.PetCollectionState start=new PetTravelCatalog.PetCollectionState(1,null,"cycle",null);
    private final PetTravelCatalog.Target target=new PetTravelCatalog.Target("123","11","20260913000000",true,null,null,1,true);

    @BeforeEach void setup() throws Exception {
        properties.setEnabled(true);properties.setBulkPetEnabled(true);
        var connection=mock(Connection.class);var statement=mock(PreparedStatement.class);var result=mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);when(result.getInt(1)).thenReturn(1);
        when(catalog.reserveRequest(anyString(),any(),anyInt())).thenReturn(true);
        when(catalog.petCollectionState()).thenReturn(start);
        when(catalog.collectionState()).thenReturn(new PetTravelCatalog.CollectionState("SHOWN",1,null,null));
        when(client.fetch(TourApiClient.Operation.REGIONS,"")).thenReturn(List.of(Map.of("code","11","name","서울")));
        when(client.fetchPage(TourApiClient.Operation.SYNC,"1",1)).thenReturn(new TourApiClient.Page(List.of(),0));
        when(catalog.pending(properties.getMaxDetailsPerRun())).thenReturn(List.of(target));
        when(client.fetch(TourApiClient.Operation.COMMON,"123")).thenReturn(List.of(Map.of("contentid","123","title","공원")));
        when(catalog.publishCommon(eq(target),anyMap(),eq(now))).thenReturn(true);
    }

    @Test void givenBulkFailure__whenCollect__thenCommonContinuesWithoutIndividualPetFallback() throws Exception {
        when(client.fetchPage(TourApiClient.Operation.PET_BULK,"",1)).thenThrow(PetTravelException.unavailable());
        var result=collector.collect();
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.published()).isEqualTo(1);
        verify(catalog).petCollectionFailed("PET_BULK_FAILED",false);
        verify(client,never()).fetch(eq(TourApiClient.Operation.PET),anyString());
    }

    @Test void givenBulkQuotaExhausted__whenCollect__thenOtherOperationBudgetsRemainUsable() throws Exception {
        when(catalog.reserveRequest(eq("PET_BULK"),any(),anyInt())).thenReturn(false);
        assertThat(collector.collect().published()).isEqualTo(1);
        verify(client,never()).fetchPage(eq(TourApiClient.Operation.PET_BULK),anyString(),anyInt());
        verify(catalog).petCollectionFailed("PET_BULK_QUOTA",false);
    }

    @Test void givenRecentCompletedBulkCycle__whenCollect__thenSkipUntilNextDay() throws Exception {
        when(catalog.petCollectionState()).thenReturn(new PetTravelCatalog.PetCollectionState(1,null,"cycle",now.minusSeconds(3600)));
        assertThat(collector.collect().published()).isEqualTo(1);
        verify(catalog,never()).reserveRequest(eq("PET_BULK"),any(),anyInt());
    }

    @Test void givenInterruptedBulkCycle__whenCollect__thenResumeSavedPageEvenWithRecentPreviousCompletion() throws Exception {
        var state=new PetTravelCatalog.PetCollectionState(2,101,"cycle",now.minusSeconds(3600));
        var page=new TourApiClient.Page(List.of(Map.of("contentid","123","acmpyNeedMtr","목줄")),101);
        when(catalog.petCollectionState()).thenReturn(state);
        when(client.fetchPage(TourApiClient.Operation.PET_BULK,"",2)).thenReturn(page);
        collector.collect();
        verify(catalog).savePetPage(state,page,now,now.plus(Duration.ofDays(14)));
        verify(client,never()).fetchPage(TourApiClient.Operation.PET_BULK,"",1);
    }

    @Test void givenInventoryShift__whenCollect__thenRestartBulkWithoutStoppingCommonDetails() throws Exception {
        var page=new TourApiClient.Page(List.of(),0);
        when(client.fetchPage(TourApiClient.Operation.PET_BULK,"",1)).thenReturn(page);
        doThrow(new IllegalStateException("PET_INVENTORY_CHANGED")).when(catalog).savePetPage(eq(start),eq(page),any(),any());
        assertThat(collector.collect().published()).isEqualTo(1);
        verify(catalog).petCollectionFailed("PET_INVENTORY_CHANGED",true);
    }

    @Test void givenLargeInventory__whenPageLimitReached__thenYieldToCommonCollection() throws Exception {
        properties.setMaxBulkPagesPerRun(1);
        var page=new TourApiClient.Page(java.util.stream.IntStream.range(1000,1100)
                .mapToObj(id->Map.of("contentid",String.valueOf(id))).toList(),9686);
        when(client.fetchPage(TourApiClient.Operation.PET_BULK,"",1)).thenReturn(page);
        var result=collector.collect();
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.published()).isEqualTo(1);
        verify(catalog).savePetPage(eq(start),eq(page),any(),any());
        verify(client,times(1)).fetchPage(eq(TourApiClient.Operation.PET_BULK),anyString(),anyInt());
        verify(catalog).finishRun(anyString(),eq("PARTIAL"),eq("PET_BULK_PENDING"),anyInt(),anyInt(),eq(1),eq(now));
    }

    @Test void givenLockLostDuringBulkHttp__whenResponseArrives__thenDoNotCommitOrContinue() throws Exception {
        var result=source.getConnection().prepareStatement("").executeQuery();
        when(result.getInt(1)).thenReturn(1,1,0,0);
        when(client.fetchPage(TourApiClient.Operation.PET_BULK,"",1)).thenReturn(new TourApiClient.Page(List.of(),0));
        assertThatThrownBy(collector::collect).hasMessage("TRAVEL_COLLECTION_FAILED").hasNoCause();
        verify(catalog,never()).savePetPage(any(),any(),any(),any());
        verify(client,never()).fetch(eq(TourApiClient.Operation.REGIONS),anyString());
    }

    @Test void givenIntroFailure__whenCollectVisitResources__thenInformationAndImagesStillPublish() throws Exception {
        var visit=new PetTravelCatalog.VisitTarget("123","12","20260913000000",1);
        when(catalog.pendingVisitDetails(eq(PetTravelCatalog.VisitResource.INTRO),anyInt())).thenReturn(List.of(visit));
        when(catalog.pendingVisitDetails(eq(PetTravelCatalog.VisitResource.INFO),anyInt())).thenReturn(List.of(visit));
        when(catalog.pendingVisitDetails(eq(PetTravelCatalog.VisitResource.IMAGES),anyInt())).thenReturn(List.of(visit));
        when(client.fetchDetail(TourApiClient.Operation.INTRO,"123","12")).thenThrow(PetTravelException.unavailable());
        var info=List.of(Map.of("contentid","123","contenttypeid","12","infoname","안내","infotext","설명"));
        when(client.fetchDetail(TourApiClient.Operation.INFO,"123","12")).thenReturn(info);
        when(client.fetchDetail(TourApiClient.Operation.IMAGES,"123","12")).thenReturn(List.of());
        assertThat(collector.collect().status()).isEqualTo("PARTIAL");
        verify(catalog).visitDetailFailed(PetTravelCatalog.VisitResource.INTRO,visit,now);
        verify(catalog).saveVisitInformation(visit,info,now);
        verify(catalog).saveVisitImages(visit,List.of(),now);
    }

    @Test void givenIntroQuotaExhausted__whenCollectVisitResources__thenOtherOperationBudgetsContinue() throws Exception {
        var visit=new PetTravelCatalog.VisitTarget("123","12","20260913000000",1);
        when(catalog.pendingVisitDetails(any(),anyInt())).thenReturn(List.of(visit));
        when(catalog.reserveRequest(eq("INTRO"),any(),anyInt())).thenReturn(false);
        when(client.fetchDetail(TourApiClient.Operation.INFO,"123","12")).thenReturn(List.of());
        when(client.fetchDetail(TourApiClient.Operation.IMAGES,"123","12")).thenReturn(List.of());
        assertThat(collector.collect().status()).isEqualTo("PARTIAL");
        verify(client,never()).fetchDetail(TourApiClient.Operation.INTRO,"123","12");
        verify(client).fetchDetail(TourApiClient.Operation.INFO,"123","12");
        verify(client).fetchDetail(TourApiClient.Operation.IMAGES,"123","12");
    }
}
