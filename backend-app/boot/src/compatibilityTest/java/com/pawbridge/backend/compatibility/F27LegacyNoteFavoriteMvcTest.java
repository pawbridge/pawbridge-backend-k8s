package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.core.context.SecurityContextHolder;

/** Eight preserved original-only binding maps; recording JDBC/TM, not PostgreSQL or socket evidence. */
class F27LegacyNoteFavoriteMvcTest {
    @ParameterizedTest @MethodSource("inputs")
    void given__recorded_original_body_or_uuid__when__both_actual_controllers_bind__then__require_measured_wire_value_and_the_same_transaction_clock_hint_order(
            String label, String path, String body, Measured expected) throws Exception {
        measured(label, expected);
        var fixture = new F27MvcFixture();
        try (var context = new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)) {
            var mapper = context.getBean(ObjectMapper.class);
            var before = fixture.favorite(fixture.sourceMvc(mapper), path, body, false);
            var after = fixture.favorite(fixture.candidateMvc(mapper), path, body, true);
            assertThat(F27MvcFixture.observation(before, fixture.source, fixture.sourceReached, fixture.sourceId, fixture.sourceRequested))
                    .isEqualTo(expected.values());
            assertThat(F27MvcFixture.observation(after, fixture.adapted, fixture.candidateReached, fixture.candidateId, fixture.candidateRequested))
                    .isEqualTo(expected.values());
            fixture.agree();
        } finally { SecurityContextHolder.clearContext(); }
    }

    @ParameterizedTest @ValueSource(strings = {"SENT", "already-true", "hint-failure"})
    void given__sent_or_same_value_or_post_commit_hint_failure__when__both_set_true__then__still_update_and_hint_after_commit_without_read_at_changes(String kind) throws Exception {
        canonical();
        var fixture = new F27MvcFixture();
        if (kind.equals("SENT")) fixture.setDirection("SENT");
        if (kind.equals("already-true")) fixture.setInitialFavorite(true);
        fixture.hintFailure = kind.equals("hint-failure");
        try (var context = new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)) {
            var mapper = context.getBean(ObjectMapper.class);
            var before = fixture.favorite(fixture.sourceMvc(mapper), F27MvcFixture.NOTE.toString(), "{\"favorite\":true}", false);
            var after = fixture.favorite(fixture.candidateMvc(mapper), F27MvcFixture.NOTE.toString(), "{\"favorite\":true}", true);
            for (var result : java.util.List.of(before, after)) {
                assertThat(result.getResponse().getStatus()).isEqualTo(200); assertThat(F27MvcFixture.body(result)).isEmpty();
                F27MvcFixture.cache(result);
            }
            fixture.agree();
            assertThat(fixture.source.phases).containsExactly("begin", "insert:7", "member-lock:7", "current:7", "clock", "find",
                    "note-lock", "clock", "find", "favorite-update", "commit", "hint:7");
            assertThat(fixture.source.favorite).isTrue(); assertThat(fixture.source.readAt).isEqualTo(F27MvcFixture.READ_AT);
            assertThat(fixture.source.clock.calls).isEqualTo(2); assertThat(fixture.source.jdbc.updates).isOne();
        } finally { SecurityContextHolder.clearContext(); }
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "inactive", "member", "DAO", "BEGIN"})
    void given__owned_lookup_current_or_sql_transaction_failure__when__both_handle__then__keep_source_error_policy_and_recording_rollback_without_hint(String kind) throws Exception {
        canonical();
        var fixture = new F27MvcFixture();
        if (kind.equals("missing")) fixture.missing();
        fixture.inactive = kind.equals("inactive"); fixture.memberFailure = kind.equals("member");
        if (kind.equals("DAO") || kind.equals("BEGIN")) fixture.fail(kind);
        int status = kind.equals("missing") ? 404 : kind.equals("inactive") ? 403 : 503;
        String message = switch (kind) {
            case "missing" -> "쪽지를 찾을 수 없습니다.";
            case "inactive" -> "이용할 수 없는 회원입니다.";
            case "member" -> "회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.";
            case "DAO" -> "쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.";
            default -> "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";
        };
        try (var context = new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)) {
            var mapper = context.getBean(ObjectMapper.class);
            var before = fixture.favorite(fixture.sourceMvc(mapper), F27MvcFixture.NOTE.toString(), "{\"favorite\":true}", false);
            var after = fixture.favorite(fixture.candidateMvc(mapper), F27MvcFixture.NOTE.toString(), "{\"favorite\":true}", true);
            for (var result : java.util.List.of(before, after)) {
                assertThat(result.getResponse().getStatus()).isEqualTo(status);
                assertThat(mapper.readTree(F27MvcFixture.body(result))).isEqualTo(mapper.valueToTree(Map.of("message", message)));
                assertThat(F27MvcFixture.body(result)).doesNotContain("private-", "SELECT", "UPDATE", "Exception");
                F27MvcFixture.cache(result);
            }
            fixture.agree(); assertThat(fixture.source.phases).doesNotContain("hint:7", "commit");
            assertThat(fixture.source.favorite).isFalse(); assertThat(fixture.source.readAt).isEqualTo(F27MvcFixture.READ_AT);
            if (kind.equals("BEGIN")) {
                assertThat(fixture.source.phases).containsExactly("begin"); assertThat(fixture.source.clock.calls).isZero();
            } else {
                assertThat(fixture.source.phases.getLast()).isEqualTo("rollback");
                assertThat(fixture.source.phases).contains("current:7");
                assertThat(fixture.source.clock.calls).isEqualTo(kind.equals("DAO") ? 2 : kind.equals("missing") ? 1 : 0);
            }
        } finally { SecurityContextHolder.clearContext(); }
    }

    record Measured(Map<String, Object> values) { }
    private static void measured(String label, Measured value) {
        assertThat(value).as("UNMEASURED F27 original '%s'; fill only from complete original-only eight observations", label).isNotNull();
    }
    private static void canonical() { measured("true", (Measured) inputs().findFirst().orElseThrow().get()[3]); }
    // Literal maps came only from the complete original-only JSON SHA eff87b02c348c147c1acee6ec02aa905d41333f66724ac4813b961dfc9c582df.
    private static Measured observed(String json) {
        try { return new Measured(new ObjectMapper().readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { })); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("Invalid measured F27 literal", failure); }
    }
    static Stream<Arguments> inputs() { return Stream.of(
            Arguments.of("true", F27MvcFixture.NOTE.toString(), "{\"favorite\":true}", observed("{\"status\":200,\"body\":\"\",\"cacheControl\":\"no-store\",\"setCookieCount\":0,\"contentType\":null,\"async\":false,\"serviceReached\":1,\"boundId\":\"f2700000-0000-0000-0000-000000000001\",\"boundFavorite\":true,\"phases\":[\"begin\",\"insert:7\",\"member-lock:7\",\"current:7\",\"clock\",\"find\",\"note-lock\",\"clock\",\"find\",\"favorite-update\",\"commit\",\"hint:7\"],\"clockCalls\":2,\"sqlCalls\":6,\"updateCalls\":1,\"favoriteAfter\":true,\"readAtAfter\":\"2030-12-31T23:59:55.123456Z\"}")),
            Arguments.of("false", F27MvcFixture.NOTE.toString(), "{\"favorite\":false}", observed("{\"status\":200,\"body\":\"\",\"cacheControl\":\"no-store\",\"setCookieCount\":0,\"contentType\":null,\"async\":false,\"serviceReached\":1,\"boundId\":\"f2700000-0000-0000-0000-000000000001\",\"boundFavorite\":false,\"phases\":[\"begin\",\"insert:7\",\"member-lock:7\",\"current:7\",\"clock\",\"find\",\"note-lock\",\"clock\",\"find\",\"favorite-update\",\"commit\",\"hint:7\"],\"clockCalls\":2,\"sqlCalls\":6,\"updateCalls\":1,\"favoriteAfter\":false,\"readAtAfter\":\"2030-12-31T23:59:55.123456Z\"}")),
            Arguments.of("empty-object", F27MvcFixture.NOTE.toString(), "{}", observed("{\"status\":200,\"body\":\"\",\"cacheControl\":\"no-store\",\"setCookieCount\":0,\"contentType\":null,\"async\":false,\"serviceReached\":1,\"boundId\":\"f2700000-0000-0000-0000-000000000001\",\"boundFavorite\":false,\"phases\":[\"begin\",\"insert:7\",\"member-lock:7\",\"current:7\",\"clock\",\"find\",\"note-lock\",\"clock\",\"find\",\"favorite-update\",\"commit\",\"hint:7\"],\"clockCalls\":2,\"sqlCalls\":6,\"updateCalls\":1,\"favoriteAfter\":false,\"readAtAfter\":\"2030-12-31T23:59:55.123456Z\"}")),
            Arguments.of("field-null", F27MvcFixture.NOTE.toString(), "{\"favorite\":null}", observed("{\"status\":200,\"body\":\"\",\"cacheControl\":\"no-store\",\"setCookieCount\":0,\"contentType\":null,\"async\":false,\"serviceReached\":1,\"boundId\":\"f2700000-0000-0000-0000-000000000001\",\"boundFavorite\":false,\"phases\":[\"begin\",\"insert:7\",\"member-lock:7\",\"current:7\",\"clock\",\"find\",\"note-lock\",\"clock\",\"find\",\"favorite-update\",\"commit\",\"hint:7\"],\"clockCalls\":2,\"sqlCalls\":6,\"updateCalls\":1,\"favoriteAfter\":false,\"readAtAfter\":\"2030-12-31T23:59:55.123456Z\"}")),
            Arguments.of("literal-null", F27MvcFixture.NOTE.toString(), "null", observed("{\"status\":400,\"body\":\"{\\\"message\\\":\\\"요청 형식과 로그인 정보를 확인해 주세요.\\\"}\",\"cacheControl\":\"no-store\",\"setCookieCount\":0,\"contentType\":\"application/json\",\"async\":false,\"serviceReached\":0,\"boundId\":null,\"boundFavorite\":null,\"phases\":[],\"clockCalls\":0,\"sqlCalls\":0,\"updateCalls\":0,\"favoriteAfter\":false,\"readAtAfter\":\"2030-12-31T23:59:55.123456Z\"}")),
            Arguments.of("absent-body", F27MvcFixture.NOTE.toString(), null, observed("{\"status\":400,\"body\":\"{\\\"message\\\":\\\"요청 형식과 로그인 정보를 확인해 주세요.\\\"}\",\"cacheControl\":\"no-store\",\"setCookieCount\":0,\"contentType\":\"application/json\",\"async\":false,\"serviceReached\":0,\"boundId\":null,\"boundFavorite\":null,\"phases\":[],\"clockCalls\":0,\"sqlCalls\":0,\"updateCalls\":0,\"favoriteAfter\":false,\"readAtAfter\":\"2030-12-31T23:59:55.123456Z\"}")),
            Arguments.of("invalid-json", F27MvcFixture.NOTE.toString(), "{broken", observed("{\"status\":400,\"body\":\"{\\\"message\\\":\\\"요청 형식과 로그인 정보를 확인해 주세요.\\\"}\",\"cacheControl\":\"no-store\",\"setCookieCount\":0,\"contentType\":\"application/json\",\"async\":false,\"serviceReached\":0,\"boundId\":null,\"boundFavorite\":null,\"phases\":[],\"clockCalls\":0,\"sqlCalls\":0,\"updateCalls\":0,\"favoriteAfter\":false,\"readAtAfter\":\"2030-12-31T23:59:55.123456Z\"}")),
            Arguments.of("invalid-uuid", "not-a-uuid", "{\"favorite\":true}", observed("{\"status\":400,\"body\":\"{\\\"message\\\":\\\"요청 형식과 로그인 정보를 확인해 주세요.\\\"}\",\"cacheControl\":\"no-store\",\"setCookieCount\":0,\"contentType\":\"application/json\",\"async\":false,\"serviceReached\":0,\"boundId\":null,\"boundFavorite\":null,\"phases\":[],\"clockCalls\":0,\"sqlCalls\":0,\"updateCalls\":0,\"favoriteAfter\":false,\"readAtAfter\":\"2030-12-31T23:59:55.123456Z\"}"))); }
}
