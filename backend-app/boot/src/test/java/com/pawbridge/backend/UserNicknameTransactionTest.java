package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pawbridge.backend.boot.configuration.UserNicknameConfiguration;
import com.pawbridge.backend.user.internal.nickname.application.ChangeUserNickname;
import com.pawbridge.backend.user.internal.nickname.application.NicknameDuplicateException;
import com.pawbridge.backend.user.internal.nickname.application.UserNicknameStore;
import com.pawbridge.backend.user.internal.nickname.web.UserNicknameController;
import com.pawbridge.backend.user.internal.nickname.web.UserNicknameExceptionHandler;
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Real production proxy/advisor with a recording transaction resource; opens no database connection. */
class UserNicknameTransactionTest {
    private AnnotationConfigApplicationContext context;
    private RecordingStore users;
    private RecordingTransactionManager transactions;
    private ChangeUserNickname change;

    @BeforeEach
    void assembleProductionNicknameConfigurationWithRecordingResource() {
        context = new AnnotationConfigApplicationContext(RecordingInputs.class, UserNicknameConfiguration.class);
        users = context.getBean(RecordingStore.class);
        transactions = context.getBean(RecordingTransactionManager.class);
        change = context.getBean(ChangeUserNickname.class);
    }

    @AfterEach
    void closeContextAndClearPrincipal() {
        SecurityContextHolder.clearContext();
        if (context != null) context.close();
    }

    @Test
    void given__production_configuration__when__changing__then__real_transaction_advisor_wraps_lookup_duplicate_check_update_and_commit() {
        assertThat(AopUtils.isCglibProxy(change)).isTrue();
        assertThat(((Advised) change).getAdvisors()).anySatisfy(advisor ->
                assertThat(advisor.getAdvice()).isInstanceOf(TransactionInterceptor.class));

        change.change(7, "새닉네임");

        assertThat(users.events).containsExactly("begin", "find:7", "exists:새닉네임", "update:7:새닉네임", "commit");
        assertReturned();
    }

    @Test
    void given__unchanged_nickname__when__changing__then__lookup_runs_in_writer_transaction_but_duplicate_update_and_auditing_are_skipped() {
        change.change(7, "기존닉네임");

        assertThat(users.events).containsExactly("begin", "find:7", "commit");
        assertReturned();
    }

    @Test
    void given__missing_user__when__changing__then__rollback_follows_lookup_without_duplicate_or_update() {
        users.current = Optional.empty();

        assertThatThrownBy(() -> change.change(7, "새닉네임")).isInstanceOf(UserNotFoundException.class);

        assertThat(users.events).containsExactly("begin", "find:7", "rollback");
        assertReturned();
    }

    @Test
    void given__duplicate_nickname__when__changing__then__rollback_follows_duplicate_check_without_update() {
        users.duplicate = true;

        assertThatThrownBy(() -> change.change(7, "새닉네임")).isInstanceOf(NicknameDuplicateException.class);

        assertThat(users.events).containsExactly("begin", "find:7", "exists:새닉네임", "rollback");
        assertReturned();
    }

    @Test
    void given__update_failure_then_recovery__when__changing__then__original_failure_rolls_back_and_next_request_uses_new_transaction() {
        var failure = new DataAccessResourceFailureException("synthetic-private-database-message");
        users.updateFailure = failure;

        assertThatThrownBy(() -> change.change(7, "새닉네임")).isSameAs(failure);
        assertThat(users.events).containsExactly("begin", "find:7", "exists:새닉네임", "update:7:새닉네임", "rollback");
        assertReturned();
        users.events.clear(); users.updateFailure = null;

        change.change(7, "새닉네임");

        assertThat(users.events).containsExactly("begin", "find:7", "exists:새닉네임", "update:7:새닉네임", "commit");
        assertReturned();
    }

    @Test
    void given__transaction_start_failure__when__changing__then__no_store_call_precedes_failure_and_next_request_recovers() {
        var failure = new CannotCreateTransactionException("synthetic-private-start-message");
        transactions.startFailure = failure;

        assertThatThrownBy(() -> change.change(7, "새닉네임")).isSameAs(failure);
        assertThat(users.events).containsExactly("begin");
        assertReturned();
        users.events.clear(); transactions.startFailure = null;

        change.change(7, "새닉네임");

        assertThat(users.events).containsExactly("begin", "find:7", "exists:새닉네임", "update:7:새닉네임", "commit");
        assertReturned();
    }

    @ParameterizedTest
    @MethodSource("commitFailures")
    void given__commit_failure_after_successful_store__when__requesting_through_real_proxy__then__http_never_returns_success_and_classifies_only_structured_charset_metadata(
            RuntimeException failure, int expectedStatus, String message) throws Exception {
        transactions.commitFailure = failure;
        var mvc = MockMvcBuilders.standaloneSetup(context.getBean(UserNicknameController.class))
                .setControllerAdvice(context.getBean(UserNicknameExceptionHandler.class))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(7L, null, List.of()));

        mvc.perform(put("/api/v1/users/me/nickname").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"새닉네임\"}"))
                .andExpect(status().is(expectedStatus))
                .andExpect(content().json("{\"code\":" + expectedStatus + ",\"message\":\"" + message + "\",\"data\":null}", JsonCompareMode.STRICT));

        assertThat(users.events).containsExactly("begin", "find:7", "exists:새닉네임", "update:7:새닉네임", "commit");
        assertReturned();
        users.events.clear(); transactions.commitFailure = null;

        mvc.perform(put("/api/v1/users/me/nickname").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"새닉네임\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"code\":200,\"message\":\"닉네임이 변경되었습니다.\",\"data\":null}", JsonCompareMode.STRICT));
        assertThat(users.events).containsExactly("begin", "find:7", "exists:새닉네임", "update:7:새닉네임", "commit");
        assertReturned();
    }

    static Stream<Arguments> commitFailures() {
        return Stream.of(
                Arguments.of(new TransactionSystemException("synthetic-private-commit-message"), 500, "서버 에러!"),
                Arguments.of(new TransactionSystemException("synthetic-private-commit-message", postgres("23514", "ck_rollback_charset_users")),
                        400, "일부 이모지 등 지원하지 않는 문자를 제외해 주세요."),
                Arguments.of(new TransactionSystemException("synthetic-private-commit-message", postgres("23505", "uk_users_nickname_folded")),
                        500, "서버 에러!"));
    }

    private static PSQLException postgres(String state, String constraint) {
        return new PSQLException(new ServerErrorMessage("SERROR\0C" + state + "\0spawbridge_user\0n" + constraint
                + "\0Msynthetic-private-database-detail\0\0"));
    }

    private void assertReturned() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
    }

    @Configuration(proxyBeanMethods = false)
    static class RecordingInputs {
        @Bean @Primary RecordingStore recordingStore() { return new RecordingStore(); }
        @Bean("writeJdbc") JdbcTemplate writeJdbc() { return mock(JdbcTemplate.class); }
        @Bean("writeTransactionManager") RecordingTransactionManager writeTransactionManager(RecordingStore users) {
            return new RecordingTransactionManager(users.events);
        }
    }

    static final class RecordingStore implements UserNicknameStore {
        private final List<String> events = new ArrayList<>();
        private Optional<String> current = Optional.of("기존닉네임");
        private boolean duplicate;
        private RuntimeException updateFailure;

        @Override public Optional<String> findNickname(long userId) {
            inWriterTransaction(); events.add("find:" + userId); return current;
        }

        @Override public boolean existsByNickname(String nickname) {
            inWriterTransaction(); events.add("exists:" + nickname); return duplicate;
        }

        @Override public void updateNickname(long userId, String nickname) {
            inWriterTransaction(); events.add("update:" + userId + ":" + nickname);
            if (updateFailure != null) throw updateFailure;
        }

        private static void inWriterTransaction() {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionName()).isEqualTo(ChangeUserNickname.class.getName() + ".change");
        }
    }

    static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private final List<String> events;
        private RuntimeException startFailure;
        private RuntimeException commitFailure;

        RecordingTransactionManager(List<String> events) { this.events = events; }
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            assertThat(definition.isReadOnly()).isFalse();
            assertThat(definition.getTimeout()).isEqualTo(10);
            events.add("begin");
            if (startFailure != null) throw startFailure;
        }
        @Override protected void doCommit(DefaultTransactionStatus status) {
            events.add("commit");
            if (commitFailure != null) throw commitFailure;
        }
        @Override protected void doRollback(DefaultTransactionStatus status) { events.add("rollback"); }
    }
}
