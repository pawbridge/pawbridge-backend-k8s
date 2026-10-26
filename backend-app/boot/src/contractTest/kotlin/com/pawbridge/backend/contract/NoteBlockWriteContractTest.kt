package com.pawbridge.backend.contract

import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The two block writes on the real application and PostgreSQL: `DELETE /api/v1/notes/blocks/{member}` (unblock) and
 * `PUT /api/v1/notes/blocks/{member}` (block). Every test names the rows it expects to change and proves that nothing
 * else did, including when a missing privilege makes the transaction roll back.
 */
class NoteBlockWriteContractTest : NoteContractBase() {
    override val ids = 9_978_000L..9_979_999L

    private fun blocks(): List<Pair<Long, Long>> {
        return ContractPostgres.admin.queryForList(
            "SELECT member_id, blocked_id FROM pawbridge_community.private_note_blocks " +
                "WHERE member_id BETWEEN ? AND ? OR blocked_id BETWEEN ? AND ? ORDER BY 1, 2",
            ids.first, ids.last, ids.first, ids.last,
        ).map { (it["member_id"] as Long) to (it["blocked_id"] as Long) }
    }

    private fun members(): List<Long> {
        return ContractPostgres.admin.queryForList(
            "SELECT member_id FROM pawbridge_community.private_note_members WHERE member_id BETWEEN ? AND ? ORDER BY 1",
            Long::class.javaObjectType, ids.first, ids.last,
        )
    }

    private fun blockPage(vararg members: Pair<Long, String>): Map<String, Any?> = mapOf(
        "content" to members.map { (member, name) -> mapOf("memberId" to member, "nickname" to name, "createdAt" to CREATED) },
        "totalElements" to members.size,
        "totalPages" to members.size,
        "number" to 0,
    )

    // ---- DELETE /api/v1/notes/blocks/{member} ------------------------------------------------------------

    private fun seedUnblock() {
        seedUser(UNBLOCK_CALLER, "F24caller")
        seedUser(UNBLOCK_OTHER, UNBLOCK_NICKNAME)
        seedMember(UNBLOCK_CALLER)
        seedMember(UNBLOCK_OTHER)
        seedBlock(UNBLOCK_CALLER, UNBLOCK_OTHER, CREATED)
        seedBlock(UNBLOCK_OTHER, UNBLOCK_CALLER, CREATED)
    }

    @Tag("timezone-sensitive")
    @Test
    fun `서로 차단했을 때__내가 해제하면__내가 건 한 행만 지우고 반대 방향은 남긴다`() {
        // given
        seedUnblock()
        val caller = token(UNBLOCK_CALLER)
        val before = withoutWrites { get(BLOCKS, caller) }

        // when
        val (reply, changed) = observingChanges { call("DELETE", "$BLOCKS/$UNBLOCK_OTHER", caller) }
        val after = withoutWrites { get(BLOCKS, caller) }

        // then
        assertOk(before, blockPage(UNBLOCK_OTHER to UNBLOCK_NICKNAME))
        assertEmptyOk(reply)
        assertThat(changed).containsExactly("pawbridge_community.private_note_blocks")
        assertThat(blocks()).containsExactly(UNBLOCK_OTHER to UNBLOCK_CALLER)
        assertOk(after, blockPage())
    }

    @Test
    fun `이미 차단하지 않은 상대일 때__다시 해제하면__200이고 아무 행도 바꾸지 않는다`() {
        // given
        seedUnblock()
        val caller = token(UNBLOCK_CALLER)
        call("DELETE", "$BLOCKS/$UNBLOCK_OTHER", caller)

        // when
        val reply = withoutWrites { call("DELETE", "$BLOCKS/$UNBLOCK_OTHER", caller) }

        // then
        assertEmptyOk(reply)
        assertThat(blocks()).containsExactly(UNBLOCK_OTHER to UNBLOCK_CALLER)
    }

    @Test
    fun `아직 멤버가 아닌 대상일 때__해제하면__그 멤버 행 하나만 만들고 나머지는 그대로다`() {
        // given
        seedUnblock()
        val caller = token(UNBLOCK_CALLER)
        assertThat(hasMember(UNBLOCK_MISSING)).isFalse()

        // when
        val (reply, changed) = observingChanges { call("DELETE", "$BLOCKS/$UNBLOCK_MISSING", caller) }

        // then
        assertEmptyOk(reply)
        assertThat(changed).containsExactly("pawbridge_community.private_note_members")
        assertThat(members()).containsExactly(UNBLOCK_CALLER, UNBLOCK_OTHER, UNBLOCK_MISSING)
        assertThat(blocks()).containsExactly(UNBLOCK_CALLER to UNBLOCK_OTHER, UNBLOCK_OTHER to UNBLOCK_CALLER)
    }

    @Test
    fun `삭제 권한이 없을 때__해제하면__저장소 503이고 아무 행도 바꾸지 않으며 복구하면 지운다`() {
        // given
        seedUnblock()
        val caller = token(UNBLOCK_CALLER)

        // when
        val failed = ContractPostgres.withRevoked("DELETE", "pawbridge_community.private_note_blocks") {
            withoutWrites { call("DELETE", "$BLOCKS/$UNBLOCK_OTHER", caller) }
        }
        val blocksAfterFailure = blocks()
        val recovered = call("DELETE", "$BLOCKS/$UNBLOCK_OTHER", caller)

        // then
        assertRejected(failed, 503, STORAGE_UNAVAILABLE)
        assertThat(blocksAfterFailure).containsExactly(UNBLOCK_CALLER to UNBLOCK_OTHER, UNBLOCK_OTHER to UNBLOCK_CALLER)
        assertEmptyOk(recovered)
        assertThat(blocks()).containsExactly(UNBLOCK_OTHER to UNBLOCK_CALLER)
    }

    @Test
    fun `멤버 잠금 권한이 없을 때__아직 멤버가 아닌 대상을 해제하면__저장소 503이고 멤버를 만들지 않는다`() {
        // given
        seedUnblock()
        val caller = token(UNBLOCK_CALLER)

        // when
        val failed = ContractPostgres.withRevoked("INSERT(member_id)", "pawbridge_community.private_note_members") {
            withoutWrites { call("DELETE", "$BLOCKS/$UNBLOCK_MISSING", caller) }
        }

        // then
        assertRejected(failed, 503, STORAGE_UNAVAILABLE)
        assertThat(hasMember(UNBLOCK_MISSING)).isFalse()
    }

    @Test
    fun `회원 별명을 읽을 수 없을 때__해제하면__회원 503이고 아무 행도 바꾸지 않으며 복구하면 지운다`() {
        // given
        seedUnblock()
        val caller = token(UNBLOCK_CALLER)

        // when
        val failed = ContractPostgres.withRevoked("SELECT(nickname)", "pawbridge_user.users") {
            withoutWrites { call("DELETE", "$BLOCKS/$UNBLOCK_OTHER", caller) }
        }
        val recovered = call("DELETE", "$BLOCKS/$UNBLOCK_OTHER", caller)

        // then
        assertRejected(failed, 503, MEMBER_UNAVAILABLE)
        assertEmptyOk(recovered)
        assertThat(blocks()).containsExactly(UNBLOCK_OTHER to UNBLOCK_CALLER)
    }

    @Test
    fun `토큰이 없거나 이웃 경로일 때__해제를 호출하면__401과 403으로 거부하고 차단은 그대로다`() {
        // given
        seedUnblock()
        val caller = token(UNBLOCK_CALLER)

        // when
        val missing = withoutWrites { call("DELETE", "$BLOCKS/9223372036854775808", null) }
        val stream = withoutWrites { get("/api/v1/notes/stream/extra", caller) }
        val extra = withoutWrites { call("PUT", "$BLOCKS/$UNBLOCK_OTHER/extra", caller) }

        // then
        assertSecurity(missing, 401, "인증 토큰이 필요합니다.")
        assertSecurity(stream, 403, "접근할 수 없습니다.")
        assertSecurity(extra, 403, "접근할 수 없습니다.")
        assertThat(blocks()).hasSize(2)
    }

    // ---- PUT /api/v1/notes/blocks/{member} ---------------------------------------------------------------

    private fun seedBlocker() {
        seedUser(BLOCK_CALLER, "F25caller")
        seedUser(BLOCK_OTHER, BLOCK_NICKNAME)
    }

    private fun storedBlockTime(): Instant {
        return ContractPostgres.admin.queryForObject(
            "SELECT created_at FROM pawbridge_community.private_note_blocks WHERE member_id = ? AND blocked_id = ?",
            java.sql.Timestamp::class.java, BLOCK_CALLER, BLOCK_OTHER,
        )!!.toInstant()
    }

    @Tag("timezone-sensitive")
    @Test
    fun `멤버가 아직 없을 때__차단하면__차단 한 행과 두 멤버 행만 만들고 시각은 요청 시각이다`() {
        // given
        seedBlocker()
        val caller = token(BLOCK_CALLER)
        val started = Instant.now()

        // when
        val (reply, changed) = observingChanges { call("PUT", "$BLOCKS/$BLOCK_OTHER", caller) }
        val finished = Instant.now()

        // then
        assertEmptyOk(reply)
        assertThat(changed).containsExactlyInAnyOrder(
            "pawbridge_community.private_note_blocks",
            "pawbridge_community.private_note_members",
        )
        assertThat(blocks()).containsExactly(BLOCK_CALLER to BLOCK_OTHER)
        assertThat(members()).containsExactly(BLOCK_CALLER, BLOCK_OTHER)
        assertThat(storedBlockTime()).isBetween(started.minusSeconds(1), finished.plusSeconds(1))
    }

    @Tag("timezone-sensitive")
    @Test
    fun `차단했을 때__다른 조회를 하면__목록과 상태와 수신자 조회가 같은 사실을 보여준다`() {
        // given
        seedBlocker()
        val caller = token(BLOCK_CALLER)
        call("PUT", "$BLOCKS/$BLOCK_OTHER", caller)

        // when
        val page = withoutWrites { get(BLOCKS, caller) }
        val status = withoutWrites { get("$BLOCKS/status?memberIds=$BLOCK_OTHER", caller) }
        val recipient = withoutWrites { get("/api/v1/notes/recipients/$BLOCK_OTHER", caller) }
        call("DELETE", "$BLOCKS/$BLOCK_OTHER", caller)
        val statusAfter = withoutWrites { get("$BLOCKS/status?memberIds=$BLOCK_OTHER", caller) }
        val recipientAfter = withoutWrites { get("/api/v1/notes/recipients/$BLOCK_OTHER", caller) }

        // then
        assertThat(page.status).isEqualTo(200)
        val item = page.json.get("data").get("content").get(0)
        assertThat(item.get("memberId").asLong()).isEqualTo(BLOCK_OTHER)
        assertThat(item.get("nickname").asText()).isEqualTo(BLOCK_NICKNAME)
        assertThat(item.get("createdAt").asText()).endsWith("Z")
        assertThat(page.json.get("data").get("totalElements").asInt()).isEqualTo(1)
        assertOk(status, mapOf("memberIds" to listOf(BLOCK_OTHER)))
        assertRejected(recipient, 403, "이 상대에게 쪽지를 보낼 수 없습니다.")
        assertOk(statusAfter, mapOf("memberIds" to emptyList<Long>()))
        assertOk(
            recipientAfter,
            mapOf("userId" to BLOCK_OTHER, "nickname" to BLOCK_NICKNAME, "active" to true, "deletionPending" to false),
        )
    }

    @Tag("timezone-sensitive")
    @Test
    fun `이미 차단했을 때__다시 차단하면__200이고 처음 차단 시각을 그대로 둔다`() {
        // given
        seedBlocker()
        val caller = token(BLOCK_CALLER)
        call("PUT", "$BLOCKS/$BLOCK_OTHER", caller)
        val first = storedBlockTime()
        val firstPage = get(BLOCKS, caller)

        // when
        val duplicate = withoutWrites { call("PUT", "$BLOCKS/$BLOCK_OTHER", caller) }
        val secondPage = get(BLOCKS, caller)

        // then
        assertEmptyOk(duplicate)
        assertThat(storedBlockTime()).isEqualTo(first)
        assertThat(secondPage.json).isEqualTo(firstPage.json)
        assertThat(Instant.parse(secondPage.json.get("data").get("content").get(0).get("createdAt").asText()))
            .isEqualTo(first)
    }

    @Test
    fun `차단 쓰기 권한이 없을 때__차단하면__저장소 503이고 멤버 행도 남기지 않으며 복구하면 차단한다`() {
        // given
        seedBlocker()
        val caller = token(BLOCK_CALLER)

        // when
        val failed = ContractPostgres.withRevoked(
            "INSERT(member_id, blocked_id, created_at)",
            "pawbridge_community.private_note_blocks",
        ) {
            withoutWrites { call("PUT", "$BLOCKS/$BLOCK_OTHER", caller) }
        }
        val afterFailure = blocks() to members()
        val recovered = call("PUT", "$BLOCKS/$BLOCK_OTHER", caller)

        // then
        assertRejected(failed, 503, STORAGE_UNAVAILABLE)
        assertThat(afterFailure.first).isEmpty()
        assertThat(afterFailure.second).isEmpty()
        assertEmptyOk(recovered)
        assertThat(blocks()).containsExactly(BLOCK_CALLER to BLOCK_OTHER)
    }

    @Test
    fun `회원 별명을 읽을 수 없을 때__차단하면__회원 503이고 아무 행도 바꾸지 않으며 복구하면 차단한다`() {
        // given
        seedBlocker()
        val caller = token(BLOCK_CALLER)

        // when
        val failed = ContractPostgres.withRevoked("SELECT(nickname)", "pawbridge_user.users") {
            withoutWrites { call("PUT", "$BLOCKS/$BLOCK_OTHER", caller) }
        }
        val recovered = call("PUT", "$BLOCKS/$BLOCK_OTHER", caller)

        // then
        assertRejected(failed, 503, MEMBER_UNAVAILABLE)
        assertEmptyOk(recovered)
        assertThat(blocks()).containsExactly(BLOCK_CALLER to BLOCK_OTHER)
    }

    @Test
    fun `토큰이 없거나 만료되었거나 이웃 경로일 때__차단을 호출하면__401과 403으로 거부한다`() {
        // given
        seedBlocker()
        val caller = token(BLOCK_CALLER)
        val expired = token(BLOCK_CALLER, lifetime = Duration.ofSeconds(-60))

        // when
        val missing = withoutWrites { call("PUT", "$BLOCKS/9223372036854775808", null) }
        val old = withoutWrites { call("PUT", "$BLOCKS/$BLOCK_OTHER", expired) }
        val stream = withoutWrites { get("/api/v1/notes/stream/extra", caller) }
        val extra = withoutWrites { call("PUT", "$BLOCKS/$BLOCK_OTHER/extra", caller) }

        // then
        assertSecurity(missing, 401, "인증 토큰이 필요합니다.")
        assertSecurity(old, 401, "유효하지 않거나 만료된 토큰입니다.")
        assertSecurity(stream, 403, "접근할 수 없습니다.")
        assertSecurity(extra, 403, "접근할 수 없습니다.")
        assertThat(blocks()).isEmpty()
    }

    private companion object {
        const val BLOCKS = "/api/v1/notes/blocks"
        const val CREATED = "2031-01-01T00:00:00.123456Z"

        const val UNBLOCK_CALLER = 9_978_001L
        const val UNBLOCK_OTHER = 9_978_002L
        const val UNBLOCK_MISSING = 9_978_003L
        const val UNBLOCK_NICKNAME = " 原文 F24nickname "

        const val BLOCK_CALLER = 9_979_001L
        const val BLOCK_OTHER = 9_979_002L
        const val BLOCK_NICKNAME = " 原文 F25nickname "
    }
}
