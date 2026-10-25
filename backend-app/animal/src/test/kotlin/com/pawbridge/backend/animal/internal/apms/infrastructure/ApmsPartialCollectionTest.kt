package com.pawbridge.backend.animal.internal.apms.infrastructure

import com.pawbridge.backend.animal.internal.apms.infrastructure.client.ApmsApiClient
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsAnimal
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsBody
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsHeader
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsItems
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsResponse
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsRootResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.ArgumentMatchers.nullable
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.springframework.batch.core.JobExecution
import java.time.LocalDate

class ApmsPartialCollectionTest {
    @Test
    fun given991ReportedBut990Returned__whenCollected__thenKeepConfirmedAnimalsAndRecordIncompleteWindow() {
        val api = mock(ApmsApiClient::class.java)
        val day = LocalDate.of(2026, 9, 10)
        val execution = JobExecution(1L, ApmsSyncPlan(day, day, day, day).parameters())
        val items = (0 until 990).map { i ->
            ApmsAnimal.builder().desertionNo("id-" + i).happenDt("20260910").updTm("2026-09-10 10:00:00").build()
        }
        `when`(
            api.getAbandonmentAnimals(
                anyString(), anyInt(), anyInt(), anyString(), anyString(),
                isNull<String>(), isNull<String>(), eq("json"), nullable(String::class.java), nullable(String::class.java),
            ),
        ).thenAnswer { call ->
            if ("20260910" == call.getArgument<String>(4)) {
                response(991, items, call.getArgument(1))
            } else {
                response(0, listOf(), call.getArgument(1))
            }
        }

        val snapshot = ApmsAnimalSnapshot(api, "test-key", execution, 100, 50000)

        assertThat(snapshot.animals()).hasSize(990)
        assertThat(execution.executionContext.getInt("apms.sync.incompleteQueryCount")).isPositive()
        assertThat(execution.executionContext.getString("apms.sync.incompleteQueryDetails")).contains("COUNT_MISMATCH")
    }

    @Test
    fun givenTransportFailureInOneWindow__whenCollected__thenKeepHealthyWindowWithoutLeakingCredential() {
        val api = mock(ApmsApiClient::class.java)
        val day = LocalDate.of(2026, 9, 10)
        val execution = JobExecution(1L, ApmsSyncPlan(day, day, day, day).parameters())
        val animal = ApmsAnimal.builder().desertionNo("healthy").happenDt("20260910").build()
        `when`(
            api.getAbandonmentAnimals(
                anyString(), anyInt(), anyInt(), anyString(), anyString(),
                isNull<String>(), isNull<String>(), eq("json"), nullable(String::class.java), nullable(String::class.java),
            ),
        ).thenAnswer { call ->
            if (call.getArgument<String?>(8) != null) {
                throw IllegalStateException("secret-placeholder")
            }
            response(1, listOf(animal), call.getArgument(1))
        }

        assertThat(ApmsAnimalSnapshot(api, "test-key", execution, 100, 50000).animals()).containsExactly(animal)

        assertThat(execution.executionContext.getInt("apms.sync.incompleteQueryCount")).isEqualTo(1)
        assertThat(execution.executionContext.getString("apms.sync.incompleteQueryDetails"))
            .contains("REQUEST_FAILED").doesNotContain("secret-placeholder")
    }

    @Test
    fun givenTransientTruncation__whenRetryReturnsMissingAnimal__thenCompleteWithoutDuplicateWrites() {
        val f = fixture(100)
        val one = animal("one", "20260909")
        val two = animal("two", "20260910")
        `when`(f.api.getAbandonmentAnimals("test-key", 1, 1000, "20260909", "20260910", null, null, "json", null, null))
            .thenReturn(response(2, listOf(one), 1), response(2, listOf(one, two), 1))

        assertThat(f.snapshot.animals()).containsExactly(one, two)

        assertThat(f.execution.executionContext.getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT)).isZero()
        verify(f.api, times(2))
            .getAbandonmentAnimals("test-key", 1, 1000, "20260909", "20260910", null, null, "json", null, null)
    }

    @Test
    fun givenTruncatedInterval__whenTwoDaysReconcileAllExpectedAnimals__thenComplete() {
        val f = fixture(100)
        splitResponses(f, 2)

        assertThat(f.snapshot.animals().map { animal -> animal.desertionNo }).containsExactly("one", "two")

        assertThat(f.execution.executionContext.getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT)).isZero()
    }

    @Test
    fun givenChildrenWithLowerTotal__whenSplitReturnsFewerThanParentReported__thenKeepUnresolved() {
        val f = fixture(100)
        splitResponses(f, 3)

        assertThat(f.snapshot.animals()).hasSize(2)

        assertThat(f.execution.executionContext.getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT)).isEqualTo(1)
        assertThat(f.execution.executionContext.getString(ApmsAnimalSnapshot.INCOMPLETE_DETAILS))
            .contains("reported=3", "observed=2", "COUNT_MISMATCH")
    }

    @Test
    fun givenBudgetExhaustedAfterHealthyQuery__whenCollected__thenRetainDataAndRecordUnqueriedWindow() {
        val f = fixture(1)
        val one = animal("one", "20260909")
        `when`(f.api.getAbandonmentAnimals("test-key", 1, 1000, "20260909", "20260910", null, null, "json", null, null))
            .thenReturn(response(1, listOf(one), 1))

        assertThat(f.snapshot.animals()).containsExactly(one)

        assertThat(f.execution.executionContext.getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT)).isEqualTo(1)
        assertThat(f.execution.executionContext.getString(ApmsAnimalSnapshot.INCOMPLETE_DETAILS))
            .contains("REQUEST_BUDGET")
        verify(f.api, times(1)).getAbandonmentAnimals(
            anyString(), anyInt(), anyInt(), anyString(), anyString(),
            isNull<String>(), isNull<String>(), eq("json"), nullable(String::class.java), nullable(String::class.java),
        )
    }

    @Test
    fun givenEarlyFailureAndSmallBudget__whenCollected__thenTryOtherWindowBeforeRetryingFailure() {
        val f = fixture(2)
        val one = animal("one", "20260909")
        `when`(f.api.getAbandonmentAnimals("test-key", 1, 1000, "20260909", "20260910", null, null, "json", null, null))
            .thenThrow(IllegalStateException("offline"))
        `when`(
            f.api.getAbandonmentAnimals(
                "test-key", 1, 1000, "20260901", "20260910", null, null, "json", "20260909", "20260910",
            ),
        ).thenReturn(response(1, listOf(one), 1))

        assertThat(f.snapshot.animals()).containsExactly(one)

        assertThat(f.execution.executionContext.getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT)).isEqualTo(1)
        verify(f.api, times(1))
            .getAbandonmentAnimals("test-key", 1, 1000, "20260909", "20260910", null, null, "json", null, null)
    }

    @Test
    fun givenRepeatedFullPage__whenProviderRepeatsIdsInsteadOfNextPage__thenDoNotClaimComplete() {
        val f = fixture(100)
        val items = (0 until 1000).map { i -> animal("id-" + i, "20260909") }
        `when`(
            f.api.getAbandonmentAnimals(
                eq("test-key"), anyInt(), eq(1000), eq("20260909"), eq("20260910"),
                isNull<String>(), isNull<String>(), eq("json"), isNull<String>(), isNull<String>(),
            ),
        ).thenAnswer { call -> response(2000, items, call.getArgument(1)) }

        assertThat(f.snapshot.animals()).hasSize(1000)

        assertThat(f.execution.executionContext.getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT)).isEqualTo(1)
        verify(f.api, never()).getAbandonmentAnimals(
            anyString(), eq(3), anyInt(), anyString(), anyString(),
            isNull<String>(), isNull<String>(), eq("json"), nullable(String::class.java), nullable(String::class.java),
        )
    }

    @Test
    fun givenEmptySecondPageBeforeTotalReached__whenCollected__thenKeepFirstPageAndRecordGap() {
        val f = fixture(100)
        val items = (0 until 1000).map { i -> animal("id-" + i, "20260909") }
        `when`(f.api.getAbandonmentAnimals("test-key", 1, 1000, "20260909", "20260910", null, null, "json", null, null))
            .thenReturn(response(1001, items, 1))
        `when`(f.api.getAbandonmentAnimals("test-key", 2, 1000, "20260909", "20260910", null, null, "json", null, null))
            .thenReturn(response(1001, listOf(), 2))

        assertThat(f.snapshot.animals()).hasSize(1000)

        assertThat(f.execution.executionContext.getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT)).isEqualTo(1)
    }

    private fun splitResponses(f: Fixture, parentTotal: Int) {
        val one = animal("one", "20260909")
        val two = animal("two", "20260910")
        `when`(f.api.getAbandonmentAnimals("test-key", 1, 1000, "20260909", "20260910", null, null, "json", null, null))
            .thenReturn(response(parentTotal, listOf(one), 1))
        `when`(f.api.getAbandonmentAnimals("test-key", 1, 1000, "20260909", "20260909", null, null, "json", null, null))
            .thenReturn(response(1, listOf(one), 1))
        `when`(f.api.getAbandonmentAnimals("test-key", 1, 1000, "20260910", "20260910", null, null, "json", null, null))
            .thenReturn(response(1, listOf(two), 1))
    }

    private fun fixture(requests: Int): Fixture {
        val api = mock(ApmsApiClient::class.java)
        val start = LocalDate.of(2026, 9, 9)
        val end = LocalDate.of(2026, 9, 10)
        val execution = JobExecution(1L, ApmsSyncPlan(start, start, start, end).parameters())
        `when`(
            api.getAbandonmentAnimals(
                anyString(), anyInt(), anyInt(), anyString(), anyString(),
                isNull<String>(), isNull<String>(), eq("json"), nullable(String::class.java), nullable(String::class.java),
            ),
        ).thenAnswer { call -> response(0, listOf(), call.getArgument(1)) }
        return Fixture(api, execution, ApmsAnimalSnapshot(api, "test-key", execution, requests, 50000))
    }

    private fun animal(number: String, date: String): ApmsAnimal {
        return ApmsAnimal.builder().desertionNo(number).happenDt(date).updTm("2026-09-10 10:00:00").build()
    }

    private data class Fixture(
        val api: ApmsApiClient,
        val execution: JobExecution,
        val snapshot: ApmsAnimalSnapshot,
    )

    private fun response(total: Int, items: List<ApmsAnimal>, page: Int): ApmsRootResponse<ApmsAnimal> {
        return ApmsRootResponse(
            ApmsResponse(
                ApmsHeader.builder().resultCode("00").build(),
                ApmsBody(ApmsItems(items), "1000", page.toString(), total.toString()),
            ),
        )
    }
}
