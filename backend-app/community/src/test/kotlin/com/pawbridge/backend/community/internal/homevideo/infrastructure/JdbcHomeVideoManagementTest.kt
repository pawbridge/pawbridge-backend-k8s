package com.pawbridge.backend.community.internal.homevideo.infrastructure

import com.pawbridge.backend.community.internal.homevideo.application.HomeVideo
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.Board
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.Metadata
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.Order
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.Publication
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.Save
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

class JdbcHomeVideoManagementTest {

    @Test
    fun `기존 보드가 있을 때__읽으면__잠그고 스냅숏을 소스 기본 트랜잭션 하나로 읽는다`() {
        // given
        val f = Fixture()

        every { f.repo.lockBoard() } returns 5L
        every { f.repo.revision() } returns 5L
        every { f.repo.list() } returns listOf()

        // when
        val result = f.service.board()

        // then
        assertThat(result).isEqualTo(Board(5, listOf()))
        verifyOrder {
            f.repo.lockBoard()
            f.repo.revision()
            f.repo.list()
        }
        assertThat(f.events).containsExactly("begin", "commit")
        assertThat(f.manager.timeout).isEqualTo(-1)
        confirmVerified(f.youtube)
    }

    @Test
    fun `새 재생 가능한 영상일 때__저장하면__트랜잭션 전에 조회하고 삽입 메타데이터 리비전을 한 번씩 처리한다`() {
        // given
        val f = Fixture()

        every { f.clock.instant() } returns NOW
        every { f.youtube.lookup(any()) } answers {
            f.events.add("lookup")
            mapOf(VIDEO to METADATA)
        }
        every { f.repo.lockBoard() } returns 5L
        every { f.repo.revision() } returns 5L
        every { f.repo.list() } returns listOf()
        justRun { f.repo.insert(any(), any(), any(), any(), any()) }
        justRun { f.repo.metadata(any(), any(), any()) }
        justRun { f.repo.changed() }

        // when
        f.service.save(null, Save(URL, true, 5))

        // then
        assertThat(f.events).containsExactly("lookup", "begin", "commit")
        val insertedId = slot<UUID>()
        val metadataId = slot<UUID>()
        verifyOrder {
            f.repo.lockBoard()
            f.repo.list()
            f.repo.insert(capture(insertedId), VIDEO, true, 0, NOW)
            f.repo.metadata(capture(metadataId), METADATA, NOW)
            f.repo.changed()
            f.repo.revision()
            f.repo.list()
        }
        assertThat(metadataId.captured).isEqualTo(insertedId.captured)
        verify(exactly = 2) { f.clock.instant() }
    }

    @ParameterizedTest
    @ValueSource(strings = ["revision", "duplicate", "capacity", "slots", "unavailable"])
    fun `충돌하거나 재생할 수 없는 선택일 때__저장하면__변경 전에 소스의 거부를 그대로 유지한다`(
        mode: String,
    ) {
        // given
        val f = Fixture()
        val rows = ArrayList<HomeVideo>()
        when (mode) {
            "duplicate" -> rows.add(video(OTHER, VIDEO, false, NOW))
            "capacity" -> {
                for (i in 0 until 100) {
                    rows.add(video(UUID(0, (i + 1).toLong()), "other$i", false, NOW))
                }
            }
            "slots" -> {
                rows.add(video(ID, "one", true, NOW))
                rows.add(video(OTHER, "two", true, NOW))
                rows.add(video(UUID(0, 3), "three", true, NOW))
            }
        }
        val metadata = if (mode == "unavailable") {
            Metadata(VIDEO, "title", "channel", null, 60, false)
        } else {
            METADATA
        }

        every { f.repo.lockBoard() } returns if (mode == "revision") 6L else 5L
        every { f.repo.list() } returns rows
        every { f.youtube.lookup(any()) } answers {
            f.events.add("lookup")
            mapOf(VIDEO to metadata)
        }

        // when
        val thrown = assertThatThrownBy { f.service.save(null, Save(URL, true, 5)) }

        // then
        thrown.isInstanceOfSatisfying(ResponseStatusException::class.java) { e ->
            assertThat(e.statusCode.value()).isEqualTo(if (mode == "unavailable") 400 else 409)
        }
        verify(exactly = 0) { f.repo.insert(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { f.repo.changed() }
        assertThat(f.events).containsExactlyElementsOf(
            if (mode == "unavailable") listOf("lookup") else listOf("lookup", "begin", "rollback"),
        )
    }

    @Test
    fun `게시된 영상이고 공급자를 쓸 수 없을 때__숨기면__조회하지 않고 메타데이터도 바꾸지 않는다`() {
        // given
        val f = Fixture()

        every { f.repo.lockBoard() } returns 5L
        every { f.repo.revision() } returns 5L
        every { f.repo.list() } returns listOf(video(ID, VIDEO, true, NOW))
        justRun { f.repo.publication(any(), any()) }
        justRun { f.repo.changed() }

        // when
        f.service.publish(ID, Publication(false, 5))

        // then
        confirmVerified(f.youtube)
        verify(exactly = 1) { f.repo.publication(ID, false) }
        verify(exactly = 0) { f.repo.metadata(any(), any(), any()) }
        assertThat(f.events).containsExactly("begin", "commit")
    }

    @ParameterizedTest
    @ValueSource(strings = ["publish", "recheck"])
    fun `외부 조회 중 선택이 바뀌었을 때__게시하거나 다시 확인하면__잠금 뒤 낡은 메타데이터를 거부한다`(
        operation: String,
    ) {
        // given
        val f = Fixture()

        every { f.repo.lockBoard() } returns 5L
        every { f.repo.list() } returnsMany listOf(
            listOf(video(ID, VIDEO, false, NOW)),
            listOf(video(ID, "OtherVideo1", false, NOW)),
        )
        every { f.youtube.lookup(any()) } answers {
            f.events.add("lookup")
            mapOf(VIDEO to METADATA)
        }

        // when
        val thrown = assertThatThrownBy {
            if (operation == "publish") {
                f.service.publish(ID, Publication(true, 5))
            } else {
                f.service.recheck(ID, 5)
            }
        }

        // then
        thrown.isInstanceOfSatisfying(ResponseStatusException::class.java) { e ->
            assertThat(e.statusCode.value()).isEqualTo(409)
        }
        verify(exactly = 0) { f.repo.metadata(any(), any(), any()) }
        verify(exactly = 0) { f.repo.changed() }
        assertThat(f.events).containsExactly("lookup", "begin", "rollback")
    }

    @Test
    fun `게시된 모든 ID가 새 순서로 주어질 때__순서를 바꾸면__숨긴 항목을 정확한 게시 집합 뒤에 둔다`() {
        // given
        val f = Fixture()
        val hidden = UUID(0, 3)

        every { f.repo.lockBoard() } returns 5L
        every { f.repo.revision() } returns 5L
        every { f.repo.list() } returns listOf(
            video(ID, VIDEO, true, NOW),
            video(OTHER, "OtherVideo1", true, NOW),
            video(hidden, "hidden", false, NOW),
        )
        justRun { f.repo.position(any(), any()) }
        justRun { f.repo.changed() }

        // when
        f.service.reorder(Order(listOf(OTHER, ID), 5))

        // then
        verifyOrder {
            f.repo.lockBoard()
            f.repo.list()
            f.repo.position(OTHER, 0)
            f.repo.position(ID, 1)
            f.repo.list()
            f.repo.position(hidden, 2)
            f.repo.changed()
        }
        assertThat(f.events).containsExactly("begin", "commit")
        confirmVerified(f.youtube)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `게시 ID가 중복되거나 불완전할 때__순서를 바꾸면__일부 위치만 바뀌지 않게 거부한다`(
        duplicate: Boolean,
    ) {
        // given
        val f = Fixture()

        every { f.repo.lockBoard() } returns 5L
        every { f.repo.list() } returns listOf(video(ID, VIDEO, true, NOW), video(OTHER, "OtherVideo1", true, NOW))

        // when
        val thrown = assertThatThrownBy { f.service.reorder(Order(if (duplicate) listOf(ID, ID) else listOf(ID), 5)) }

        // then
        thrown.isInstanceOfSatisfying(ResponseStatusException::class.java) { e ->
            assertThat(e.statusCode.value()).isEqualTo(409)
        }
        verify(exactly = 0) { f.repo.position(any(), any()) }
        verify(exactly = 0) { f.repo.changed() }
        assertThat(f.events).containsExactly("begin", "rollback")
    }

    @Test
    fun `공급자가 선택한 ID가 없다고 보고할 때__다시 확인하면__선택과 게시는 그대로 두고 메타데이터를 지운다`() {
        // given
        val f = Fixture()

        every { f.clock.instant() } returns NOW
        every { f.repo.lockBoard() } returns 5L
        every { f.repo.revision() } returns 5L
        every { f.repo.list() } returns listOf(video(ID, VIDEO, true, NOW))
        every { f.youtube.lookup(any()) } returns emptyMap()
        justRun { f.repo.metadata(any(), any(), any()) }
        justRun { f.repo.changed() }

        // when
        f.service.recheck(ID, 5)

        // then
        verify(exactly = 1) { f.repo.metadata(ID, null, NOW) }
        verify(exactly = 0) { f.repo.selection(any(), any(), any()) }
        verify(exactly = 0) { f.repo.publication(any(), any()) }
        verify(exactly = 1) { f.repo.changed() }
    }

    @Test
    fun `메타데이터가 만료되고 공급자가 실패할 때__갱신하면__외부 실패 전에 만료를 커밋한다`() {
        // given
        val f = Fixture()

        every { f.clock.instant() } returns NOW
        every { f.repo.lockBoard() } returns 5L
        every { f.repo.expire(any()) } returns 1
        every { f.repo.list() } returns listOf(video(ID, VIDEO, true, null))
        every { f.youtube.lookup(any()) } answers {
            f.events.add("lookup")
            throw IllegalStateException("synthetic-provider")
        }
        justRun { f.repo.changed() }

        // when
        val thrown = assertThatThrownBy { f.service.refresh() }

        // then
        thrown.isInstanceOf(IllegalStateException::class.java)
        verify(exactly = 1) { f.repo.expire(NOW.minus(Duration.ofDays(29))) }
        verify(exactly = 1) { f.repo.changed() }
        verify(exactly = 0) { f.repo.metadata(any(), any(), any()) }
        assertThat(f.events).containsExactly("begin", "commit", "lookup")
    }

    @Test
    fun `일 경계와 낡은 스냅숏이 있을 때__갱신하면__정확히 하루인 항목은 제외하고 바뀌지 않은 선택 행만 갱신한다`() {
        // given
        val f = Fixture()
        val unchecked = video(ID, VIDEO, true, null)
        val old = video(OTHER, "OtherVideo1", true, NOW.minus(Duration.ofDays(2)))
        val boundary = video(UUID(0, 3), "AtBoundary1", true, NOW.minus(Duration.ofDays(1)))

        every { f.clock.instant() } returns NOW
        every { f.repo.lockBoard() } returns 5L
        every { f.repo.expire(any()) } returns 0
        every { f.repo.list() } returnsMany listOf(
            listOf(unchecked, old, boundary),
            listOf(unchecked, video(OTHER, "OtherVideo1", true, NOW), boundary),
        )
        every { f.youtube.lookup(any()) } answers {
            f.events.add("lookup")
            mapOf(VIDEO to METADATA)
        }
        justRun { f.repo.metadata(any(), any(), any()) }
        justRun { f.repo.changed() }

        // when
        f.service.refresh()

        // then
        verify(exactly = 1) { f.youtube.lookup(listOf(VIDEO, "OtherVideo1")) }
        verify(exactly = 1) { f.repo.metadata(ID, METADATA, NOW) }
        verify(exactly = 0) { f.repo.metadata(OTHER, any(), any()) }
        verify(exactly = 1) { f.repo.changed() }
        assertThat(f.events).containsExactly("begin", "commit", "lookup", "begin", "commit")
    }

    private class Fixture {
        val events = ArrayList<String>()
        val manager = Manager(events)
        val repo: JdbcHomeVideoRepository = mockk<JdbcHomeVideoRepository>()
        val youtube: YouTubeVideoClient = mockk<YouTubeVideoClient>()
        val clock: Clock = mockk<Clock>()
        val service = JdbcHomeVideoManagement(repo, youtube, clock, TransactionTemplate(manager))
    }

    private class Manager(val events: MutableList<String>) : AbstractPlatformTransactionManager() {
        var timeout = 0

        override fun doGetTransaction(): Any = Any()

        override fun doBegin(transaction: Any, definition: TransactionDefinition) {
            timeout = definition.timeout
            events.add("begin")
        }

        override fun doCommit(status: DefaultTransactionStatus) {
            events.add("commit")
        }

        override fun doRollback(status: DefaultTransactionStatus) {
            events.add("rollback")
        }
    }

    private companion object {
        const val VIDEO = "AbCdEfGhI_1"
        const val URL = "https://youtu.be/$VIDEO"
        val ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val OTHER: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val NOW: Instant = Instant.parse("2030-01-01T00:00:00.123456Z")
        val METADATA = Metadata(VIDEO, "title", "channel", "https://i.ytimg.com/x.jpg", 60, true)

        fun video(id: UUID, videoId: String, published: Boolean, checked: Instant?): HomeVideo =
            HomeVideo(id, videoId, published, 0, NOW, "title", "channel", null, 60L, true, checked)
    }
}
