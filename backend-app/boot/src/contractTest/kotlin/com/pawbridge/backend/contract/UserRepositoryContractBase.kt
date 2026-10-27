package com.pawbridge.backend.contract

import com.fasterxml.jackson.databind.JsonNode
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Shared pieces of the user repository contracts: the application's writer JDBC, transaction templates on its
 * transaction manager, and small helpers to seed and read `pawbridge_user` rows through the superuser connection.
 */
abstract class UserRepositoryContractBase : ApiContractTestBase() {
    @Autowired
    @Qualifier("writeJdbc")
    protected lateinit var write: JdbcTemplate

    @Autowired
    @Qualifier("writeTransactionManager")
    protected lateinit var manager: PlatformTransactionManager

    protected fun writeTransaction(): TransactionTemplate = TransactionTemplate(manager)

    protected fun readOnlyTransaction(): TransactionTemplate {
        val template = TransactionTemplate(manager)
        template.isReadOnly = true
        return template
    }

    protected fun insertUser(
        userId: Long,
        nickname: String = "회원$userId",
        email: String = "repo-$userId@fixture.invalid",
        name: String = "이름$userId",
        provider: String = "LOCAL",
        providerId: String? = null,
        password: String? = "ENCODED_$userId",
        role: String = "ROLE_USER",
        careRegNo: String? = null,
        createdAt: String? = "2025-01-01 01:02:03.123456",
    ) {
        execute(
            "INSERT INTO pawbridge_user.users(user_id,nickname,email,name,provider,provider_id,password,role,care_reg_no," +
                "created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?::timestamp,'1997-02-03 04:05:06.654321'::timestamp)",
            userId, nickname, email, name, provider, providerId, password, role, careRegNo, createdAt,
        )
    }

    protected fun userRow(userId: Long): JsonNode = row("pawbridge_user.users", "user_id", userId)

    protected fun userCount(): Long = count("SELECT count(*) FROM pawbridge_user.users")

    protected fun sessionCount(userId: Long, active: Boolean? = null): Long {
        val filter = when (active) {
            null -> ""
            true -> " AND revoked_at IS NULL"
            false -> " AND revoked_at IS NOT NULL"
        }
        return count("SELECT count(*) FROM pawbridge_user.auth_sessions WHERE user_id = ?$filter", userId)
    }

    protected fun insertSession(userId: Long, tokenHash: String) {
        execute(
            "INSERT INTO pawbridge_user.auth_sessions(user_id,token_hash,created_at,last_used_at,expires_at,absolute_expires_at) " +
                "VALUES(?,?,now(),now(),now() + interval '1 hour',now() + interval '1 day')",
            userId, tokenHash,
        )
    }

    /** The wall clock the repositories store for a local time: they bind it as an instant of the JVM zone, in UTC. */
    protected fun storedWallClock(time: LocalDateTime): String {
        val utc = time.atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()
        return DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(utc)
    }

    protected fun hash(character: Char): String = character.toString().repeat(64)

    /**
     * Holds `FOR KEY SHARE` on the user row from another connection, runs [action] on its own thread, and proves that
     * the action does not finish until the lock is released. Only an explicit `FOR UPDATE` in the action conflicts with
     * that lock: a plain `UPDATE` of other columns or an insert that references the user does not, so the wait cannot
     * come from the writes that follow the lock. [whileBlocked] runs while the action is still waiting.
     */
    protected fun <T> assertWaitsForUserRowLock(userId: Long, whileBlocked: () -> Unit = {}, action: Callable<T>): T {
        val executor = Executors.newSingleThreadExecutor()
        try {
            return ContractPostgres.adminConnection { connection ->
                connection.autoCommit = false
                connection.createStatement().use {
                    it.execute("SELECT user_id FROM pawbridge_user.users WHERE user_id = $userId FOR KEY SHARE")
                }
                val pending = executor.submit(action)
                assertThatThrownBy { pending.get(700, TimeUnit.MILLISECONDS) }.isInstanceOf(TimeoutException::class.java)
                whileBlocked()
                connection.commit()
                pending.get(10, TimeUnit.SECONDS)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    /** The counterpart of [assertWaitsForUserRowLock]: the action reads the row and finishes while it is locked. */
    protected fun <T> assertIgnoresUserRowLock(userId: Long, action: Callable<T>): T {
        val executor = Executors.newSingleThreadExecutor()
        try {
            return ContractPostgres.adminConnection { connection ->
                connection.autoCommit = false
                connection.createStatement().use {
                    it.execute("SELECT user_id FROM pawbridge_user.users WHERE user_id = $userId FOR UPDATE")
                }
                val result = executor.submit(action).get(5, TimeUnit.SECONDS)
                connection.commit()
                assertThat(result).isNotNull()
                result
            }
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * Installs a row trigger on a `pawbridge_user` table for the duration of [action]. [body] is the PL/pgSQL body:
     * `RETURN NULL` skips the row silently (zero rows affected), `RAISE ...` makes the statement fail with that error.
     * The trigger is only for reproducing database outcomes that cannot be forced with plain data.
     */
    protected fun <T> withTrigger(event: String, table: String, condition: String, body: String, action: () -> T): T {
        ContractPostgres.admin.execute(
            "CREATE FUNCTION pawbridge_user.contract_trigger() RETURNS trigger LANGUAGE plpgsql AS \$contract\$ BEGIN $body; END \$contract\$",
        )
        ContractPostgres.admin.execute(
            "CREATE TRIGGER contract_trigger BEFORE $event ON pawbridge_user.$table FOR EACH ROW " +
                "WHEN ($condition) EXECUTE FUNCTION pawbridge_user.contract_trigger()",
        )
        try {
            return action()
        } finally {
            ContractPostgres.admin.execute("DROP TRIGGER contract_trigger ON pawbridge_user.$table")
            ContractPostgres.admin.execute("DROP FUNCTION pawbridge_user.contract_trigger()")
        }
    }
}
