package com.pawbridge.animalservice.reconciliation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.pawbridge.animalservice.batch.ApmsUpdatedAt;
import com.pawbridge.animalservice.dto.apms.ApmsAnimal;
import com.pawbridge.animalservice.enums.AnimalStatus;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** A reviewed, month-scoped snapshot. Source completeness is independent of apply completion. */
public record HistoryPlan(String contract, String month, Instant capturedAt, String database,
                          String searchIndex, int reported, int observed, List<String> warnings,
                          List<Entry> entries) {
    public static final String CONTRACT = "apms-history-v1";
    public static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    public record State(Long id, String status, String processState, LocalDate happenDate,
                        LocalDateTime sourceUpdatedAt) {
        public boolean sameValues(State other) {
            return Objects.equals(status, other.status) && Objects.equals(processState, other.processState)
                    && Objects.equals(happenDate, other.happenDate)
                    && Objects.equals(sourceUpdatedAt, other.sourceUpdatedAt);
        }
    }
    public record Entry(ApmsAnimal source, State before, long shelterId) { }

    public static State after(ApmsAnimal source, Long id) {
        return new State(id, AnimalStatus.fromCode(source.getProcessState()).name(), source.getProcessState(),
                date(source.getHappenDt()), ApmsUpdatedAt.parse(source.getUpdTm()));
    }
    public static LocalDate date(String value) {
        return LocalDate.parse(Objects.requireNonNull(value, "Missing APMS date"), DateTimeFormatter.BASIC_ISO_DATE);
    }
    public static void validateSource(ApmsAnimal source, YearMonth month) {
        requireText(source.getDesertionNo(), 50);
        requireText(source.getNoticeNo(), 100);
        requireText(source.getCareRegNo(), 50);
        requireText(source.getProcessState(), 50);
        if (!YearMonth.from(date(source.getHappenDt())).equals(month)) fail("Source is outside approved month");
        if (date(source.getNoticeEdt()).isBefore(date(source.getNoticeSdt()))) fail("Invalid notice dates");
        if (ApmsUpdatedAt.parse(source.getUpdTm()) == null) fail("Missing source version");
        if (ApmsUpdatedAt.parse(source.getUpdTm()).getNano() % 1000 != 0) fail("Source precision exceeds MySQL microseconds");
        if (Arrays.stream(AnimalStatus.values()).noneMatch(s -> s != AnimalStatus.UNKNOWN
                && s.getDescription().equals(source.getProcessState()))) fail("Unknown source status");
        if (!Set.of("417000", "422400", "429900").contains(Objects.toString(source.getUpKindCd(), ""))) fail("Unknown species");
        if (!Set.of("M", "F", "Q").contains(Objects.toString(source.getSexCd(), ""))) fail("Unknown sex");
        if (!Set.of("Y", "N", "U").contains(Objects.toString(source.getNeuterYn(), ""))) fail("Unknown neuter status");
        length(source.getKindNm(), 100); length(source.getWeight(), 50); length(source.getColorCd(), 100);
        length(source.getSpecialMark(), 1000); length(source.getHappenPlace(), 200);
        length(source.getPopfile1(), 500); length(source.getPopfile2(), 500);
    }
    public void validate() {
        if (!CONTRACT.equals(contract) || capturedAt == null || database == null || searchIndex == null
                || warnings == null || entries == null || entries.size() > 20000 || observed < entries.size()
                || reported < 0 || observed < 0) fail("Invalid plan contract");
        var period = YearMonth.parse(month);
        var ids = new HashSet<String>();
        for (var entry : entries) {
            validateSource(entry.source(), period);
            if (!ids.add(entry.source().getDesertionNo()) || entry.shelterId() <= 0) fail("Duplicate ID or missing shelter");
            if (entry.before() != null) {
                var before = entry.before();
                var target = after(entry.source(), before.id());
                if (before.id() == null || before.id() <= 0 || target.sameValues(before)) fail("Invalid update candidate");
                if (before.sourceUpdatedAt() != null && !target.sourceUpdatedAt().isAfter(before.sourceUpdatedAt()))
                    fail("Source is not newer than reviewed DB version");
            }
        }
    }
    private static void requireText(String value, int max) {
        if (value == null || value.isBlank()) fail("Required APMS field is missing");
        length(value, max);
    }
    private static void length(String value, int max) {
        if (value != null && value.codePointCount(0, value.length()) > max) fail("APMS field exceeds DB limit");
    }
    public static void fail(String message) { throw new RejectedPlanException(message); }
    public static class RejectedPlanException extends IllegalStateException {
        public RejectedPlanException(String message) { super(message); }
    }
}
