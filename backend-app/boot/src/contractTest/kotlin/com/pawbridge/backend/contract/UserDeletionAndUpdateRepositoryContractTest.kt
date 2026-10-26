package com.pawbridge.backend.contract

import com.pawbridge.backend.user.internal.adminupdate.application.AdminUserUpdate.Changes
import com.pawbridge.backend.user.internal.adminupdate.application.AdminUserUpdate.Role
import com.pawbridge.backend.user.internal.adminupdate.application.ShelterApprovalRequiredException
import com.pawbridge.backend.user.internal.adminupdate.infrastructure.JdbcAdminUserUpdate
import com.pawbridge.backend.user.internal.deletion.application.MemberCleanupUnavailableException
import com.pawbridge.backend.user.internal.deletion.infrastructure.JdbcUserDeletion
import com.pawbridge.backend.user.internal.nickname.application.NicknameDuplicateException
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import com.pawbridge.backend.user.internal.session.application.UserSessionRevoker
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore
import java.time.LocalDateTime
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.LongConsumer
import java.util.function.Supplier
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.OptimisticLockingFailureException

/**
 * The member deletion and administrator update repositories called directly. Deletion is a pending marker, the
 * community cleanup and the account removal in separate transactions; the update locks the row, applies the allowed
 * changes and revokes sessions on a demotion in the same transaction.
 */
class UserDeletionAndUpdateRepositoryContractTest : UserRepositoryContractBase() {
    @Autowired
    private lateinit var sessions: JdbcAuthSessionStore

    private val modified: LocalDateTime = LocalDateTime.of(2026, 3, 5, 10, 11, 12, 345_678_000)

    // ---- Deletion ---------------------------------------------------------------------------------------------

    @Test
    fun `즐겨찾기와 신청과 세션이 있는 회원일 때__삭제하면__대기 표식과 세션 폐기를 먼저 커밋하고 정리 뒤 계정과 딸린 행을 지운다`() {
        // given
        insertUser(MEMBER)
        insertUser(BYSTANDER)
        execute("INSERT INTO pawbridge_user.favorites(user_id,animal_id,created_at) VALUES (?,?,now()),(?,?,now())", MEMBER, 1, BYSTANDER, 1)
        execute("INSERT INTO pawbridge_user.shelter_applications(user_id,shelter_name,status,requested_at) VALUES (?,'보호소','PENDING',now()),(?,'보호소','PENDING',now())", MEMBER, BYSTANDER)
        insertSession(MEMBER, hash('a'))
        insertSession(BYSTANDER, hash('b'))
        val seenByCleanup = ArrayList<String>()
        val deletion = JdbcUserDeletion(write, writeTransaction(), LongConsumer { userId ->
            seenByCleanup.add("target=$userId")
            seenByCleanup.add("marker=" + count("SELECT count(*) FROM pawbridge_user.contact_deletions WHERE user_id = ?", userId))
            seenByCleanup.add("account=" + count("SELECT count(*) FROM pawbridge_user.users WHERE user_id = ?", userId))
            seenByCleanup.add("active-sessions=" + sessionCount(userId, active = true))
        }, sessions)

        // when
        deletion.delete(MEMBER)

        // then
        assertThat(seenByCleanup).containsExactly("target=$MEMBER", "marker=1", "account=1", "active-sessions=0")
        assertThat(count("SELECT count(*) FROM pawbridge_user.users WHERE user_id = ?", MEMBER)).isZero()
        assertThat(count("SELECT count(*) FROM pawbridge_user.contact_deletions WHERE user_id = ?", MEMBER)).isZero()
        assertThat(count("SELECT count(*) FROM pawbridge_user.favorites WHERE user_id = ?", MEMBER)).isZero()
        assertThat(count("SELECT count(*) FROM pawbridge_user.shelter_applications WHERE user_id = ?", MEMBER)).isZero()
        assertThat(sessionCount(MEMBER)).isZero()
        assertThat(count("SELECT count(*) FROM pawbridge_user.users WHERE user_id = ?", BYSTANDER)).isOne()
        assertThat(count("SELECT count(*) FROM pawbridge_user.favorites WHERE user_id = ?", BYSTANDER)).isOne()
        assertThat(count("SELECT count(*) FROM pawbridge_user.shelter_applications WHERE user_id = ?", BYSTANDER)).isOne()
        assertThat(sessionCount(BYSTANDER, active = true)).isOne()
    }

    @Test
    fun `없는 회원일 때__삭제하면__회원 없음 예외이고 정리도 표식도 남기지 않는다`() {
        // given
        insertUser(BYSTANDER)
        var cleanups = 0
        val deletion = JdbcUserDeletion(write, writeTransaction(), LongConsumer { cleanups++ }, sessions)
        val before = ContractPostgres.snapshot()

        // when
        // then
        assertThatThrownBy { deletion.delete(MEMBER) }.isInstanceOf(UserNotFoundException::class.java)
        assertThatThrownBy { deletion.delete(-5) }.isInstanceOf(UserNotFoundException::class.java)
        assertThat(cleanups).isZero()
        assertThat(ContractPostgres.snapshot() == before).isTrue()
    }

    @Test
    fun `정리가 실패했을 때__삭제하면__서비스 불가 예외이고 표식과 세션 폐기는 남으며 재시도로 끝난다`() {
        // given
        insertUser(MEMBER)
        insertSession(MEMBER, hash('c'))
        var attempts = 0
        val deletion = JdbcUserDeletion(write, writeTransaction(), LongConsumer {
            attempts++
            if (attempts == 1) {
                throw IllegalStateException("community cleanup down")
            }
        }, sessions)

        // when
        // then
        assertThatThrownBy { deletion.delete(MEMBER) }.isInstanceOf(MemberCleanupUnavailableException::class.java)
        assertThat(count("SELECT count(*) FROM pawbridge_user.contact_deletions WHERE user_id = ?", MEMBER)).isOne()
        assertThat(count("SELECT count(*) FROM pawbridge_user.users WHERE user_id = ?", MEMBER)).isOne()
        assertThat(sessionCount(MEMBER, active = true)).isZero()
        deletion.delete(MEMBER)
        assertThat(attempts).isEqualTo(2)
        assertThat(count("SELECT count(*) FROM pawbridge_user.users WHERE user_id = ?", MEMBER)).isZero()
        assertThat(count("SELECT count(*) FROM pawbridge_user.contact_deletions WHERE user_id = ?", MEMBER)).isZero()
    }

    @Test
    fun `정리 중에 계정이 이미 사라졌을 때__삭제하면__마지막 삭제 없이 성공한다`() {
        // given
        insertUser(MEMBER)
        val deletion = JdbcUserDeletion(write, writeTransaction(), LongConsumer { userId ->
            execute("DELETE FROM pawbridge_user.users WHERE user_id = ?", userId)
        }, sessions)

        // when
        deletion.delete(MEMBER)

        // then
        assertThat(count("SELECT count(*) FROM pawbridge_user.users WHERE user_id = ?", MEMBER)).isZero()
    }

    @Test
    fun `최종 계정 삭제가 행을 지우지 못할 때__삭제하면__낡은 상태로 보고 마지막 단계를 롤백한다`() {
        // given
        insertUser(MEMBER)
        execute("INSERT INTO pawbridge_user.favorites(user_id,animal_id,created_at) VALUES (?,?,now())", MEMBER, 9)
        val deletion = JdbcUserDeletion(write, writeTransaction(), LongConsumer { }, sessions)

        // when
        // then
        withSkippedRows("DELETE", MEMBER) {
            assertThatThrownBy { deletion.delete(MEMBER) }.isInstanceOf(OptimisticLockingFailureException::class.java)
        }
        assertThat(count("SELECT count(*) FROM pawbridge_user.users WHERE user_id = ?", MEMBER)).isOne()
        assertThat(count("SELECT count(*) FROM pawbridge_user.favorites WHERE user_id = ?", MEMBER)).`as`("Rolled back with the final step").isOne()
        assertThat(count("SELECT count(*) FROM pawbridge_user.contact_deletions WHERE user_id = ?", MEMBER)).`as`("The marker stays for a retry").isOne()
    }

    @Test
    fun `회원 행이 다른 연결에 잠겨 있을 때__삭제하면__대기 표식과 정리도 잠금이 풀릴 때까지 시작하지 않는다`() {
        // given
        insertUser(MEMBER)
        val cleanups = AtomicInteger()
        val deletion = JdbcUserDeletion(write, writeTransaction(), LongConsumer { cleanups.incrementAndGet() }, sessions)

        // when
        assertWaitsForUserRowLock(MEMBER, whileBlocked = { assertThat(cleanups.get()).isZero() }, action = Callable { deletion.delete(MEMBER) })

        // then
        assertThat(cleanups.get()).isOne()
        assertThat(count("SELECT count(*) FROM pawbridge_user.users WHERE user_id = ?", MEMBER)).isZero()
    }

    // ---- Administrator update ---------------------------------------------------------------------------------

    @Test
    @Tag("timezone-sensitive")
    fun `다른 회원이 쓰지 않는 닉네임일 때__닉네임만 바꾸면__닉네임과 수정 시각만 바뀌고 세션은 그대로다`() {
        // given
        insertUser(MEMBER, nickname = "OldNick", role = "ROLE_USER")
        insertSession(MEMBER, hash('d'))
        val before = userRow(MEMBER)

        // when
        updater().update(MEMBER, Changes("NewNick", null, null))

        // then
        val after = userRow(MEMBER)
        assertThat(after.get("nickname").asText()).isEqualTo("NewNick")
        assertThat(after.get("updated_at").asText()).isEqualTo(storedWallClock(modified))
        assertThat(omitting(after, "nickname", "updated_at")).isEqualTo(omitting(before, "nickname", "updated_at"))
        assertThat(sessionCount(MEMBER, active = true)).isOne()
    }

    @Test
    fun `역할을 올리거나 내릴 때__수정하면__올림은 세션을 두고 내림만 그 회원의 세션을 모두 폐기한다`() {
        // given
        insertUser(MEMBER, role = "ROLE_USER")
        insertUser(BYSTANDER, role = "ROLE_ADMIN", nickname = "다른관리자")
        insertSession(MEMBER, hash('e'))
        insertSession(BYSTANDER, hash('f'))

        // when
        updater().update(MEMBER, Changes(null, Role.ROLE_ADMIN, null))
        val afterPromotion = sessionCount(MEMBER, active = true)
        updater().update(MEMBER, Changes(null, Role.ROLE_USER, null))

        // then
        assertThat(afterPromotion).isOne()
        assertThat(userRow(MEMBER).get("role").asText()).isEqualTo("ROLE_USER")
        assertThat(sessionCount(MEMBER, active = true)).isZero()
        assertThat(sessionCount(BYSTANDER, active = true)).isOne()
    }

    @Test
    fun `세션 폐기가 실패할 때__강등하면__역할 변경도 함께 롤백한다`() {
        // given
        insertUser(MEMBER, role = "ROLE_ADMIN")
        val failing = JdbcAdminUserUpdate(write, writeTransaction(), Supplier { modified }, UserSessionRevoker { throw IllegalStateException("revoke down") })

        // when
        // then
        assertThatThrownBy { failing.update(MEMBER, Changes(null, Role.ROLE_USER, null)) }.isInstanceOf(IllegalStateException::class.java)
        assertThat(userRow(MEMBER).get("role").asText()).isEqualTo("ROLE_ADMIN")
    }

    @Test
    fun `보호소 승격이거나 다른 등록번호일 때__수정하면__승인 필요로 거부하고 같은 값이나 빈 값은 변경 없이 성공한다`() {
        // given
        insertUser(MEMBER, role = "ROLE_USER", nickname = "Plain")
        insertUser(BYSTANDER, role = "ROLE_SHELTER", careRegNo = "CARE-OWN", nickname = "Shelterer")
        val member = userRow(MEMBER)
        val shelter = userRow(BYSTANDER)

        // when
        // then
        assertThatThrownBy { updater().update(MEMBER, Changes(null, Role.ROLE_SHELTER, null)) }
            .isInstanceOf(ShelterApprovalRequiredException::class.java)
        assertThatThrownBy { updater().update(MEMBER, Changes(null, null, "CARE-NEW")) }
            .isInstanceOf(ShelterApprovalRequiredException::class.java)
        assertThatThrownBy { updater().update(BYSTANDER, Changes(null, null, "CARE-OTHER")) }
            .isInstanceOf(ShelterApprovalRequiredException::class.java)
        updater().update(BYSTANDER, Changes("  ", Role.ROLE_SHELTER, "CARE-OWN"))
        updater().update(BYSTANDER, Changes(null, null, "   "))
        updater().update(MEMBER, Changes("Plain", Role.ROLE_USER, ""))
        assertThat(userRow(MEMBER)).isEqualTo(member)
        assertThat(userRow(BYSTANDER)).isEqualTo(shelter)
    }

    @Test
    fun `다른 회원이 쓰는 닉네임이거나 대소문자만 바꿀 때__수정하면__중복으로 거부하고 아무것도 바뀌지 않는다`() {
        // given
        insertUser(MEMBER, nickname = "Mine")
        insertUser(BYSTANDER, nickname = "Taken")
        val before = ContractPostgres.snapshot()

        // when
        // then
        assertThatThrownBy { updater().update(MEMBER, Changes("taken", null, null)) }.isInstanceOf(NicknameDuplicateException::class.java)
        assertThatThrownBy { updater().update(MEMBER, Changes("MINE", null, null)) }
            .`as`("The member's own spelling counts as taken, as in the source")
            .isInstanceOf(NicknameDuplicateException::class.java)
        assertThat(ContractPostgres.snapshot() == before).isTrue()
    }

    @Test
    fun `없는 회원일 때__수정하면__회원 없음 예외이고 양수가 아닌 아이디도 같다`() {
        // given
        insertUser(BYSTANDER)
        val before = ContractPostgres.snapshot()

        // when
        // then
        assertThatThrownBy { updater().update(MEMBER, Changes("Nick", null, null)) }.isInstanceOf(UserNotFoundException::class.java)
        assertThatThrownBy { updater().update(0, Changes("Nick", null, null)) }.isInstanceOf(UserNotFoundException::class.java)
        assertThat(ContractPostgres.snapshot() == before).isTrue()
    }

    @Test
    fun `쓰기가 한 행을 바꾸지 못할 때__수정하면__성공으로 보고하지 않고 롤백한다`() {
        // given
        insertUser(MEMBER, nickname = "Stays", role = "ROLE_ADMIN")
        insertSession(MEMBER, hash('9'))

        // when
        // then
        withSkippedRows("UPDATE", MEMBER) {
            assertThatThrownBy { updater().update(MEMBER, Changes("Changed", Role.ROLE_USER, null)) }
                .isInstanceOf(IllegalStateException::class.java)
        }
        assertThat(userRow(MEMBER).get("nickname").asText()).isEqualTo("Stays")
        assertThat(userRow(MEMBER).get("role").asText()).isEqualTo("ROLE_ADMIN")
        assertThat(sessionCount(MEMBER, active = true)).`as`("Sessions are only revoked after a successful write").isOne()
    }

    @Test
    fun `회원 행이 다른 연결에 잠겨 있을 때__수정하면__잠금이 풀릴 때까지 기다린 뒤 적용한다`() {
        // given
        // The role column is in no unique index, so the write itself would not conflict with the lock held below.
        insertUser(MEMBER, nickname = "Waiting", role = "ROLE_USER")

        // when
        assertWaitsForUserRowLock(MEMBER, action = Callable { updater().update(MEMBER, Changes(null, Role.ROLE_ADMIN, null)) })

        // then
        assertThat(userRow(MEMBER).get("role").asText()).isEqualTo("ROLE_ADMIN")
    }

    private fun updater() = JdbcAdminUserUpdate(write, writeTransaction(), Supplier { modified }, sessions)

    /** Makes PostgreSQL skip the statement for one user row: zero rows affected and no error. */
    private fun <T> withSkippedRows(operation: String, userId: Long, body: () -> T): T =
        withTrigger(operation, "users", "OLD.user_id = $userId", "RETURN NULL", body)

    private companion object {
        const val MEMBER = 9_961_001L
        const val BYSTANDER = 9_961_002L
    }
}
