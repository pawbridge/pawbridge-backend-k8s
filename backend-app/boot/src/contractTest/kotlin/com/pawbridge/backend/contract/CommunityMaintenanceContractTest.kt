package com.pawbridge.backend.contract

import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteExpiry
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostSearchRebuild
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostWriter
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

/**
 * Community jobs that have no HTTP route: private note expiry and the search projection recovery.
 *
 * Both jobs are scheduled only when their feature flags are on, so the shared context has no bean of them. They are
 * built here from the application's own write pool and transaction template, which is how the configuration builds them.
 */
class CommunityMaintenanceContractTest : PostContractTestBase() {
    @Autowired
    @Qualifier("writeJdbc")
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    @Qualifier("writeTransaction")
    private lateinit var transaction: TransactionTemplate

    @Autowired
    private lateinit var writer: JdbcPostWriter

    @BeforeEach
    fun seedMembers() {
        seedUser(AUTHOR, "정리 작성자")
        seedUser(RECIPIENT, "정리 수신자")
        for (member in listOf(AUTHOR, RECIPIENT)) {
            execute("INSERT INTO pawbridge_community.private_note_members(member_id) VALUES (?)", member)
        }
    }

    @Test
    fun `만료된 쪽지와 오래된 빈 요청이 있을 때__만료를 처리하면__만료 쪽지는 사서함과 함께 지우고 나머지는 남긴다`() {
        // given
        val now = Instant.now()
        val expired = seedNote(now.minus(1, ChronoUnit.HOURS))
        val active = seedNote(now.plus(1, ChronoUnit.DAYS))
        seedRequest(UUID.randomUUID(), noteId = null, createdAt = now.minus(3, ChronoUnit.DAYS))
        val recentRequest = UUID.randomUUID()
        seedRequest(recentRequest, noteId = null, createdAt = now.minus(1, ChronoUnit.DAYS))
        val boundRequest = UUID.randomUUID()
        seedRequest(boundRequest, noteId = active, createdAt = now.minus(3, ChronoUnit.DAYS))
        seedRequest(UUID.randomUUID(), noteId = expired, createdAt = now.minus(3, ChronoUnit.DAYS))

        // when
        val removed = JdbcNoteExpiry(jdbc, transaction, Clock.fixed(now, Clock.systemUTC().zone)).expire()

        // then
        assertThat(removed).isEqualTo(1)
        assertThat(count("SELECT count(*) FROM pawbridge_community.private_notes WHERE note_id = ?::uuid", expired.toString())).isZero()
        assertThat(count("SELECT count(*) FROM pawbridge_community.private_note_mailboxes WHERE note_id = ?::uuid", expired.toString())).isZero()
        assertThat(count("SELECT count(*) FROM pawbridge_community.private_notes WHERE note_id = ?::uuid", active.toString())).isOne()
        assertThat(count("SELECT count(*) FROM pawbridge_community.private_note_mailboxes WHERE note_id = ?::uuid", active.toString())).isEqualTo(2)
        assertThat(
            ContractPostgres.admin.queryForList(
                "SELECT request_id::text FROM pawbridge_community.private_note_requests WHERE sender_id = ? ORDER BY created_at",
                String::class.java,
                AUTHOR,
            ).toSet(),
        ).`as`("Old requests without a note go, including the one whose expired note was just removed").hasSize(2)
        assertThat(count("SELECT count(*) FROM pawbridge_community.private_note_requests WHERE request_id = ?::uuid", recentRequest.toString())).isOne()
        assertThat(count("SELECT count(*) FROM pawbridge_community.private_note_requests WHERE request_id = ?::uuid", boundRequest.toString())).isOne()
    }

    @Test
    fun `만료된 쪽지가 한 번에 처리할 수를 넘을 때__만료를 처리하면__오래된 순서로 300건씩 지운다`() {
        // given
        val now = Instant.now()
        for (index in 0 until 301) {
            seedNote(now.minus(10, ChronoUnit.DAYS).plusSeconds(index.toLong()), mailboxes = false)
        }
        val newest = scalar("SELECT max(expires_at)::text FROM pawbridge_community.private_notes")
        val expiry = JdbcNoteExpiry(jdbc, transaction, Clock.fixed(now, Clock.systemUTC().zone))

        // when
        val first = expiry.expire()
        val remaining = scalar("SELECT expires_at::text FROM pawbridge_community.private_notes")
        val second = expiry.expire()

        // then
        assertThat(first).isEqualTo(300)
        assertThat(remaining).`as`("The newest expiry is the one left").isEqualTo(newest)
        assertThat(second).isOne()
        assertThat(count("SELECT count(*) FROM pawbridge_community.private_notes")).isZero()
    }

    @Test
    fun `문서가 없거나 낡은 글과 최신 글과 삭제된 글이 있을 때__검색 문서를 복구하면__필요한 글만 다시 만들고 개수를 돌려준다`() {
        // given
        val current = createPost(accessToken(AUTHOR), "복구 최신 글", "최신 본문").get("postId").asLong()
        val stale = createPost(accessToken(AUTHOR), "복구 낡은 글", "낡은 본문").get("postId").asLong()
        execute("UPDATE pawbridge_community.posts SET title = '복구 바뀐 제목' WHERE post_id = ?", stale)
        seedPost(POST_BASE + 1, AUTHOR, title = "복구 문서 없음", content = "문서 없는 본문")
        seedPost(POST_BASE + 2, AUTHOR, title = "복구 삭제됨", content = "삭제된 본문", deletedAt = "2025-02-01 00:00:00")
        val currentBefore = scalar("SELECT to_jsonb(d)::text FROM pawbridge_community.post_search_documents d WHERE post_id = ?", current)
        val rebuild = JdbcPostSearchRebuild(jdbc, transaction, writer)

        // when
        val rebuilt = rebuild.rebuildPage(100)
        val again = rebuild.rebuildPage(100)

        // then
        assertThat(rebuilt).isEqualTo(2)
        assertThat(again).`as`("Nothing is left to recover").isZero()
        assertThat(scalar("SELECT to_jsonb(d)::text FROM pawbridge_community.post_search_documents d WHERE post_id = ?", current))
            .isEqualTo(currentBefore)
        for (post in listOf(stale, POST_BASE + 1)) {
            assertThat(
                scalar(
                    "SELECT (d.title_hash = md5(p.title) AND d.content_hash = md5(p.content))::text FROM pawbridge_community.posts p " +
                        "JOIN pawbridge_community.post_search_documents d ON d.post_id = p.post_id WHERE p.post_id = ?",
                    post,
                ),
            ).`as`("Document of $post matches its post").isEqualTo("true")
        }
        assertThat(searchDocumentCount(POST_BASE + 2)).`as`("A deleted post gets no document").isZero()
    }

    @Test
    fun `복구할 글이 한 번에 처리할 수보다 많을 때__검색 문서를 복구하면__글 번호 순서로 한도만큼만 만든다`() {
        // given
        for (offset in 1..3) {
            seedPost(POST_BASE + offset, AUTHOR, title = "복구 순서 $offset", content = "본문 $offset")
        }
        val rebuild = JdbcPostSearchRebuild(jdbc, transaction, writer)

        // when
        val first = rebuild.rebuildPage(2)

        // then
        assertThat(first).isEqualTo(2)
        assertThat(searchDocumentCount(POST_BASE + 1)).isOne()
        assertThat(searchDocumentCount(POST_BASE + 2)).isOne()
        assertThat(searchDocumentCount(POST_BASE + 3)).isZero()
        assertThat(rebuild.rebuildPage(2)).isOne()
        assertThatThrownBy { rebuild.rebuildPage(0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { rebuild.rebuildPage(101) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun seedNote(expiresAt: Instant, mailboxes: Boolean = true): UUID {
        val note = UUID.randomUUID()
        execute(
            "INSERT INTO pawbridge_community.private_notes(note_id, sender_id, recipient_id, body, created_at, expires_at) " +
                "VALUES (?, ?, ?, '정리 본문', ?::timestamptz, ?::timestamptz)",
            note, AUTHOR, RECIPIENT, expiresAt.minus(30, ChronoUnit.DAYS).toString(), expiresAt.toString(),
        )
        if (mailboxes) {
            execute(
                "INSERT INTO pawbridge_community.private_note_mailboxes(member_id, note_id, direction, created_at) " +
                    "VALUES (?, ?, 'SENT', now()), (?, ?, 'INBOX', now())",
                AUTHOR, note, RECIPIENT, note,
            )
        }
        return note
    }

    private fun seedRequest(requestId: UUID, noteId: UUID?, createdAt: Instant) {
        execute(
            "INSERT INTO pawbridge_community.private_note_requests(sender_id, request_id, request_hash, note_id, created_at) " +
                "VALUES (?, ?, ?, ?, ?::timestamptz)",
            AUTHOR, requestId, "h".repeat(64), noteId, createdAt.toString(),
        )
    }

    private companion object {
        const val AUTHOR = 9_911_001L
        const val RECIPIENT = 9_911_002L
        const val POST_BASE = 9_911_100L
    }
}
