package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.UserNicknameConfiguration
import com.pawbridge.backend.user.internal.nickname.application.ChangeUserNickname
import com.pawbridge.backend.user.internal.nickname.application.NicknameDuplicateException
import com.pawbridge.backend.user.internal.nickname.application.UserNicknameStore
import com.pawbridge.backend.user.internal.nickname.web.UserNicknameController
import com.pawbridge.backend.user.internal.nickname.web.UserNicknameExceptionHandler
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import io.mockk.justRun
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.postgresql.util.PSQLException
import org.postgresql.util.ServerErrorMessage
import org.springframework.aop.framework.Advised
import org.springframework.aop.support.AopUtils
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.interceptor.TransactionInterceptor
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.Optional
import java.util.stream.Stream

/** Real production proxy/advisor with a recording transaction resource; opens no database connection. */
class UserNicknameTransactionTest {
    private var context: AnnotationConfigApplicationContext? = null
    private lateinit var users: RecordingStore
    private lateinit var transactions: RecordingTransactionManager
    private lateinit var change: ChangeUserNickname

    @BeforeEach
    fun assembleProductionNicknameConfigurationWithRecordingResource() {
        val assembled = AnnotationConfigApplicationContext(RecordingInputs::class.java, UserNicknameConfiguration::class.java)
        context = assembled
        users = assembled.getBean(RecordingStore::class.java)
        transactions = assembled.getBean(RecordingTransactionManager::class.java)
        change = assembled.getBean(ChangeUserNickname::class.java)
    }

    @AfterEach
    fun closeContextAndClearPrincipal() {
        SecurityContextHolder.clearContext()
        context?.close()
    }

    @Test
    fun `운영 구성이 주어졌을 때__닉네임을 바꾸면__실제 트랜잭션 어드바이저가 조회·중복 확인·수정·커밋을 감싼다`() {
        // given
        assertThat(AopUtils.isCglibProxy(change)).isTrue()
        assertThat((change as Advised).advisors).anySatisfy { advisor ->
            assertThat(advisor.advice).isInstanceOf(TransactionInterceptor::class.java)
        }

        // when
        change.change(7, "새닉네임")

        // then
        assertThat(users.events).containsExactly("begin", "find:7", "exists:새닉네임", "update:7:새닉네임", "commit")
        assertReturned()
    }

    @Test
    fun `닉네임이 그대로일 때__바꾸면__쓰기 트랜잭션에서 조회만 하고 중복 확인·수정·감사는 건너뛴다`() {
        // given
        val unchanged = "기존닉네임"

        // when
        change.change(7, unchanged)

        // then
        assertThat(users.events).containsExactly("begin", "find:7", "commit")
        assertReturned()
    }

    @Test
    fun `회원이 없을 때__바꾸면__조회 뒤 중복 확인이나 수정 없이 롤백한다`() {
        // given
        users.current = Optional.empty()

        // when
        val thrown = assertThatThrownBy { change.change(7, "새닉네임") }

        // then
        thrown.isInstanceOf(UserNotFoundException::class.java)
        assertThat(users.events).containsExactly("begin", "find:7", "rollback")
        assertReturned()
    }

    @Test
    fun `닉네임이 중복일 때__바꾸면__중복 확인 뒤 수정 없이 롤백한다`() {
        // given
        users.duplicate = true

        // when
        val thrown = assertThatThrownBy { change.change(7, "새닉네임") }

        // then
        thrown.isInstanceOf(NicknameDuplicateException::class.java)
        assertThat(users.events).containsExactly("begin", "find:7", "exists:새닉네임", "rollback")
        assertReturned()
    }

    @Test
    fun `수정 실패 뒤 복구되는 경우__바꾸면__원래 실패를 롤백하고 다음 요청은 새 트랜잭션을 쓴다`() {
        // given
        val failure = DataAccessResourceFailureException("synthetic-private-database-message")
        users.updateFailure = failure

        // when
        val thrown = assertThatThrownBy { change.change(7, "새닉네임") }

        // then
        thrown.isSameAs(failure)
        assertThat(users.events).containsExactly("begin", "find:7", "exists:새닉네임", "update:7:새닉네임", "rollback")
        assertReturned()
        users.events.clear()
        users.updateFailure = null

        change.change(7, "새닉네임")

        assertThat(users.events).containsExactly("begin", "find:7", "exists:새닉네임", "update:7:새닉네임", "commit")
        assertReturned()
    }

    @Test
    fun `트랜잭션 시작이 실패할 때__바꾸면__저장소 호출 없이 실패하고 다음 요청은 복구된다`() {
        // given
        val failure = CannotCreateTransactionException("synthetic-private-start-message")
        transactions.startFailure = failure

        // when
        val thrown = assertThatThrownBy { change.change(7, "새닉네임") }

        // then
        thrown.isSameAs(failure)
        assertThat(users.events).containsExactly("begin")
        assertReturned()
        users.events.clear()
        transactions.startFailure = null

        change.change(7, "새닉네임")

        assertThat(users.events).containsExactly("begin", "find:7", "exists:새닉네임", "update:7:새닉네임", "commit")
        assertReturned()
    }

    @ParameterizedTest
    @MethodSource("commitFailures")
    fun `저장 뒤 커밋이 실패할 때__프록시로 요청하면__성공 없이 구조화된 문자셋 정보만 분류한다`(
        failure: RuntimeException,
        expectedStatus: Int,
        message: String,
    ) {
        // given
        transactions.commitFailure = failure
        val assembled = context!!
        val mvc = MockMvcBuilders.standaloneSetup(assembled.getBean(UserNicknameController::class.java))
            .setControllerAdvice(assembled.getBean(UserNicknameExceptionHandler::class.java))
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(7L, null, listOf())

        // when
        val failed = mvc.perform(
            put("/api/v1/users/me/nickname")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"새닉네임\"}"),
        )

        // then
        failed
            .andExpect(status().`is`(expectedStatus))
            .andExpect(
                content().json(
                    "{\"code\":" + expectedStatus + ",\"message\":\"" + message + "\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        assertThat(users.events).containsExactly("begin", "find:7", "exists:새닉네임", "update:7:새닉네임", "commit")
        assertReturned()
        users.events.clear()
        transactions.commitFailure = null

        val recovered = mvc.perform(
            put("/api/v1/users/me/nickname")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"새닉네임\"}"),
        )

        recovered
            .andExpect(status().isOk())
            .andExpect(
                content().json("{\"code\":200,\"message\":\"닉네임이 변경되었습니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        assertThat(users.events).containsExactly("begin", "find:7", "exists:새닉네임", "update:7:새닉네임", "commit")
        assertReturned()
    }

    private fun assertReturned() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse()
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty()
    }

    @Configuration(proxyBeanMethods = false)
    class RecordingInputs {
        @Bean
        @Primary
        fun recordingStore(): RecordingStore = RecordingStore()

        @Bean("writeJdbc")
        fun writeJdbc(): JdbcTemplate {
            val jdbc = mockk<JdbcTemplate>()
            // Spring runs this lifecycle callback when the bean is created; the test never uses the template.
            justRun { jdbc.afterPropertiesSet() }
            return jdbc
        }

        @Bean("writeTransactionManager")
        fun writeTransactionManager(users: RecordingStore): RecordingTransactionManager {
            return RecordingTransactionManager(users.events)
        }
    }

    class RecordingStore : UserNicknameStore {
        val events: MutableList<String> = ArrayList()
        var current: Optional<String> = Optional.of("기존닉네임")
        var duplicate: Boolean = false
        var updateFailure: RuntimeException? = null

        override fun findNickname(userId: Long): Optional<String> {
            inWriterTransaction()
            events.add("find:$userId")
            return current
        }

        override fun existsByNickname(nickname: String?): Boolean {
            inWriterTransaction()
            events.add("exists:$nickname")
            return duplicate
        }

        override fun updateNickname(userId: Long, nickname: String?) {
            inWriterTransaction()
            events.add("update:$userId:$nickname")
            val failure = updateFailure
            if (failure != null) {
                throw failure
            }
        }

        private fun inWriterTransaction() {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue()
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse()
            assertThat(TransactionSynchronizationManager.getCurrentTransactionName())
                .isEqualTo(ChangeUserNickname::class.java.name + ".change")
        }
    }

    class RecordingTransactionManager(private val events: MutableList<String>) : AbstractPlatformTransactionManager() {
        var startFailure: RuntimeException? = null
        var commitFailure: RuntimeException? = null

        override fun doGetTransaction(): Any = Any()

        override fun doBegin(transaction: Any, definition: TransactionDefinition) {
            assertThat(definition.isReadOnly).isFalse()
            assertThat(definition.timeout).isEqualTo(10)
            events.add("begin")
            val failure = startFailure
            if (failure != null) {
                throw failure
            }
        }

        override fun doCommit(status: DefaultTransactionStatus) {
            events.add("commit")
            val failure = commitFailure
            if (failure != null) {
                throw failure
            }
        }

        override fun doRollback(status: DefaultTransactionStatus) {
            events.add("rollback")
        }
    }

    companion object {
        @JvmStatic
        fun commitFailures(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(TransactionSystemException("synthetic-private-commit-message"), 500, "서버 에러!"),
                Arguments.of(
                    TransactionSystemException(
                        "synthetic-private-commit-message",
                        postgres("23514", "ck_rollback_charset_users"),
                    ),
                    400,
                    "일부 이모지 등 지원하지 않는 문자를 제외해 주세요.",
                ),
                Arguments.of(
                    TransactionSystemException(
                        "synthetic-private-commit-message",
                        postgres("23505", "uk_users_nickname_folded"),
                    ),
                    500,
                    "서버 에러!",
                ),
            )
        }

        private fun postgres(state: String, constraint: String): PSQLException {
            return PSQLException(
                ServerErrorMessage(
                    "SERROR\u0000C" + state + "\u0000spawbridge_user\u0000n" + constraint +
                        "\u0000Msynthetic-private-database-detail\u0000\u0000",
                ),
            )
        }
    }
}
