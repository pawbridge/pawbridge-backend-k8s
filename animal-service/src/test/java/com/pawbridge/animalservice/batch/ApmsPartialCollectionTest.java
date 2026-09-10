package com.pawbridge.animalservice.batch;

import com.pawbridge.animalservice.client.ApmsApiClient;
import com.pawbridge.animalservice.dto.apms.*;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobExecution;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApmsPartialCollectionTest {
    @Test
    void given991ReportedBut990Returned__whenCollected__thenKeepConfirmedAnimalsAndRecordIncompleteWindow() {
        var api = mock(ApmsApiClient.class);
        var day = LocalDate.of(2026, 9, 10);
        var execution = new JobExecution(1L, new ApmsSyncPlan(day, day, day, day).parameters());
        var items = IntStream.range(0, 990).mapToObj(i -> ApmsAnimal.builder().desertionNo("id-" + i)
                .happenDt("20260910").updTm("2026-09-10 10:00:00").build()).toList();
        when(api.getAbandonmentAnimals(anyString(), anyInt(), anyInt(), anyString(), anyString(),
                isNull(), isNull(), eq("json"), nullable(String.class), nullable(String.class)))
                .thenAnswer(call -> "20260910".equals(call.getArgument(4))
                        ? response(991, items, call.getArgument(1)) : response(0, List.of(), call.getArgument(1)));
        var snapshot = new ApmsAnimalSnapshot(api, "test-key", execution, 100, 50000);
        assertThat(snapshot.animals()).hasSize(990);
        assertThat(execution.getExecutionContext().getInt("apms.sync.incompleteQueryCount")).isPositive();
        assertThat(execution.getExecutionContext().getString("apms.sync.incompleteQueryDetails")).contains("COUNT_MISMATCH");
    }

    @Test
    void givenTransportFailureInOneWindow__whenCollected__thenKeepHealthyWindowWithoutLeakingCredential() {
        var api = mock(ApmsApiClient.class);
        var day = LocalDate.of(2026, 9, 10);
        var execution = new JobExecution(1L, new ApmsSyncPlan(day, day, day, day).parameters());
        var animal = ApmsAnimal.builder().desertionNo("healthy").happenDt("20260910").build();
        when(api.getAbandonmentAnimals(anyString(), anyInt(), anyInt(), anyString(), anyString(),
                isNull(), isNull(), eq("json"), nullable(String.class), nullable(String.class)))
                .thenAnswer(call -> {
                    if (call.getArgument(8) != null) throw new IllegalStateException("secret-placeholder");
                    return response(1, List.of(animal), call.getArgument(1));
                });
        assertThat(new ApmsAnimalSnapshot(api, "test-key", execution, 100, 50000).animals()).containsExactly(animal);
        assertThat(execution.getExecutionContext().getInt("apms.sync.incompleteQueryCount")).isEqualTo(1);
        assertThat(execution.getExecutionContext().getString("apms.sync.incompleteQueryDetails"))
                .contains("REQUEST_FAILED").doesNotContain("secret-placeholder");
    }

    @Test
    void givenTransientTruncation__whenRetryReturnsMissingAnimal__thenCompleteWithoutDuplicateWrites() {
        var f = fixture(100);
        var one = animal("one", "20260909");
        var two = animal("two", "20260910");
        when(f.api.getAbandonmentAnimals("test-key", 1, 1000, "20260909", "20260910", null, null, "json", null, null))
                .thenReturn(response(2, List.of(one), 1), response(2, List.of(one, two), 1));
        assertThat(f.snapshot.animals()).containsExactly(one, two);
        assertThat(f.execution.getExecutionContext().getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT)).isZero();
        verify(f.api, times(2)).getAbandonmentAnimals("test-key", 1, 1000, "20260909", "20260910", null, null, "json", null, null);
    }

    @Test
    void givenTruncatedInterval__whenTwoDaysReconcileAllExpectedAnimals__thenComplete() {
        var f = fixture(100);
        splitResponses(f, 2);
        assertThat(f.snapshot.animals()).extracting(ApmsAnimal::getDesertionNo).containsExactly("one", "two");
        assertThat(f.execution.getExecutionContext().getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT)).isZero();
    }

    @Test
    void givenChildrenWithLowerTotal__whenSplitReturnsFewerThanParentReported__thenKeepUnresolved() {
        var f = fixture(100);
        splitResponses(f, 3);
        assertThat(f.snapshot.animals()).hasSize(2);
        assertThat(f.execution.getExecutionContext().getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT)).isEqualTo(1);
        assertThat(f.execution.getExecutionContext().getString(ApmsAnimalSnapshot.INCOMPLETE_DETAILS))
                .contains("reported=3", "observed=2", "COUNT_MISMATCH");
    }

    @Test
    void givenBudgetExhaustedAfterHealthyQuery__whenCollected__thenRetainDataAndRecordUnqueriedWindow() {
        var f = fixture(1);
        var one = animal("one", "20260909");
        when(f.api.getAbandonmentAnimals("test-key", 1, 1000, "20260909", "20260910", null, null, "json", null, null))
                .thenReturn(response(1, List.of(one), 1));
        assertThat(f.snapshot.animals()).containsExactly(one);
        assertThat(f.execution.getExecutionContext().getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT)).isEqualTo(1);
        assertThat(f.execution.getExecutionContext().getString(ApmsAnimalSnapshot.INCOMPLETE_DETAILS)).contains("REQUEST_BUDGET");
        verify(f.api, times(1)).getAbandonmentAnimals(anyString(), anyInt(), anyInt(), anyString(), anyString(),
                isNull(), isNull(), eq("json"), nullable(String.class), nullable(String.class));
    }

    @Test
    void givenEarlyFailureAndSmallBudget__whenCollected__thenTryOtherWindowBeforeRetryingFailure() {
        var f = fixture(2);
        var one = animal("one", "20260909");
        when(f.api.getAbandonmentAnimals("test-key", 1, 1000, "20260909", "20260910", null, null, "json", null, null))
                .thenThrow(new IllegalStateException("offline"));
        when(f.api.getAbandonmentAnimals("test-key", 1, 1000, "20260901", "20260910", null, null, "json", "20260909", "20260910"))
                .thenReturn(response(1, List.of(one), 1));
        assertThat(f.snapshot.animals()).containsExactly(one);
        assertThat(f.execution.getExecutionContext().getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT)).isEqualTo(1);
        verify(f.api, times(1)).getAbandonmentAnimals("test-key", 1, 1000, "20260909", "20260910", null, null, "json", null, null);
    }

    @Test
    void givenRepeatedFullPage__whenProviderRepeatsIdsInsteadOfNextPage__thenDoNotClaimComplete() {
        var f = fixture(100);
        var items = IntStream.range(0, 1000).mapToObj(i -> animal("id-" + i, "20260909")).toList();
        when(f.api.getAbandonmentAnimals(eq("test-key"), anyInt(), eq(1000), eq("20260909"), eq("20260910"),
                isNull(), isNull(), eq("json"), isNull(), isNull()))
                .thenAnswer(call -> response(2000, items, call.getArgument(1)));
        assertThat(f.snapshot.animals()).hasSize(1000);
        assertThat(f.execution.getExecutionContext().getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT)).isEqualTo(1);
        verify(f.api, never()).getAbandonmentAnimals(anyString(), eq(3), anyInt(), anyString(), anyString(),
                isNull(), isNull(), eq("json"), nullable(String.class), nullable(String.class));
    }

    @Test
    void givenEmptySecondPageBeforeTotalReached__whenCollected__thenKeepFirstPageAndRecordGap() {
        var f = fixture(100);
        var items = IntStream.range(0, 1000).mapToObj(i -> animal("id-" + i, "20260909")).toList();
        when(f.api.getAbandonmentAnimals("test-key", 1, 1000, "20260909", "20260910", null, null, "json", null, null))
                .thenReturn(response(1001, items, 1));
        when(f.api.getAbandonmentAnimals("test-key", 2, 1000, "20260909", "20260910", null, null, "json", null, null))
                .thenReturn(response(1001, List.of(), 2));
        assertThat(f.snapshot.animals()).hasSize(1000);
        assertThat(f.execution.getExecutionContext().getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT)).isEqualTo(1);
    }

    private void splitResponses(Fixture f, int parentTotal) {
        var one = animal("one", "20260909");
        var two = animal("two", "20260910");
        when(f.api.getAbandonmentAnimals("test-key", 1, 1000, "20260909", "20260910", null, null, "json", null, null))
                .thenReturn(response(parentTotal, List.of(one), 1));
        when(f.api.getAbandonmentAnimals("test-key", 1, 1000, "20260909", "20260909", null, null, "json", null, null))
                .thenReturn(response(1, List.of(one), 1));
        when(f.api.getAbandonmentAnimals("test-key", 1, 1000, "20260910", "20260910", null, null, "json", null, null))
                .thenReturn(response(1, List.of(two), 1));
    }

    private Fixture fixture(int requests) {
        var api = mock(ApmsApiClient.class);
        var start = LocalDate.of(2026, 9, 9);
        var end = LocalDate.of(2026, 9, 10);
        var execution = new JobExecution(1L, new ApmsSyncPlan(start, start, start, end).parameters());
        when(api.getAbandonmentAnimals(anyString(), anyInt(), anyInt(), anyString(), anyString(),
                isNull(), isNull(), eq("json"), nullable(String.class), nullable(String.class)))
                .thenAnswer(call -> response(0, List.of(), call.getArgument(1)));
        return new Fixture(api, execution, new ApmsAnimalSnapshot(api, "test-key", execution, requests, 50000));
    }

    private ApmsAnimal animal(String number, String date) {
        return ApmsAnimal.builder().desertionNo(number).happenDt(date).updTm("2026-09-10 10:00:00").build();
    }

    private record Fixture(ApmsApiClient api, JobExecution execution, ApmsAnimalSnapshot snapshot) { }

    private ApmsRootResponse<ApmsAnimal> response(int total, List<ApmsAnimal> items, int page) {
        return new ApmsRootResponse<>(new ApmsResponse<>(ApmsHeader.builder().resultCode("00").build(),
                new ApmsBody<>(new ApmsItems<>(items), "1000", String.valueOf(page), String.valueOf(total))));
    }
}
