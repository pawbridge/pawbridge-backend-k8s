package com.pawbridge.backend.contract

import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.Metadata
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.Order
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.Publication
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.Save
import com.pawbridge.backend.community.internal.homevideo.infrastructure.JdbcHomeVideoManagement
import com.pawbridge.backend.community.internal.homevideo.infrastructure.JdbcHomeVideoReader
import com.pawbridge.backend.community.internal.homevideo.infrastructure.JdbcHomeVideoRepository
import com.pawbridge.backend.community.internal.homevideo.infrastructure.YouTubeVideoClient
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException

/**
 * The home video repositories called directly: the public visible-list query and the administrator board
 * (revision lock, selection, publication, order and metadata expiry). YouTube is a mock; the database is real.
 */
class HomeVideoRepositoryContractTest : ApiContractTestBase() {
    @Autowired
    @Qualifier("writeJdbc")
    private lateinit var write: JdbcTemplate

    @Autowired
    @Qualifier("writeTransaction")
    private lateinit var writeTx: TransactionTemplate

    @Autowired
    @Qualifier("readJdbc")
    private lateinit var read: JdbcTemplate

    @Autowired
    @Qualifier("readOnlyTransaction")
    private lateinit var readTx: TransactionTemplate

    private val now: Instant = Instant.parse("2026-03-10T00:00:00Z")
    private val youtube = mockk<YouTubeVideoClient>()
    private lateinit var management: JdbcHomeVideoManagement

    @BeforeEach
    fun build() {
        execute("UPDATE pawbridge_community.home_video_board SET revision = 7")
        management = JdbcHomeVideoManagement(JdbcHomeVideoRepository(write), youtube, Clock.fixed(now, ZoneOffset.UTC), writeTx)
    }

    // ---- public list -------------------------------------------------------------------------------------

    @Test
    fun `게시되고 사용 가능하고 최근 확인한 영상들일 때__공개 목록을 읽으면__위치순 앞의 셋만 모든 필드로 준다`() {
        // given
        seedVideo(0, published = true, position = 3, available = true, checkedAt = now.minusSeconds(60))
        seedVideo(1, published = true, position = 1, available = true, checkedAt = now.minusSeconds(60))
        seedVideo(2, published = true, position = 2, available = true, checkedAt = now.minusSeconds(60))
        seedVideo(3, published = true, position = 0, available = true, checkedAt = now.minusSeconds(60))
        seedVideo(4, published = false, position = -1, available = true, checkedAt = now.minusSeconds(60))
        seedVideo(5, published = true, position = -2, available = false, checkedAt = now.minusSeconds(60))
        seedVideo(6, published = true, position = -3, available = true, checkedAt = now.minus(31, ChronoUnit.DAYS))
        seedVideo(7, published = true, position = -4, available = true, checkedAt = null)

        // when
        val shown = JdbcHomeVideoReader(read, readTx).read(now.minus(30, ChronoUnit.DAYS))

        // then
        assertThat(shown.map { it.id.toString() }).isEqualTo(listOf(3, 1, 2).map { id(it) })
        assertThat(shown[0].videoId).isEqualTo(videoId(3))
        assertThat(shown[0].title).isEqualTo("제목 3")
        assertThat(shown[0].durationSeconds).isEqualTo(100L)
        assertThat(shown[0].checkedAt).isEqualTo(now.minusSeconds(60))
        assertThat(shown[0].createdAt).isEqualTo(Instant.parse("2026-03-01T00:00:00Z"))
    }

    // ---- board -------------------------------------------------------------------------------------------

    @Test
    fun `게시된 영상과 숨긴 영상이 섞여 있을 때__보드를 읽으면__현재 리비전과 위치순 전체 영상을 준다`() {
        // given
        seedBoard()

        // when
        val board = management.board()

        // then
        assertThat(board.revision).isEqualTo(7)
        assertThat(board.videos.map { it.id.toString() }).isEqualTo((0..3).map { id(it) })
        assertThat(board.videos.map { it.published }).isEqualTo(listOf(true, true, false, true))
    }

    @Test
    fun `게시된 영상 전체의 새 순서일 때__순서를 바꾸면__게시 영상이 앞서고 숨긴 영상은 뒤에 두며 리비전이 오른다`() {
        // given
        seedBoard()
        val ids = listOf(3, 0, 1).map { UUID.fromString(id(it)) }

        // when
        val board = management.reorder(Order(ids, 7))

        // then
        assertThat(positions()).isEqualTo(listOf(id(3) to 0, id(0) to 1, id(1) to 2, id(2) to 3))
        assertThat(board.revision).isEqualTo(8)
        assertThat(board.videos.map { it.id.toString() }).isEqualTo(listOf(id(3), id(0), id(1), id(2)))
    }

    @Test
    fun `순서가 불완전하거나 중복이거나 숨긴 영상을 담았거나 낡은 리비전일 때__순서를 바꾸면__409이고 위치와 리비전은 그대로다`() {
        // given
        seedBoard()
        val before = positions()
        fun ids(vararg indexes: Int) = indexes.map { UUID.fromString(id(it)) }

        // when
        // then
        for ((name, order) in mapOf(
            "missing one" to Order(ids(0, 1), 7),
            "duplicate" to Order(ids(0, 0, 1), 7),
            "hidden included" to Order(ids(0, 1, 2), 7),
            "null" to Order(null, 7),
            "stale revision" to Order(ids(0, 1, 3), 6),
        )) {
            assertThatThrownBy { management.reorder(order) }.`as`(name).isStatus(HttpStatus.CONFLICT)
        }
        assertThat(positions()).isEqualTo(before)
        assertThat(revision()).isEqualTo(7)
    }

    @Test
    fun `게시된 영상일 때__숨기면__영상 제공자 없이 게시만 끄고 리비전이 오르며 낡은 리비전과 없는 영상은 거부된다`() {
        // given
        seedBoard()

        // when
        assertThatThrownBy { management.publish(UUID.fromString(id(0)), Publication(false, 6)) }.isStatus(HttpStatus.CONFLICT)
        assertThatThrownBy { management.publish(UUID.fromString(id(9)), Publication(false, 7)) }.isStatus(HttpStatus.NOT_FOUND)
        management.publish(UUID.fromString(id(0)), Publication(false, 7))

        // then
        assertThat(published(0)).isEqualTo("false")
        assertThat(published(1)).isEqualTo("true")
        assertThat(revision()).isEqualTo(8)
    }

    @Test
    fun `숨긴 영상이고 제공자가 사용 가능이라 답할 때__게시하면__게시되고 메타데이터를 새로 쓰며 세 개가 이미 게시되었으면 409이다`() {
        // given
        seedBoard()
        every { youtube.lookup(listOf(videoId(2))) } returns mapOf(videoId(2) to Metadata(videoId(2), "새 제목", "새 채널", "https://fixture.invalid/new", 321L, true))

        // when
        assertThatThrownBy { management.publish(UUID.fromString(id(2)), Publication(true, 7)) }.isStatus(HttpStatus.CONFLICT)
        management.publish(UUID.fromString(id(1)), Publication(false, 7))
        management.publish(UUID.fromString(id(2)), Publication(true, 8))

        // then
        assertThat(published(2)).isEqualTo("true")
        assertThat(metadata(id(2))).isEqualTo("새 제목|새 채널|321|t|2026-03-10 00:00:00+00")
        assertThat(revision()).isEqualTo(9)
    }

    @Test
    fun `새 영상 주소일 때__저장하면__다음 위치에 삽입하고 메타데이터를 쓰며 이미 등록되었거나 낡은 리비전이거나 사용할 수 없으면 거부한다`() {
        // given
        seedBoard()
        val newId = "NEWVIDEO123"
        every { youtube.lookup(listOf(newId)) } returns mapOf(newId to Metadata(newId, "새 영상", "채널", "https://fixture.invalid/t", 50L, true))
        every { youtube.lookup(listOf("BADVIDEO123")) } returns mapOf("BADVIDEO123" to Metadata("BADVIDEO123", null, null, null, 0L, false))
        every { youtube.lookup(listOf(videoId(0))) } returns mapOf(videoId(0) to Metadata(videoId(0), "같은 영상", "채널", "t", 1L, true))

        // when
        assertThatThrownBy { management.save(null, Save("https://youtu.be/$newId", false, 6)) }.isStatus(HttpStatus.CONFLICT)
        assertThatThrownBy { management.save(null, Save("https://youtu.be/${videoId(0)}", false, 7)) }.isStatus(HttpStatus.CONFLICT)
        assertThatThrownBy { management.save(null, Save("https://youtu.be/BADVIDEO123", true, 7)) }.isStatus(HttpStatus.BAD_REQUEST)
        assertThatThrownBy { management.save(null, Save("https://youtu.be/$newId", true, 7)) }.`as`("three are already published").isStatus(HttpStatus.CONFLICT)
        val board = management.save(null, Save("https://youtu.be/$newId", false, 7))

        // then
        assertThat(board.revision).isEqualTo(8)
        assertThat(count("SELECT count(*) FROM pawbridge_community.home_videos")).isEqualTo(5)
        assertThat(scalar("SELECT position || ':' || published || ':' || title FROM pawbridge_community.home_videos WHERE video_id = ?", newId))
            .isEqualTo("9:false:새 영상")
    }

    @Test
    fun `다시 확인을 요청할 때__영상 제공자가 영상이 없다고 답하면__선택과 게시는 두고 메타데이터를 지운다`() {
        // given
        seedBoard()
        every { youtube.lookup(listOf(videoId(0))) } returns emptyMap()

        // when
        management.recheck(UUID.fromString(id(0)), 7)

        // then
        assertThat(metadata(id(0))).isEqualTo("null|null||f|2026-03-10 00:00:00+00")
        assertThat(published(0)).isEqualTo("true")
        assertThat(revision()).isEqualTo(8)
    }

    @Test
    fun `확인 시각이 29일을 넘었거나 하루가 지난 영상이 있을 때__새로 고치면__만료분을 먼저 지우고 오래된 영상만 제공자에 물어 갱신한다`() {
        // given
        seedBoard()
        execute("UPDATE pawbridge_community.home_videos SET checked_at = ?::timestamptz WHERE id = ?::uuid", now.minus(40, ChronoUnit.DAYS).toString(), id(0))
        execute("UPDATE pawbridge_community.home_videos SET checked_at = ?::timestamptz WHERE id = ?::uuid", now.minus(10, ChronoUnit.HOURS).toString(), id(1))
        execute("UPDATE pawbridge_community.home_videos SET checked_at = ?::timestamptz WHERE id = ?::uuid", now.minus(2, ChronoUnit.DAYS).toString(), id(2))
        execute("UPDATE pawbridge_community.home_videos SET checked_at = ?::timestamptz WHERE id = ?::uuid", now.minus(28, ChronoUnit.DAYS).toString(), id(3))
        every { youtube.lookup(any()) } answers {
            firstArg<List<String>>().associateWith { Metadata(it, "갱신 $it", "갱신 채널", "t", 9L, true) }
        }

        // when
        management.refresh()

        // then
        assertThat(metadata(id(0))).`as`("expired then refreshed").startsWith("갱신 ${videoId(0)}|")
        assertThat(metadata(id(1))).`as`("checked 10 hours ago is left").isEqualTo("제목 1|채널 1|100|t|" + scalar("SELECT checked_at::text FROM pawbridge_community.home_videos WHERE id = ?::uuid", id(1)))
        assertThat(metadata(id(2))).startsWith("갱신 ${videoId(2)}|")
        assertThat(metadata(id(3))).startsWith("갱신 ${videoId(3)}|")
        assertThat(published(0)).`as`("Selection and publication survive the expiry").isEqualTo("true")
    }

    @Test
    fun `다른 관리자가 보드를 잠그고 바꾸는 중일 때__순서를 바꾸면__잠금이 풀릴 때까지 기다리고 낡은 리비전으로 거부된다`() {
        // given
        seedBoard()
        val before = positions()
        val executor = Executors.newSingleThreadExecutor()
        val order = Order(listOf(3, 0, 1).map { UUID.fromString(id(it)) }, 7)

        // when
        val outcome = ContractPostgres.adminConnection { other ->
            other.autoCommit = false
            other.createStatement().use { it.execute("SELECT revision FROM pawbridge_community.home_video_board WHERE id = 1 FOR UPDATE") }
            val pending = executor.submit<Throwable?> {
                try {
                    management.reorder(order)
                    null
                } catch (failure: Throwable) {
                    failure
                }
            }
            Thread.sleep(800)
            val waiting = !pending.isDone
            other.createStatement().use { it.execute("UPDATE pawbridge_community.home_video_board SET revision = revision + 1") }
            other.commit()
            waiting to pending.get(10, TimeUnit.SECONDS)
        }
        executor.shutdown()

        // then
        assertThat(outcome.first).`as`("The call waits for the board lock").isTrue()
        assertThat(outcome.second).isInstanceOfSatisfying(ResponseStatusException::class.java) { assertThat(it.statusCode).isEqualTo(HttpStatus.CONFLICT) }
        assertThat(positions()).isEqualTo(before)
    }

    private fun org.assertj.core.api.AbstractThrowableAssert<*, *>.isStatus(status: HttpStatus) {
        this.isInstanceOfSatisfying(ResponseStatusException::class.java) { assertThat(it.statusCode).isEqualTo(status) }
    }

    private fun id(index: Int): String = "f1ee0000-0000-0000-0000-" + index.toString().padStart(12, '0')

    private fun videoId(index: Int): String = "Vid%08d".format(index)

    private fun revision(): Long = count("SELECT revision FROM pawbridge_community.home_video_board WHERE id = 1")

    private fun published(index: Int): String? = scalar("SELECT published::text FROM pawbridge_community.home_videos WHERE id = ?::uuid", id(index))

    private fun positions(): List<Pair<String, Int>> = ContractPostgres.admin.query(
        "SELECT id::text, position FROM pawbridge_community.home_videos ORDER BY position",
    ) { rows, _ -> rows.getString(1) to rows.getInt(2) }

    private fun metadata(id: String): String = scalar(
        "SELECT concat_ws('|', coalesce(title,'null'), coalesce(channel_title,'null'), coalesce(duration_seconds::text,''), " +
            "available, checked_at) FROM pawbridge_community.home_videos WHERE id = ?::uuid",
        id,
    )!!

    private fun seedBoard() {
        seedVideo(0, published = true, position = 5, available = true, checkedAt = now)
        seedVideo(1, published = true, position = 6, available = true, checkedAt = now)
        seedVideo(2, published = false, position = 7, available = true, checkedAt = now)
        seedVideo(3, published = true, position = 8, available = true, checkedAt = now)
    }

    private fun seedVideo(index: Int, published: Boolean, position: Int, available: Boolean, checkedAt: Instant?) {
        execute(
            "INSERT INTO pawbridge_community.home_videos(id,video_id,published,position,created_at,title,channel_title," +
                "thumbnail_url,duration_seconds,available,checked_at) VALUES (?::uuid,?,?,?,?::timestamptz,?,?,?,?,?,?::timestamptz)",
            id(index), videoId(index), published, position, "2026-03-01T00:00:00Z", "제목 $index", "채널 $index",
            "https://fixture.invalid/$index", 100L, available, checkedAt?.toString(),
        )
    }
}
