package com.pawbridge.backend.contract

import java.time.Duration
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * The two block reads of the note feature on the real application and PostgreSQL:
 * `GET /api/v1/notes/blocks/status` (which of these authors did I block) and `GET /api/v1/notes/blocks` (my block
 * list, paged). Each refusal is tied to the table that causes it, and no read writes a row.
 */
class NoteBlockReadContractTest : NoteContractBase() {
    override val ids = 9_972_000L..9_974_999L

    // ---- GET /api/v1/notes/blocks/status ------------------------------------------------------------------

    private fun seedStatus() {
        seedUser(STATUS_CALLER, "F18표시회원")
        for (member in listOf(STATUS_CALLER, STATUS_FIRST, STATUS_SECOND, STATUS_REVERSE)) seedMember(member)
        seedBlock(STATUS_CALLER, STATUS_FIRST)
        seedBlock(STATUS_CALLER, STATUS_SECOND)
        seedBlock(STATUS_REVERSE, STATUS_CALLER)
    }

    @Test
    fun `내가 차단한 작성자가 섞여 있을 때__상태를 조회하면__중복 없이 오름차순 id만 준다`() {
        // given
        seedStatus()
        val caller = token(STATUS_CALLER)
        val query = listOf(STATUS_SECOND, STATUS_FIRST, STATUS_REVERSE, STATUS_CALLER, STATUS_FIRST).joinToString(",")

        // when
        val reply = withoutWrites { get("$STATUS?memberIds=$query", caller) }

        // then
        assertOk(reply, mapOf("memberIds" to listOf(STATUS_FIRST, STATUS_SECOND)))
    }

    @Test
    fun `역방향 차단만 있을 때__상태를 조회하면__빈 목록을 준다`() {
        // given
        seedStatus()
        val caller = token(STATUS_CALLER)

        // when
        val reply = withoutWrites { get("$STATUS?memberIds=$STATUS_REVERSE", caller) }

        // then
        assertOk(reply, mapOf("memberIds" to emptyList<Long>()))
    }

    @Test
    fun `파라미터가 없을 때__상태를 조회하면__누락 400으로 거부한다`() {
        // given
        seedStatus()
        val caller = token(STATUS_CALLER)

        // when
        val reply = withoutWrites { get(STATUS, caller) }

        // then
        // The binding error keeps the shared envelope, unlike the note controllers' own message-only refusals.
        assertBusiness(
            reply,
            400,
            obj("code" to 400, "message" to "필수 요청 파라미터 'memberIds'(이)가 누락되었습니다.", "data" to null),
        )
    }

    @Test
    fun `값이 비었거나 양수가 아닐 때__상태를 조회하면__범위 400으로 거부한다`() {
        // given
        seedStatus()
        val caller = token(STATUS_CALLER)

        // when
        val empty = withoutWrites { get("$STATUS?memberIds=", caller) }
        val zero = withoutWrites { get("$STATUS?memberIds=0", caller) }

        // then
        assertRejected(empty, 400, INVALID_MEMBERS)
        assertRejected(zero, 400, INVALID_MEMBERS)
    }

    @Test
    fun `쉼표 목록과 반복 파라미터가 섞였을 때__상태를 조회하면__요청 형식 400으로 거부한다`() {
        // given
        seedStatus()
        val caller = token(STATUS_CALLER)

        // when
        val reply = withoutWrites {
            get("$STATUS?memberIds=$STATUS_FIRST,$STATUS_SECOND&memberIds=$STATUS_REVERSE", caller)
        }

        // then
        assertRejected(reply, 400, INVALID_BINDING)
    }

    @Test
    fun `회원 확인 원본을 읽을 수 없을 때__상태를 조회하면__잘못된 입력은 먼저 400이고 올바른 입력은 회원 503이다`() {
        // given
        seedStatus()
        val caller = token(STATUS_CALLER)

        // when
        val (invalid, failed) = ContractPostgres.withRevoked("SELECT(user_id)", "pawbridge_user.contact_deletions") {
            withoutWrites { get("$STATUS?memberIds=0", caller) } to withoutWrites { get("$STATUS?memberIds=$STATUS_FIRST", caller) }
        }
        val recovered = withoutWrites { get("$STATUS?memberIds=$STATUS_FIRST", caller) }

        // then
        assertRejected(invalid, 400, INVALID_MEMBERS)
        assertRejected(failed, 503, MEMBER_UNAVAILABLE)
        assertOk(recovered, mapOf("memberIds" to listOf(STATUS_FIRST)))
    }

    @Test
    fun `차단 원본을 읽을 수 없을 때__상태를 조회하면__저장소 503이고 복구하면 다시 읽는다`() {
        // given
        seedStatus()
        val caller = token(STATUS_CALLER)

        // when
        val failed = ContractPostgres.withRevoked("SELECT(member_id)", "pawbridge_community.private_note_blocks") {
            withoutWrites { get("$STATUS?memberIds=$STATUS_FIRST", caller) }
        }
        val recovered = withoutWrites { get("$STATUS?memberIds=$STATUS_FIRST", caller) }

        // then
        assertRejected(failed, 503, STORAGE_UNAVAILABLE)
        assertOk(recovered, mapOf("memberIds" to listOf(STATUS_FIRST)))
    }

    @Test
    fun `토큰이 없거나 만료되었거나 쓰기 메서드일 때__상태를 호출하면__401과 403으로 거부한다`() {
        // given
        val caller = token(STATUS_CALLER)
        val expired = token(STATUS_CALLER, lifetime = Duration.ofSeconds(-60))

        // when
        val missing = withoutWrites { get("$STATUS?memberIds=not-a-number", null) }
        val old = withoutWrites { get("$STATUS?memberIds=$STATUS_FIRST", expired) }
        val write = withoutWrites { call("POST", STATUS, caller, "{}") }

        // then
        assertSecurity(missing, 401, "인증 토큰이 필요합니다.")
        assertSecurity(old, 401, "유효하지 않거나 만료된 토큰입니다.")
        assertSecurity(write, 403, "접근할 수 없습니다.")
    }

    // ---- GET /api/v1/notes/blocks -------------------------------------------------------------------------

    private fun seedList() {
        // The first target has a blank nickname that must come back untouched; the pending one counts as withdrawn.
        seedUser(LIST_CALLER, "F20caller")
        seedUser(LIST_FIRST, "  ")
        seedUser(LIST_NORMAL, "F20normal")
        seedUser(LIST_PENDING, "F20pending")
        seedPendingDeletion(LIST_PENDING)
        for (member in listOf(LIST_CALLER) + LIST_TARGETS + LIST_REVERSE) seedMember(member)
        for (target in LIST_TARGETS) seedBlock(LIST_CALLER, target)
        seedBlock(LIST_REVERSE, LIST_CALLER)
    }

    private fun display(member: Long): String = when (member) {
        LIST_FIRST -> "  "
        LIST_NORMAL -> "F20normal"
        else -> WITHDRAWN
    }

    private fun expectedPage(number: Int): Map<String, Any?> {
        val selected = LIST_TARGETS.sortedDescending().drop(number * 10).take(10)
        return mapOf(
            "content" to selected.map { mapOf("memberId" to it, "nickname" to display(it), "createdAt" to STORED_TIME) },
            "totalElements" to 12,
            "totalPages" to 2,
            "number" to number,
        )
    }

    @Tag("timezone-sensitive")
    @ParameterizedTest(name = "{displayName}[{index}]")
    @ValueSource(ints = [0, 1, 2])
    fun `열두 명을 차단했을 때__목록 페이지를 조회하면__표시명과 시각과 순서를 원본대로 준다`(page: Int) {
        // given
        seedList()
        val caller = token(LIST_CALLER)

        // when
        val reply = withoutWrites { get("$LIST?page=$page", caller) }

        // then
        assertOk(reply, expectedPage(page))
    }

    @Test
    fun `차단 시각을 읽을 수 없을 때__목록을 조회하면__저장소 503이고 복구하면 다시 읽는다`() {
        // given
        seedList()
        val caller = token(LIST_CALLER)

        // when
        val failed = ContractPostgres.withRevoked("SELECT(created_at)", "pawbridge_community.private_note_blocks") {
            withoutWrites { get(LIST, caller) }
        }
        val recovered = withoutWrites { get(LIST, caller) }

        // then
        assertRejected(failed, 503, STORAGE_UNAVAILABLE)
        assertOk(recovered, expectedPage(0))
    }

    @Test
    fun `회원 별명을 읽을 수 없을 때__목록을 조회하면__잘못된 페이지는 먼저 400이고 올바른 페이지는 회원 503이다`() {
        // given
        seedList()
        val caller = token(LIST_CALLER)

        // when
        val (invalid, failed) = ContractPostgres.withRevoked("SELECT(nickname)", "pawbridge_user.users") {
            withoutWrites { get("$LIST?page=-1", caller) } to withoutWrites { get(LIST, caller) }
        }
        val recovered = withoutWrites { get("$LIST?page=1", caller) }

        // then
        assertRejected(invalid, 400, "페이지 범위를 확인해 주세요.")
        assertRejected(failed, 503, MEMBER_UNAVAILABLE)
        assertOk(recovered, expectedPage(1))
    }

    @Test
    fun `토큰이 없거나 쓰기 메서드일 때__목록을 호출하면__401과 403으로 거부한다`() {
        // given
        val caller = token(LIST_CALLER)

        // when
        val missing = withoutWrites { get(LIST, null) }
        val write = withoutWrites { call("POST", LIST, caller, "{}") }

        // then
        assertSecurity(missing, 401, "인증 토큰이 필요합니다.")
        assertSecurity(write, 403, "접근할 수 없습니다.")
    }

    private companion object {
        const val STATUS = "/api/v1/notes/blocks/status"
        const val LIST = "/api/v1/notes/blocks"
        const val INVALID_MEMBERS = "작성자 식별자는 1~100개의 양수여야 합니다."
        const val INVALID_BINDING = "요청 형식과 로그인 정보를 확인해 주세요."

        const val STATUS_CALLER = 9_972_001L
        const val STATUS_FIRST = 9_972_002L
        const val STATUS_SECOND = 9_972_003L
        const val STATUS_REVERSE = 9_972_004L

        const val LIST_CALLER = 9_974_001L
        const val LIST_FIRST = 9_974_002L
        const val LIST_NORMAL = 9_974_003L
        const val LIST_PENDING = 9_974_004L
        const val LIST_REVERSE = 9_974_014L
        val LIST_TARGETS: List<Long> = (LIST_FIRST until LIST_REVERSE).toList()
    }
}
