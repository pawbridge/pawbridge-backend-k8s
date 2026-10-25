package com.pawbridge.backend.animal.internal.apms.infrastructure

import com.pawbridge.backend.animal.internal.apms.infrastructure.client.ApmsApiClient
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsAnimal
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsBody
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsHeader
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsItems
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsResponse
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsRootResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.ArgumentMatchers.nullable
import org.mockito.Mock
import org.mockito.Mockito.lenient
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.batch.core.JobExecution
import org.springframework.batch.support.transaction.ResourcelessTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate

@ExtendWith(MockitoExtension::class)
class ApmsAnimalSnapshotTest {
    @Mock
    private lateinit var api: ApmsApiClient

    private lateinit var execution: JobExecution
    private val plan = ApmsSyncPlan(
        LocalDate.of(2026, 8, 11),
        LocalDate.of(2026, 7, 1),
        LocalDate.of(2026, 8, 11),
        LocalDate.of(2026, 9, 10),
    )

    @BeforeEach
    fun defaultEmptyPages() {
        lenient().`when`(
            api.getAbandonmentAnimals(
                anyString(), anyInt(), eq(1000), anyString(), anyString(),
                isNull<String>(), isNull<String>(), eq("json"), nullable(String::class.java), nullable(String::class.java),
            ),
        ).thenAnswer { call -> page("0", listOf(), call.getArgument(1)) }
    }

    @Test
    fun givenAnActiveWriterTransaction__whenFetchingUncachedSnapshot__thenRejectBeforeAnyProviderCall() {
        val uncached = snapshot()
        val transaction = TransactionTemplate(ResourcelessTransactionManager())

        assertThatThrownBy { transaction.execute { status -> uncached.animals() } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("APMS external fetch must run outside the writer transaction")

        verifyNoInteractions(api)
    }

    @Test
    fun givenOldIntakeRecentlyAdopted__whenCollected__thenIncludeWithoutProtectFilter() {
        val old = animal("old", "20260715", "2026-08-23 15:21:46.0", "종료(입양)")
        updatedJuly(page("1", listOf(old), 1))

        assertThat(snapshot().animals().map { animal -> animal.processState }).containsExactly("종료(입양)")

        verify(api).getAbandonmentAnimals(
            "test-key", 1, 1000, "20260701", "20260731",
            null, null, "json", "20260811", "20260910",
        )
    }

    @Test
    fun givenNewAnimalWithoutUpdateTime__whenCollected__thenKeepIntakePath() {
        val fresh = animal("new", "20260908", null, "보호중")
        recent(page("1", listOf(fresh), 1))

        assertThat(snapshot().animals()).containsExactly(fresh)
    }

    @Test
    fun givenDuplicateNewNumberWithLaterState__whenCollected__thenWriteOneNewestAnimal() {
        val first = animal("new", "20260908", "2026-09-08 10:00:00.0", "보호중")
        val latest = animal("new", "20260908", "2026-09-09 10:00:00.123", "종료(입양)")
        recent(page("1", listOf(first), 1))
        updatedSeptember(page("1", listOf(latest), 1))

        assertThat(snapshot().animals()).containsExactly(latest)
    }

    @Test
    fun givenAlreadyEndedAnimalCorrected__whenCollected__thenKeepLatestCorrection() {
        val latest = animal("ended", "20260908", "2026-09-10 10:00:00", "종료(반환)")
        val stale = animal("ended", "20260908", "2026-09-09 10:00:00", "종료(입양)")
        recent(page("1", listOf(latest), 1))
        updatedSeptember(page("1", listOf(stale), 1))

        assertThat(snapshot().animals()).containsExactly(latest)
    }

    @Test
    fun givenSameTimestampWithConflictingStatus__whenCollected__thenFailBeforeAnimalWrites() {
        recent(page("1", listOf(animal("one", "20260908", "2026-09-09 10:00:00", "보호중")), 1))
        updatedSeptember(page("1", listOf(animal("one", "20260908", "2026-09-09 10:00:00.000", "종료(입양)")), 1))

        assertThatThrownBy { snapshot().animals() }.hasMessageContaining("conflicting data")
    }

    @Test
    fun givenEquivalentTimestampFormats__whenCollected__thenDeduplicate() {
        recent(page("1", listOf(animal("one", "20260908", "2026-09-09 10:00:00", "보호중")), 1))
        updatedSeptember(page("1", listOf(animal("one", "20260908", "2026-09-09 10:00:00.000", "보호중")), 1))

        assertThat(snapshot().animals()).hasSize(1)
    }

    @Test
    fun givenDifferentNumbersSharingNotice__whenCollected__thenRetainBoth() {
        val one = animal("one", "20260908", null, "보호중")
        val two = animal("two", "20260908", null, "보호중")
        one.noticeNo = "same-notice"
        two.noticeNo = "same-notice"
        recent(page("2", listOf(one, two), 1))

        assertThat(snapshot().animals()).hasSize(2)
    }

    @Test
    fun givenSnapshotUsedByTwoSteps__whenReadTwice__thenFetchOnlyOnce() {
        val snapshot = snapshot()
        val first = snapshot.animals()

        assertThat(snapshot.animals()).isSameAs(first)

        verify(api, times(4)).getAbandonmentAnimals(
            anyString(), anyInt(), eq(1000), anyString(), anyString(),
            isNull<String>(), isNull<String>(), eq("json"), nullable(String::class.java), nullable(String::class.java),
        )
    }

    @Test
    fun givenMultiplePages__whenCollected__thenReadEachPageAndStopAtLast() {
        val first = (0 until 1000).map { i -> animal("id-" + i, "20260908", null, "보호중") }
        recent(page("1001", first, 1))
        `when`(api.getAbandonmentAnimals("test-key", 2, 1000, "20260811", "20260910", null, null, "json", null, null))
            .thenReturn(page("1001", listOf(animal("last", "20260908", null, "보호중")), 2))

        assertThat(snapshot().animals()).hasSize(1001)

        verify(api, never()).getAbandonmentAnimals(
            anyString(), eq(3), anyInt(), anyString(), anyString(),
            any(), any(), anyString(), any(), any(),
        )
    }

    @Test
    fun givenTransportFailure__whenCollected__thenRecordIncompleteWithoutLeakingRequest() {
        `when`(
            api.getAbandonmentAnimals(
                eq("test-key"), anyInt(), eq(1000), anyString(), anyString(),
                isNull<String>(), isNull<String>(), eq("json"), isNull<String>(), isNull<String>(),
            ),
        ).thenThrow(IllegalStateException("private-service-key-placeholder"))

        assertIncomplete("REQUEST_FAILED")

        assertThat(execution.executionContext.getString(ApmsAnimalSnapshot.INCOMPLETE_DETAILS))
            .doesNotContain("private-service-key-placeholder")
    }

    @Test
    fun givenProviderError__whenCollected__thenRecordIncomplete() {
        val response = page("0", listOf(), 1)
        response.response!!.header!!.resultCode = "30"
        everyIntakeQuery(response)

        assertIncomplete("UNSUCCESSFUL_RESPONSE")
    }

    @Test
    fun givenMissingResponse__whenCollected__thenRecordIncomplete() {
        everyIntakeQuery(null)

        assertIncomplete("UNSUCCESSFUL_RESPONSE")
    }

    @Test
    fun givenPositiveCountAndMissingItems__whenCollected__thenRecordIncomplete() {
        recent(page("3", null, 1))

        assertIncomplete("COUNT_MISMATCH")
    }

    @Test
    fun givenPositiveCountAndEmptyItems__whenCollected__thenRecordIncomplete() {
        recent(page("3", listOf(), 1))

        assertIncomplete("COUNT_MISMATCH")
    }

    @Test
    fun givenTruncatedPage__whenCollected__thenRecordIncomplete() {
        recent(page("2", listOf(animal("one", "20260908", null, "보호중")), 1))

        assertIncomplete("COUNT_MISMATCH")
    }

    @Test
    fun givenSuccessfulZeroCount__whenCollected__thenAcceptEmptyResult() {
        recent(page("0", null, 1))

        assertThat(snapshot().animals()).isEmpty()
    }

    @Test
    fun givenRequestBudgetReached__whenAnotherQueryNeeded__thenRecordUnqueriedWindowsWithoutExtraCall() {
        assertThat(snapshot(1, 50000).animals()).isEmpty()

        assertThat(execution.executionContext.getString(ApmsAnimalSnapshot.INCOMPLETE_DETAILS)).contains("REQUEST_BUDGET")
        verify(api, times(1)).getAbandonmentAnimals(
            anyString(), anyInt(), anyInt(), anyString(), anyString(),
            any(), any(), anyString(), any(), any(),
        )
    }

    @Test
    fun givenAnimalBudgetReached__whenNewNumberArrives__thenKeepBoundedDataAndRecordIncomplete() {
        recent(
            page(
                "2",
                listOf(animal("one", "20260908", null, "보호중"), animal("two", "20260908", null, "보호중")),
                1,
            ),
        )

        assertThat(snapshot(100, 1).animals().map { animal -> animal.desertionNo }).containsExactly("one")

        assertThat(execution.executionContext.getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT)).isPositive()
        assertThat(execution.executionContext.getString(ApmsAnimalSnapshot.INCOMPLETE_DETAILS)).contains("ANIMAL_BUDGET")
    }

    @Test
    fun givenProviderIgnoringModifiedFilter__whenCollected__thenFail() {
        updatedJuly(page("1", listOf(animal("old", "20260715", "2026-07-16 10:00:00", "보호중")), 1))

        assertThatThrownBy { snapshot().animals() }.hasMessageContaining("update window")
    }

    @Test
    fun givenMissingNumber__whenCollected__thenFail() {
        recent(page("1", listOf(animal(null, "20260908", null, "보호중")), 1))

        assertThatThrownBy { snapshot().animals() }.hasMessageContaining("number is missing")
    }

    @Test
    fun givenMalformedUpdateTime__whenCollected__thenFailInsteadOfChoosingAnArbitraryDuplicate() {
        recent(page("1", listOf(animal("one", "20260908", "bad-date", "보호중")), 1))

        assertThatThrownBy { snapshot().animals() }.hasMessageContaining("timestamp is invalid")
    }

    @Test
    fun givenImpossibleCalendarDate__whenCollected__thenFailInsteadOfNormalizing() {
        recent(page("1", listOf(animal("one", "20260908", "2026-02-30 10:00:00", "보호중")), 1))

        assertThatThrownBy { snapshot().animals() }.hasMessageContaining("timestamp is invalid")
    }

    private fun assertIncomplete(reason: String) {
        snapshot().animals()
        assertThat(execution.executionContext.getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT)).isPositive()
        assertThat(execution.executionContext.getString(ApmsAnimalSnapshot.INCOMPLETE_DETAILS)).contains(reason)
    }

    private fun snapshot(): ApmsAnimalSnapshot {
        return snapshot(100, 50000)
    }

    private fun snapshot(requests: Int, animals: Int): ApmsAnimalSnapshot {
        execution = JobExecution(1L, plan.parameters())
        return ApmsAnimalSnapshot(api, "test-key", execution, requests, animals)
    }

    private fun animal(number: String?, intake: String, updated: String?, state: String): ApmsAnimal {
        return ApmsAnimal.builder().desertionNo(number).happenDt(intake).updTm(updated).processState(state).build()
    }

    private fun everyIntakeQuery(response: ApmsRootResponse<ApmsAnimal>?) {
        `when`(
            api.getAbandonmentAnimals(
                eq("test-key"), anyInt(), eq(1000), anyString(), anyString(),
                isNull<String>(), isNull<String>(), eq("json"), isNull<String>(), isNull<String>(),
            ),
        ).thenReturn(response)
    }

    private fun recent(response: ApmsRootResponse<ApmsAnimal>) {
        `when`(api.getAbandonmentAnimals("test-key", 1, 1000, "20260811", "20260910", null, null, "json", null, null))
            .thenReturn(response)
    }

    private fun updatedJuly(response: ApmsRootResponse<ApmsAnimal>) {
        `when`(
            api.getAbandonmentAnimals(
                "test-key", 1, 1000, "20260701", "20260731", null, null, "json", "20260811", "20260910",
            ),
        ).thenReturn(response)
    }

    private fun updatedSeptember(response: ApmsRootResponse<ApmsAnimal>) {
        `when`(
            api.getAbandonmentAnimals(
                "test-key", 1, 1000, "20260901", "20260910", null, null, "json", "20260811", "20260910",
            ),
        ).thenReturn(response)
    }

    private fun page(total: String, animals: List<ApmsAnimal>?, number: Int): ApmsRootResponse<ApmsAnimal> {
        return ApmsRootResponse(
            ApmsResponse(
                ApmsHeader.builder().resultCode("00").build(),
                ApmsBody(ApmsItems(animals), "1000", number.toString(), total),
            ),
        )
    }
}
