package com.pawbridge.backend.contract

import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * `GET /api/v1/notes/recipients/{member}` on the real application and PostgreSQL: who may receive a note, which
 * table decides each refusal, and that a read never writes a row.
 */
class NoteRecipientContractTest : NoteContractBase() {
    override val ids = 9_971_000L..9_971_999L

    @BeforeEach
    fun seedMembers() {
        seedUser(CALLER, CALLER_NAME)
        seedUser(OTHER, OTHER_NAME)
        seedMember(CALLER)
        seedMember(OTHER)
    }

    @Test
    fun `활성 회원일 때__수신자를 조회하면__네 필드만 no-store로 준다`() {
        // given
        val caller = token(CALLER)
        val other = token(OTHER)

        // when
        val toOther = withoutWrites { get("$PATH$OTHER", caller) }
        val toCaller = withoutWrites { get("$PATH$CALLER", other) }

        // then
        assertOk(toOther, mapOf("userId" to OTHER, "nickname" to OTHER_NAME, "active" to true, "deletionPending" to false))
        assertOk(toCaller, mapOf("userId" to CALLER, "nickname" to CALLER_NAME, "active" to true, "deletionPending" to false))
    }

    @Test
    fun `자기 자신일 때__수신자를 조회하면__보낼 수 없다고 403으로 거부한다`() {
        // given
        val caller = token(CALLER)

        // when
        val reply = withoutWrites { get("$PATH$CALLER", caller) }

        // then
        assertRejected(reply, 403, BLOCKED)
    }

    @ParameterizedTest(name = "{displayName}[{index}]")
    @ValueSource(strings = ["caller-blocks-other", "other-blocks-caller"])
    fun `어느 한쪽이 차단했을 때__수신자를 조회하면__방향과 관계없이 403이고 해제하면 다시 열린다`(direction: String) {
        // given
        val caller = token(CALLER)
        if (direction == "caller-blocks-other") seedBlock(CALLER, OTHER) else seedBlock(OTHER, CALLER)

        // when
        val blocked = withoutWrites { get("$PATH$OTHER", caller) }
        ContractPostgres.admin.update("DELETE FROM pawbridge_community.private_note_blocks WHERE member_id BETWEEN ? AND ?", ids.first, ids.last)
        val reopened = withoutWrites { get("$PATH$OTHER", caller) }

        // then
        assertRejected(blocked, 403, BLOCKED)
        assertThat(reopened.status).isEqualTo(200)
    }

    @Test
    fun `상대가 탈퇴 대기일 때__수신자를 조회하면__이용할 수 없는 회원으로 403이고 취소하면 다시 열린다`() {
        // given
        val caller = token(CALLER)
        seedPendingDeletion(OTHER)

        // when
        val pending = withoutWrites { get("$PATH$OTHER", caller) }
        ContractPostgres.admin.update("DELETE FROM pawbridge_user.contact_deletions WHERE user_id = ?", OTHER)
        val restored = withoutWrites { get("$PATH$OTHER", caller) }

        // then
        assertRejected(pending, 403, INACTIVE)
        assertThat(restored.status).isEqualTo(200)
    }

    @ParameterizedTest(name = "{displayName}[{index}]")
    @ValueSource(strings = ["0", "-1"])
    fun `양수가 아닌 id일 때__수신자를 조회하면__로그인이 필요하다고 401로 거부한다`(member: String) {
        // given
        val caller = token(CALLER)

        // when
        val reply = withoutWrites { get("$PATH$member", caller) }

        // then
        assertRejected(reply, 401, LOGIN)
    }

    @Test
    fun `회원 확인 원본을 읽을 수 없을 때__수신자를 조회하면__회원 503이고 복구하면 다시 열린다`() {
        // given
        val caller = token(CALLER)

        // when
        val failed = ContractPostgres.withRevoked("SELECT(user_id)", "pawbridge_user.contact_deletions") {
            withoutWrites { get("$PATH$CALLER", caller) }
        }
        val recovered = withoutWrites { get("$PATH$OTHER", caller) }

        // then
        assertRejected(failed, 503, MEMBER_UNAVAILABLE)
        assertThat(recovered.status).isEqualTo(200)
    }

    @Test
    fun `차단 원본을 읽을 수 없을 때__수신자를 조회하면__저장소 503이고 복구하면 다시 열린다`() {
        // given
        val caller = token(CALLER)

        // when
        val failed = ContractPostgres.withRevoked("SELECT(member_id)", "pawbridge_community.private_note_blocks") {
            withoutWrites { get("${PATH}0", caller) }
        }
        val recovered = withoutWrites { get("$PATH$OTHER", caller) }

        // then
        assertRejected(failed, 503, STORAGE_UNAVAILABLE)
        assertThat(recovered.status).isEqualTo(200)
    }

    @Test
    fun `토큰이 없거나 만료되었거나 위조되었을 때__수신자를 조회하면__401로 거부한다`() {
        // given
        val expired = token(CALLER, lifetime = Duration.ofSeconds(-60))

        // when
        val missing = withoutWrites { get("$PATH$OTHER", null) }
        val old = withoutWrites { get("$PATH$OTHER", expired) }
        val garbage = withoutWrites { get("$PATH$OTHER", "definitely-not-a-jwt") }

        // then
        assertSecurity(missing, 401, "인증 토큰이 필요합니다.")
        assertSecurity(old, 401, "유효하지 않거나 만료된 토큰입니다.")
        assertSecurity(garbage, 401, "유효하지 않거나 만료된 토큰입니다.")
    }

    @Test
    fun `id가 숫자가 아닐 때__수신자를 조회하면__요청 형식 400으로 거부한다`() {
        // given
        val caller = token(CALLER)

        // when
        val reply = withoutWrites { get("${PATH}not-a-number", caller) }

        // then
        assertRejected(reply, 400, INVALID)
    }

    @Test
    fun `이웃 경로와 쓰기 메서드일 때__호출하면__403으로 거부한다`() {
        // given
        val caller = token(CALLER)

        // when
        val extra = withoutWrites { get("$PATH$OTHER/extra", caller) }
        val write = withoutWrites { call("POST", "$PATH$OTHER", caller, "{}") }

        // then
        assertSecurity(extra, 403, "접근할 수 없습니다.")
        assertSecurity(write, 403, "접근할 수 없습니다.")
    }

    private companion object {
        const val CALLER = 9_971_001L
        const val OTHER = 9_971_002L
        const val CALLER_NAME = "F17보낸이"
        const val OTHER_NAME = "F17받는이"
        const val PATH = "/api/v1/notes/recipients/"
        const val BLOCKED = "이 상대에게 쪽지를 보낼 수 없습니다."
        const val INACTIVE = "이용할 수 없는 회원입니다."
        const val LOGIN = "로그인이 필요합니다."
        const val INVALID = "요청 형식과 로그인 정보를 확인해 주세요."
    }
}
