package com.pawbridge.backend.community.internal.homevideo.infrastructure

import com.pawbridge.backend.community.internal.homevideo.application.HomeVideo
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.Board
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.Metadata
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.Order
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.Publication
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.Save
import com.pawbridge.backend.community.support.anyNonNull
import com.pawbridge.backend.community.support.capture
import com.pawbridge.backend.community.support.eqNonNull
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when` as whenever
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
    fun given__an_existing_board__when__reading__then__lock_and_snapshot_in_one_source_default_transaction() {
        val f = Fixture()

        assertThat(f.service.board()).isEqualTo(Board(5, listOf()))

        val order = inOrder(f.repo)
        order.verify(f.repo).lockBoard()
        order.verify(f.repo).revision()
        order.verify(f.repo).list()
        assertThat(f.events).containsExactly("begin", "commit")
        assertThat(f.manager.timeout).isEqualTo(-1)
        verifyNoInteractions(f.youtube)
    }

    @Test
    fun given__new_available_video__when__saving__then__lookup_before_transaction_then_insert_metadata_and_revision_once() {
        val f = Fixture()

        f.service.save(null, Save(URL, true, 5))

        assertThat(f.events).containsExactly("lookup", "begin", "commit")
        val id: ArgumentCaptor<UUID> = ArgumentCaptor.forClass(UUID::class.java)
        val order = inOrder(f.repo)
        order.verify(f.repo).lockBoard()
        order.verify(f.repo).list()
        order.verify(f.repo).insert(capture(id), eqNonNull(VIDEO), eq(true), eq(0), eqNonNull(NOW))
        order.verify(f.repo).metadata(id.value, METADATA, NOW)
        order.verify(f.repo).changed()
        order.verify(f.repo).revision()
        order.verify(f.repo).list()
        verify(f.clock, times(2)).instant()
    }

    @ParameterizedTest
    @ValueSource(strings = ["revision", "duplicate", "capacity", "slots", "unavailable"])
    fun given__a_conflicting_or_unavailable_selection__when__saving__then__preserve_source_rejection_before_any_mutation(
        mode: String,
    ) {
        val f = Fixture()
        when (mode) {
            "revision" -> whenever(f.repo.lockBoard()).thenReturn(6L)
            "duplicate" -> whenever(f.repo.list()).thenReturn(listOf(video(OTHER, VIDEO, false, NOW)))
            "capacity" -> {
                val rows = ArrayList<HomeVideo>()
                for (i in 0 until 100) {
                    rows.add(video(UUID(0, (i + 1).toLong()), "other$i", false, NOW))
                }
                whenever(f.repo.list()).thenReturn(rows)
            }
            "slots" -> whenever(f.repo.list()).thenReturn(
                listOf(
                    video(ID, "one", true, NOW),
                    video(OTHER, "two", true, NOW),
                    video(UUID(0, 3), "three", true, NOW),
                ),
            )
            else -> doAnswer {
                f.events.add("lookup")
                mapOf(VIDEO to Metadata(VIDEO, "title", "channel", null, 60, false))
            }.`when`(f.youtube).lookup(anyList())
        }

        assertThatThrownBy { f.service.save(null, Save(URL, true, 5)) }
            .isInstanceOfSatisfying(ResponseStatusException::class.java) { e ->
                assertThat(e.statusCode.value()).isEqualTo(if (mode == "unavailable") 400 else 409)
            }
        verify(f.repo, never()).insert(anyNonNull(), anyString(), anyBoolean(), anyInt(), anyNonNull())
        verify(f.repo, never()).changed()
        assertThat(f.events).containsExactlyElementsOf(
            if (mode == "unavailable") listOf("lookup") else listOf("lookup", "begin", "rollback"),
        )
    }

    @Test
    fun given__a_published_video_and_unavailable_provider__when__hiding__then__do_not_lookup_or_replace_metadata() {
        val f = Fixture()
        whenever(f.repo.list()).thenReturn(listOf(video(ID, VIDEO, true, NOW)))

        f.service.publish(ID, Publication(false, 5))

        verifyNoInteractions(f.youtube)
        verify(f.repo).publication(ID, false)
        verify(f.repo, never()).metadata(anyNonNull(), any(), anyNonNull())
        assertThat(f.events).containsExactly("begin", "commit")
    }

    @ParameterizedTest
    @ValueSource(strings = ["publish", "recheck"])
    fun given__a_selection_changed_during_external_lookup__when__publishing_or_rechecking__then__reject_the_stale_metadata_after_lock(
        operation: String,
    ) {
        val f = Fixture()
        whenever(f.repo.list()).thenReturn(
            listOf(video(ID, VIDEO, false, NOW)),
            listOf(video(ID, "OtherVideo1", false, NOW)),
        )

        assertThatThrownBy {
            if (operation == "publish") {
                f.service.publish(ID, Publication(true, 5))
            } else {
                f.service.recheck(ID, 5)
            }
        }.isInstanceOfSatisfying(ResponseStatusException::class.java) { e ->
            assertThat(e.statusCode.value()).isEqualTo(409)
        }
        verify(f.repo, never()).metadata(anyNonNull(), any(), anyNonNull())
        verify(f.repo, never()).changed()
        assertThat(f.events).containsExactly("lookup", "begin", "rollback")
    }

    @Test
    fun given__all_published_ids_in_a_new_order__when__reordering__then__retain_hidden_items_behind_the_exact_published_set() {
        val f = Fixture()
        val hidden = UUID(0, 3)
        whenever(f.repo.list()).thenReturn(
            listOf(
                video(ID, VIDEO, true, NOW),
                video(OTHER, "OtherVideo1", true, NOW),
                video(hidden, "hidden", false, NOW),
            ),
        )

        f.service.reorder(Order(listOf(OTHER, ID), 5))

        val order = inOrder(f.repo)
        order.verify(f.repo).lockBoard()
        order.verify(f.repo).list()
        order.verify(f.repo).position(OTHER, 0)
        order.verify(f.repo).position(ID, 1)
        order.verify(f.repo).list()
        order.verify(f.repo).position(hidden, 2)
        order.verify(f.repo).changed()
        assertThat(f.events).containsExactly("begin", "commit")
        verifyNoInteractions(f.youtube)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun given__duplicate_or_incomplete_published_ids__when__reordering__then__reject_without_partial_positions(
        duplicate: Boolean,
    ) {
        val f = Fixture()
        whenever(f.repo.list()).thenReturn(
            listOf(video(ID, VIDEO, true, NOW), video(OTHER, "OtherVideo1", true, NOW)),
        )

        assertThatThrownBy { f.service.reorder(Order(if (duplicate) listOf(ID, ID) else listOf(ID), 5)) }
            .isInstanceOfSatisfying(ResponseStatusException::class.java) { e ->
                assertThat(e.statusCode.value()).isEqualTo(409)
            }
        verify(f.repo, never()).position(anyNonNull(), anyInt())
        verify(f.repo, never()).changed()
        assertThat(f.events).containsExactly("begin", "rollback")
    }

    @Test
    fun given__provider_reports_the_selected_id_absent__when__rechecking__then__clear_metadata_without_changing_selection_or_publication() {
        val f = Fixture()
        whenever(f.repo.list()).thenReturn(listOf(video(ID, VIDEO, true, NOW)))
        doReturn(emptyMap<String, Metadata>()).`when`(f.youtube).lookup(anyList())

        f.service.recheck(ID, 5)

        verify(f.repo).metadata(ID, null, NOW)
        verify(f.repo, never()).selection(anyNonNull(), anyString(), anyBoolean())
        verify(f.repo, never()).publication(anyNonNull(), anyBoolean())
        verify(f.repo).changed()
    }

    @Test
    fun given__expired_metadata_and_provider_failure__when__refreshing__then__commit_expiry_before_external_failure() {
        val f = Fixture()
        whenever(f.repo.expire(NOW.minus(Duration.ofDays(29)))).thenReturn(1)
        whenever(f.repo.list()).thenReturn(listOf(video(ID, VIDEO, true, null)))
        doAnswer {
            f.events.add("lookup")
            throw IllegalStateException("synthetic-provider")
        }.`when`(f.youtube).lookup(anyList())

        assertThatThrownBy { f.service.refresh() }.isInstanceOf(IllegalStateException::class.java)
        verify(f.repo).changed()
        verify(f.repo, never()).metadata(anyNonNull(), any(), anyNonNull())
        assertThat(f.events).containsExactly("begin", "commit", "lookup")
    }

    @Test
    fun given__daily_boundary_and_stale_snapshot__when__refreshing__then__exclude_exact_day_and_update_only_unchanged_selected_rows() {
        val f = Fixture()
        val unchecked = video(ID, VIDEO, true, null)
        val old = video(OTHER, "OtherVideo1", true, NOW.minus(Duration.ofDays(2)))
        val boundary = video(UUID(0, 3), "AtBoundary1", true, NOW.minus(Duration.ofDays(1)))
        whenever(f.repo.list()).thenReturn(
            listOf(unchecked, old, boundary),
            listOf(unchecked, video(OTHER, "OtherVideo1", true, NOW), boundary),
        )

        f.service.refresh()

        verify(f.youtube).lookup(listOf(VIDEO, "OtherVideo1"))
        verify(f.repo).metadata(ID, METADATA, NOW)
        verify(f.repo, never()).metadata(eqNonNull(OTHER), any(), anyNonNull())
        verify(f.repo).changed()
        assertThat(f.events).containsExactly("begin", "commit", "lookup", "begin", "commit")
    }

    private class Fixture {
        val events = ArrayList<String>()
        val manager = Manager(events)
        val repo: JdbcHomeVideoRepository = mock(JdbcHomeVideoRepository::class.java)
        val youtube: YouTubeVideoClient = mock(YouTubeVideoClient::class.java)
        val clock: Clock = mock(Clock::class.java)
        val service: JdbcHomeVideoManagement

        init {
            whenever(clock.instant()).thenReturn(NOW)
            whenever(repo.lockBoard()).thenReturn(5L)
            whenever(repo.revision()).thenReturn(5L)
            whenever(repo.list()).thenReturn(listOf())
            whenever(youtube.lookup(anyList())).thenAnswer {
                events.add("lookup")
                mapOf(VIDEO to METADATA)
            }
            service = JdbcHomeVideoManagement(repo, youtube, clock, TransactionTemplate(manager))
        }
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
