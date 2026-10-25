package com.pawbridge.backend.animal.internal.travel.infrastructure.collection

import com.pawbridge.backend.animal.anyNonNull
import com.pawbridge.backend.animal.eqNonNull
import com.pawbridge.backend.animal.internal.travel.application.PetTravelException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.sql.DataSource

class PetTravelBulkCollectorTest {
    private val source: DataSource = mock(DataSource::class.java)
    private val catalog: PostgresqlTravelCollectionCatalog = mock(PostgresqlTravelCollectionCatalog::class.java)
    private val client: TourApiClient = mock(TourApiClient::class.java)
    private val properties = TourApiProperties()
    private val now: Instant = Instant.parse("2026-09-13T00:00:00Z")
    private val collector = PetTravelCollector(source, catalog, client, properties, Clock.fixed(now, ZoneOffset.UTC))
    private val start = PostgresqlTravelCollectionCatalog.PetCollectionState(1, null, "cycle", null)
    private val target =
        PostgresqlTravelCollectionCatalog.Target("123", "11", "20260913000000", true, null, null, 1, true)

    @BeforeEach
    fun setup() {
        properties.isEnabled = true
        properties.isBulkPetEnabled = true
        val connection = mock(Connection::class.java)
        val statement = mock(PreparedStatement::class.java)
        val result = mock(ResultSet::class.java)
        `when`(source.connection).thenReturn(connection)
        `when`(connection.prepareStatement(anyString())).thenReturn(statement)
        `when`(statement.executeQuery()).thenReturn(result)
        `when`(result.next()).thenReturn(true)
        `when`(result.getObject(1)).thenReturn(1)
        `when`(catalog.reserveRequest(anyString(), anyNonNull<LocalDate>(), anyInt())).thenReturn(true)
        `when`(catalog.petCollectionState()).thenReturn(start)
        `when`(catalog.collectionState())
            .thenReturn(PostgresqlTravelCollectionCatalog.CollectionState("SHOWN", 1, null, null))
        `when`(client.fetch(TourApiClient.Operation.REGIONS, ""))
            .thenReturn(listOf(mapOf("code" to "11", "name" to "서울")))
        `when`(client.fetchPage(TourApiClient.Operation.SYNC, "1", 1)).thenReturn(TourApiClient.Page(listOf(), 0))
        `when`(catalog.pending(properties.maxDetailsPerRun)).thenReturn(listOf(target))
        `when`(client.fetch(TourApiClient.Operation.COMMON, "123"))
            .thenReturn(listOf(mapOf("contentid" to "123", "title" to "공원")))
        `when`(catalog.publishCommon(eqNonNull(target), anyMap(), eqNonNull(now))).thenReturn(true)
    }

    @Test
    fun given__disabled_collection__when__collect__then__no_database_or_http() {
        properties.isEnabled = false

        assertThatThrownBy { collector.collect() }.hasMessage("TRAVEL_COLLECTION_DISABLED")

        verifyNoInteractions(source, catalog, client)
    }

    @Test
    fun given__busy_postgresql_session_lock__when__collect__then__close_without_starting_a_run() {
        val connection = source.connection
        val statement = connection.prepareStatement("")
        val result = statement.executeQuery()
        `when`(result.getObject(1)).thenReturn(0)

        assertThatThrownBy { collector.collect() }.hasMessage("TRAVEL_COLLECTION_BUSY")

        verify(statement).setInt(1, 2)
        verify(statement).setQueryTimeout(5)
        verify(connection).close()
        verify(connection, never()).getMetaData()
        verify(connection, never()).abort(any())
        verifyNoInteractions(catalog, client)
    }

    @Test
    fun givenBulkFailure__whenCollect__thenCommonContinuesWithoutIndividualPetFallback() {
        `when`(client.fetchPage(TourApiClient.Operation.PET_BULK, "", 1)).thenThrow(PetTravelException.unavailable())

        val result = collector.collect()

        assertThat(result.status).isEqualTo("PARTIAL")
        assertThat(result.published).isEqualTo(1)
        verify(catalog).petCollectionFailed("PET_BULK_FAILED", false)
        verify(client, never()).fetch(eqNonNull(TourApiClient.Operation.PET), anyString())
    }

    @Test
    fun givenBulkQuotaExhausted__whenCollect__thenOtherOperationBudgetsRemainUsable() {
        `when`(catalog.reserveRequest(eqNonNull("PET_BULK"), anyNonNull<LocalDate>(), anyInt())).thenReturn(false)

        assertThat(collector.collect().published).isEqualTo(1)

        verify(client, never()).fetchPage(eqNonNull(TourApiClient.Operation.PET_BULK), anyString(), anyInt())
        verify(catalog).petCollectionFailed("PET_BULK_QUOTA", false)
    }

    @Test
    fun givenRecentCompletedBulkCycle__whenCollect__thenSkipUntilNextDay() {
        `when`(catalog.petCollectionState()).thenReturn(
            PostgresqlTravelCollectionCatalog.PetCollectionState(1, null, "cycle", now.minusSeconds(3600)),
        )

        assertThat(collector.collect().published).isEqualTo(1)

        verify(catalog, never()).reserveRequest(eqNonNull("PET_BULK"), anyNonNull<LocalDate>(), anyInt())
    }

    @Test
    fun givenInterruptedBulkCycle__whenCollect__thenResumeSavedPageEvenWithRecentPreviousCompletion() {
        val state = PostgresqlTravelCollectionCatalog.PetCollectionState(2, 101, "cycle", now.minusSeconds(3600))
        val page = TourApiClient.Page(listOf(mapOf("contentid" to "123", "acmpyNeedMtr" to "목줄")), 101)
        `when`(catalog.petCollectionState()).thenReturn(state)
        `when`(client.fetchPage(TourApiClient.Operation.PET_BULK, "", 2)).thenReturn(page)

        collector.collect()

        verify(catalog).savePetPage(state, page, now, now.plus(Duration.ofDays(14)))
        verify(client, never()).fetchPage(TourApiClient.Operation.PET_BULK, "", 1)
    }

    @Test
    fun givenInventoryShift__whenCollect__thenRestartBulkWithoutStoppingCommonDetails() {
        val page = TourApiClient.Page(listOf(), 0)
        `when`(client.fetchPage(TourApiClient.Operation.PET_BULK, "", 1)).thenReturn(page)
        doThrow(IllegalStateException("PET_INVENTORY_CHANGED")).`when`(catalog).savePetPage(
            eqNonNull(start),
            eqNonNull(page),
            anyNonNull<Instant>(),
            anyNonNull<Instant>(),
        )

        assertThat(collector.collect().published).isEqualTo(1)

        verify(catalog).petCollectionFailed("PET_INVENTORY_CHANGED", true)
    }

    @Test
    fun givenLargeInventory__whenPageLimitReached__thenYieldToCommonCollection() {
        properties.maxBulkPagesPerRun = 1
        val page = TourApiClient.Page(
            (1000 until 1100).map { id -> mapOf("contentid" to id.toString()) },
            9686,
        )
        `when`(client.fetchPage(TourApiClient.Operation.PET_BULK, "", 1)).thenReturn(page)

        val result = collector.collect()

        assertThat(result.status).isEqualTo("PARTIAL")
        assertThat(result.published).isEqualTo(1)
        verify(catalog).savePetPage(
            eqNonNull(start),
            eqNonNull(page),
            anyNonNull<Instant>(),
            anyNonNull<Instant>(),
        )
        verify(client, times(1)).fetchPage(eqNonNull(TourApiClient.Operation.PET_BULK), anyString(), anyInt())
        verify(catalog).finishRun(
            anyString(),
            eqNonNull("PARTIAL"),
            eq("PET_BULK_PENDING"),
            anyInt(),
            anyInt(),
            eq(1),
            eqNonNull(now),
        )
    }

    @Test
    fun givenLockLostDuringBulkHttp__whenResponseArrives__thenDoNotCommitOrContinue() {
        val result = source.connection.prepareStatement("").executeQuery()
        `when`(result.getObject(1)).thenReturn(1, 1, 0, 0)
        `when`(client.fetchPage(TourApiClient.Operation.PET_BULK, "", 1)).thenReturn(TourApiClient.Page(listOf(), 0))

        assertThatThrownBy { collector.collect() }.hasMessage("TRAVEL_COLLECTION_FAILED").hasNoCause()

        verify(catalog, never()).savePetPage(
            anyNonNull<PostgresqlTravelCollectionCatalog.PetCollectionState>(),
            anyNonNull<TourApiClient.Page>(),
            anyNonNull<Instant>(),
            anyNonNull<Instant>(),
        )
        verify(client, never()).fetch(eqNonNull(TourApiClient.Operation.REGIONS), anyString())
    }

    @Test
    fun givenIntroFailure__whenCollectVisitResources__thenInformationAndImagesStillPublish() {
        val visit = PostgresqlTravelCollectionCatalog.VisitTarget("123", "12", "20260913000000", 1)
        `when`(catalog.pendingVisitDetails(eqNonNull(PostgresqlTravelCollectionCatalog.VisitResource.INTRO), anyInt()))
            .thenReturn(listOf(visit))
        `when`(catalog.pendingVisitDetails(eqNonNull(PostgresqlTravelCollectionCatalog.VisitResource.INFO), anyInt()))
            .thenReturn(listOf(visit))
        `when`(catalog.pendingVisitDetails(eqNonNull(PostgresqlTravelCollectionCatalog.VisitResource.IMAGES), anyInt()))
            .thenReturn(listOf(visit))
        `when`(client.fetchDetail(TourApiClient.Operation.INTRO, "123", "12")).thenThrow(PetTravelException.unavailable())
        val info = listOf(mapOf("contentid" to "123", "contenttypeid" to "12", "infoname" to "안내", "infotext" to "설명"))
        `when`(client.fetchDetail(TourApiClient.Operation.INFO, "123", "12")).thenReturn(info)
        `when`(client.fetchDetail(TourApiClient.Operation.IMAGES, "123", "12")).thenReturn(listOf())

        assertThat(collector.collect().status).isEqualTo("PARTIAL")

        verify(catalog).visitDetailFailed(PostgresqlTravelCollectionCatalog.VisitResource.INTRO, visit, now)
        verify(catalog).saveVisitInformation(visit, info, now)
        verify(catalog).saveVisitImages(visit, listOf(), now)
    }

    @Test
    fun givenIntroQuotaExhausted__whenCollectVisitResources__thenOtherOperationBudgetsContinue() {
        val visit = PostgresqlTravelCollectionCatalog.VisitTarget("123", "12", "20260913000000", 1)
        `when`(catalog.pendingVisitDetails(anyNonNull<PostgresqlTravelCollectionCatalog.VisitResource>(), anyInt()))
            .thenReturn(listOf(visit))
        `when`(catalog.reserveRequest(eqNonNull("INTRO"), anyNonNull<LocalDate>(), anyInt())).thenReturn(false)
        `when`(client.fetchDetail(TourApiClient.Operation.INFO, "123", "12")).thenReturn(listOf())
        `when`(client.fetchDetail(TourApiClient.Operation.IMAGES, "123", "12")).thenReturn(listOf())

        assertThat(collector.collect().status).isEqualTo("PARTIAL")

        verify(client, never()).fetchDetail(TourApiClient.Operation.INTRO, "123", "12")
        verify(client).fetchDetail(TourApiClient.Operation.INFO, "123", "12")
        verify(client).fetchDetail(TourApiClient.Operation.IMAGES, "123", "12")
    }
}
