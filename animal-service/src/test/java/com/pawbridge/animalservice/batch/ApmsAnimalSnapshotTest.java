package com.pawbridge.animalservice.batch;

import com.pawbridge.animalservice.client.ApmsApiClient;
import com.pawbridge.animalservice.dto.apms.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.JobExecution;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApmsAnimalSnapshotTest {
    @Mock private ApmsApiClient api;
    private final ApmsSyncPlan plan = new ApmsSyncPlan(LocalDate.of(2026, 8, 11),
            LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 11), LocalDate.of(2026, 9, 10));

    @BeforeEach
    void defaultEmptyPages() {
        lenient().when(api.getAbandonmentAnimals(anyString(), anyInt(), eq(1000), anyString(), anyString(),
                isNull(), isNull(), eq("json"), nullable(String.class), nullable(String.class)))
                .thenAnswer(call -> page("0", List.of(), call.getArgument(1)));
    }

    @Test
    void givenOldIntakeRecentlyAdopted__whenCollected__thenIncludeWithoutProtectFilter() {
        var old = animal("old", "20260715", "2026-08-23 15:21:46.0", "종료(입양)");
        updatedJuly(page("1", List.of(old), 1));
        assertThat(snapshot().animals()).extracting(ApmsAnimal::getProcessState).containsExactly("종료(입양)");
        verify(api).getAbandonmentAnimals("test-key", 1, 1000, "20260701", "20260731",
                null, null, "json", "20260811", "20260910");
    }

    @Test
    void givenNewAnimalWithoutUpdateTime__whenCollected__thenKeepIntakePath() {
        var fresh = animal("new", "20260908", null, "보호중");
        recent(page("1", List.of(fresh), 1));
        assertThat(snapshot().animals()).containsExactly(fresh);
    }

    @Test
    void givenDuplicateNewNumberWithLaterState__whenCollected__thenWriteOneNewestAnimal() {
        var first = animal("new", "20260908", "2026-09-08 10:00:00.0", "보호중");
        var latest = animal("new", "20260908", "2026-09-09 10:00:00.123", "종료(입양)");
        recent(page("1", List.of(first), 1));
        updatedSeptember(page("1", List.of(latest), 1));
        assertThat(snapshot().animals()).containsExactly(latest);
    }

    @Test
    void givenAlreadyEndedAnimalCorrected__whenCollected__thenKeepLatestCorrection() {
        var latest = animal("ended", "20260908", "2026-09-10 10:00:00", "종료(반환)");
        var stale = animal("ended", "20260908", "2026-09-09 10:00:00", "종료(입양)");
        recent(page("1", List.of(latest), 1));
        updatedSeptember(page("1", List.of(stale), 1));
        assertThat(snapshot().animals()).containsExactly(latest);
    }

    @Test
    void givenSameTimestampWithConflictingStatus__whenCollected__thenFailBeforeAnimalWrites() {
        recent(page("1", List.of(animal("one", "20260908", "2026-09-09 10:00:00", "보호중")), 1));
        updatedSeptember(page("1", List.of(animal("one", "20260908", "2026-09-09 10:00:00.000", "종료(입양)")), 1));
        assertThatThrownBy(() -> snapshot().animals()).hasMessageContaining("conflicting data");
    }

    @Test
    void givenEquivalentTimestampFormats__whenCollected__thenDeduplicate() {
        recent(page("1", List.of(animal("one", "20260908", "2026-09-09 10:00:00", "보호중")), 1));
        updatedSeptember(page("1", List.of(animal("one", "20260908", "2026-09-09 10:00:00.000", "보호중")), 1));
        assertThat(snapshot().animals()).hasSize(1);
    }

    @Test
    void givenDifferentNumbersSharingNotice__whenCollected__thenRetainBoth() {
        var one = animal("one", "20260908", null, "보호중");
        var two = animal("two", "20260908", null, "보호중");
        one.setNoticeNo("same-notice"); two.setNoticeNo("same-notice");
        recent(page("2", List.of(one, two), 1));
        assertThat(snapshot().animals()).hasSize(2);
    }

    @Test
    void givenSnapshotUsedByTwoSteps__whenReadTwice__thenFetchOnlyOnce() {
        var snapshot = snapshot();
        var first = snapshot.animals();
        assertThat(snapshot.animals()).isSameAs(first);
        verify(api, times(4)).getAbandonmentAnimals(anyString(), anyInt(), eq(1000), anyString(), anyString(),
                isNull(), isNull(), eq("json"), nullable(String.class), nullable(String.class));
    }

    @Test
    void givenMultiplePages__whenCollected__thenReadEachPageAndStopAtLast() {
        var first = IntStream.range(0, 1000).mapToObj(i -> animal("id-" + i, "20260908", null, "보호중")).toList();
        recent(page("1001", first, 1));
        when(api.getAbandonmentAnimals("test-key", 2, 1000, "20260811", "20260910", null, null, "json", null, null))
                .thenReturn(page("1001", List.of(animal("last", "20260908", null, "보호중")), 2));
        assertThat(snapshot().animals()).hasSize(1001);
        verify(api, never()).getAbandonmentAnimals(anyString(), eq(3), anyInt(), anyString(), anyString(),
                any(), any(), anyString(), any(), any());
    }

    @Test
    void givenTransportFailure__whenCollected__thenFailWithoutLeakingRequest() {
        when(api.getAbandonmentAnimals("test-key", 1, 1000, "20260811", "20260910", null, null, "json", null, null))
                .thenThrow(new IllegalStateException("private-service-key-placeholder"));
        assertThatThrownBy(() -> snapshot().animals()).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("private-service-key-placeholder").hasNoCause();
    }

    @Test
    void givenProviderError__whenCollected__thenFail() {
        var response = page("0", List.of(), 1); response.getResponse().getHeader().setResultCode("30"); recent(response);
        assertThatThrownBy(() -> snapshot().animals()).hasMessageContaining("unsuccessful");
    }

    @Test
    void givenMissingResponse__whenCollected__thenFail() {
        recent(null);
        assertThatThrownBy(() -> snapshot().animals()).hasMessageContaining("unsuccessful");
    }

    @Test
    void givenPositiveCountAndMissingItems__whenCollected__thenFail() {
        recent(page("3", null, 1));
        assertThatThrownBy(() -> snapshot().animals()).hasMessageContaining("incomplete");
    }

    @Test
    void givenPositiveCountAndEmptyItems__whenCollected__thenFail() {
        recent(page("3", List.of(), 1));
        assertThatThrownBy(() -> snapshot().animals()).hasMessageContaining("incomplete");
    }

    @Test
    void givenTruncatedPage__whenCollected__thenFail() {
        recent(page("2", List.of(animal("one", "20260908", null, "보호중")), 1));
        assertThatThrownBy(() -> snapshot().animals()).hasMessageContaining("incomplete");
    }

    @Test
    void givenSuccessfulZeroCount__whenCollected__thenAcceptEmptyResult() {
        recent(page("0", null, 1));
        assertThat(snapshot().animals()).isEmpty();
    }

    @Test
    void givenRequestBudgetReached__whenAnotherQueryNeeded__thenFailBeforeExtraCall() {
        assertThatThrownBy(() -> snapshot(1, 50000).animals()).hasMessageContaining("request budget");
        verify(api, times(1)).getAbandonmentAnimals(anyString(), anyInt(), anyInt(), anyString(), anyString(),
                any(), any(), anyString(), any(), any());
    }

    @Test
    void givenAnimalBudgetReached__whenNewNumberArrives__thenFail() {
        recent(page("2", List.of(animal("one", "20260908", null, "보호중"), animal("two", "20260908", null, "보호중")), 1));
        assertThatThrownBy(() -> snapshot(100, 1).animals()).hasMessageContaining("animal budget");
    }

    @Test
    void givenProviderIgnoringModifiedFilter__whenCollected__thenFail() {
        updatedJuly(page("1", List.of(animal("old", "20260715", "2026-07-16 10:00:00", "보호중")), 1));
        assertThatThrownBy(() -> snapshot().animals()).hasMessageContaining("update window");
    }

    @Test
    void givenMissingNumber__whenCollected__thenFail() {
        recent(page("1", List.of(animal(null, "20260908", null, "보호중")), 1));
        assertThatThrownBy(() -> snapshot().animals()).hasMessageContaining("number is missing");
    }

    @Test
    void givenMalformedUpdateTime__whenCollected__thenFailInsteadOfChoosingAnArbitraryDuplicate() {
        recent(page("1", List.of(animal("one", "20260908", "bad-date", "보호중")), 1));
        assertThatThrownBy(() -> snapshot().animals()).hasMessageContaining("timestamp is invalid");
    }

    @Test
    void givenImpossibleCalendarDate__whenCollected__thenFailInsteadOfNormalizing() {
        recent(page("1", List.of(animal("one", "20260908", "2026-02-30 10:00:00", "보호중")), 1));
        assertThatThrownBy(() -> snapshot().animals()).hasMessageContaining("timestamp is invalid");
    }

    private ApmsAnimalSnapshot snapshot() { return snapshot(100, 50000); }
    private ApmsAnimalSnapshot snapshot(int requests, int animals) {
        return new ApmsAnimalSnapshot(api, "test-key", new JobExecution(1L, plan.parameters()), requests, animals);
    }
    private ApmsAnimal animal(String number, String intake, String updated, String state) {
        return ApmsAnimal.builder().desertionNo(number).happenDt(intake).updTm(updated).processState(state).build();
    }
    private void recent(ApmsRootResponse<ApmsAnimal> response) {
        when(api.getAbandonmentAnimals("test-key", 1, 1000, "20260811", "20260910", null, null, "json", null, null)).thenReturn(response);
    }
    private void updatedJuly(ApmsRootResponse<ApmsAnimal> response) {
        when(api.getAbandonmentAnimals("test-key", 1, 1000, "20260701", "20260731", null, null, "json", "20260811", "20260910")).thenReturn(response);
    }
    private void updatedSeptember(ApmsRootResponse<ApmsAnimal> response) {
        when(api.getAbandonmentAnimals("test-key", 1, 1000, "20260901", "20260910", null, null, "json", "20260811", "20260910")).thenReturn(response);
    }
    private ApmsRootResponse<ApmsAnimal> page(String total, List<ApmsAnimal> animals, int number) {
        return new ApmsRootResponse<>(new ApmsResponse<>(ApmsHeader.builder().resultCode("00").build(),
                new ApmsBody<>(new ApmsItems<>(animals), "1000", String.valueOf(number), total)));
    }
}
