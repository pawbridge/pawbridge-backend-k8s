package com.pawbridge.backend.community.internal.note.application

import com.pawbridge.backend.community.internal.note.application.BlockPage.BlockView
import com.pawbridge.backend.community.internal.note.application.NoteBlockListReader.Block
import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.api.ContactMemberUnavailableException
import com.pawbridge.backend.user.api.ContactMembersQuery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when` as whenever
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.transaction.TransactionTimedOutException
import java.time.Instant

class ListNoteBlocksTest {
    private val members = mock(ContactMemberQuery::class.java)
    private val blocks = mock(NoteBlockListReader::class.java)
    private val contactMembers = mock(ContactMembersQuery::class.java)
    private val query = ListNoteBlocks(members, blocks, contactMembers)

    @ParameterizedTest
    @ValueSource(ints = [-1, 10001])
    fun given__invalid_page_and_current_id__when__reading__then__reject_page_before_login_or_any_dependency(page: Int) {
        assertEquals(
            NoteBlockListRejectedException.Reason.INVALID_PAGE,
            assertThrows(NoteBlockListRejectedException::class.java) { query.read(0L, page) }.reason(),
        )

        verifyNoInteractions(members, blocks, contactMembers)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 10000])
    fun given__valid_page_boundary_beyond_content__when__reading__then__keep_requested_page_count_and_order_without_batch(
        page: Int,
    ) {
        whenever(members.get(7L)).thenReturn(CURRENT)
        whenever(blocks.count(7L)).thenReturn(11L)
        whenever(blocks.read(7L, page)).thenReturn(listOf())

        assertEquals(BlockPage(listOf(), 11L, 2, page), query.read(7L, page))

        val order = inOrder(members, blocks)
        order.verify(members).get(7L)
        order.verify(blocks).count(7L)
        order.verify(blocks).read(7L, page)
        verifyNoMoreInteractions(members, blocks)
        verifyNoInteractions(contactMembers)
    }

    @ParameterizedTest
    @ValueSource(longs = [0L, -1L])
    fun given__valid_page_and_invalid_current_id__when__reading__then__reject_login_before_user_or_block_lookup(
        currentUserId: Long,
    ) {
        assertEquals(
            NoteBlockListRejectedException.Reason.LOGIN,
            assertThrows(NoteBlockListRejectedException::class.java) { query.read(currentUserId, 0) }.reason(),
        )

        verifyNoInteractions(members, blocks, contactMembers)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun given__missing_or_pending_current_member__when__reading__then__reject_inactive_before_count_list_or_batch(
        pending: Boolean,
    ) {
        whenever(members.get(7L)).thenReturn(ContactMember(7L, "탈퇴한 회원", false, pending))

        assertEquals(
            NoteBlockListRejectedException.Reason.INACTIVE,
            assertThrows(NoteBlockListRejectedException::class.java) { query.read(7L, 0) }.reason(),
        )

        verify(members).get(7L)
        verifyNoMoreInteractions(members)
        verifyNoInteractions(blocks, contactMembers)
    }

    @Test
    fun given__ordered_blocks_and_unordered_complete_contacts__when__reading__then__batch_once_and_preserve_target_ids_raw_names_instants_and_page() {
        val first = Instant.parse("2026-10-19T14:59:59.123456Z")
        val second = Instant.parse("1969-12-31T23:59:59.000001Z")
        val page = listOf(
            Block(90L, first),
            Block(80L, first),
            Block(70L, first),
            Block(60L, second),
            Block(50L, second),
        )
        whenever(members.get(7L)).thenReturn(CURRENT)
        whenever(blocks.count(7L)).thenReturn(15L)
        whenever(blocks.read(7L, 1)).thenReturn(page)
        whenever(contactMembers.getAll(listOf(90L, 80L, 70L, 60L, 50L))).thenReturn(
            listOf(
                ContactMember(50L, "탈퇴한 회원", false, true),
                ContactMember(60L, "탈퇴한 회원", false, false),
                ContactMember(70L, "  ", true, false),
                ContactMember(80L, "", true, false),
                ContactMember(90L, " 원문 닉네임 ", true, false),
            ),
        )

        assertEquals(
            BlockPage(
                listOf(
                    BlockView(90L, " 원문 닉네임 ", first),
                    BlockView(80L, "", first),
                    BlockView(70L, "  ", first),
                    BlockView(60L, "탈퇴한 회원", second),
                    BlockView(50L, "탈퇴한 회원", second),
                ),
                15L,
                2,
                1,
            ),
            query.read(7L, 1),
        )

        val order = inOrder(members, blocks, contactMembers)
        order.verify(members).get(7L)
        order.verify(blocks).count(7L)
        order.verify(blocks).read(7L, 1)
        order.verify(contactMembers).getAll(listOf(90L, 80L, 70L, 60L, 50L))
        verifyNoMoreInteractions(members, blocks, contactMembers)
    }

    @ParameterizedTest
    @ValueSource(longs = [0L, 17L])
    fun given__empty_page_including_zero_count__when__reading__then__still_read_the_page_once_and_skip_only_batch(
        count: Long,
    ) {
        whenever(members.get(7L)).thenReturn(CURRENT)
        whenever(blocks.count(7L)).thenReturn(count)
        whenever(blocks.read(7L, 1)).thenReturn(listOf())

        assertEquals(BlockPage(listOf(), count, if (count == 0L) 0 else 2, 1), query.read(7L, 1))

        val order = inOrder(members, blocks)
        order.verify(members).get(7L)
        order.verify(blocks).count(7L)
        order.verify(blocks).read(7L, 1)
        verifyNoMoreInteractions(members, blocks)
        verifyNoInteractions(contactMembers)
    }

    @ParameterizedTest
    @EnumSource(FailurePhase::class)
    fun given__caller_count_list_or_batch_failure__when__reading__then__preserve_failure_origin_and_stop_later_steps(
        phase: FailurePhase,
    ) {
        val failure: RuntimeException = when (phase) {
            FailurePhase.CALLER, FailurePhase.BATCH ->
                ContactMemberUnavailableException(DataAccessResourceFailureException("private User SQL"))
            FailurePhase.COUNT -> DataAccessResourceFailureException("private count SQL")
            FailurePhase.PAGE -> TransactionTimedOutException("private list timeout")
        }
        whenever(members.get(7L)).thenReturn(CURRENT)
        whenever(blocks.count(7L)).thenReturn(1L)
        whenever(blocks.read(7L, 0)).thenReturn(listOf(Block(8L, Instant.EPOCH)))
        when (phase) {
            FailurePhase.CALLER -> whenever(members.get(7L)).thenThrow(failure)
            FailurePhase.COUNT -> whenever(blocks.count(7L)).thenThrow(failure)
            FailurePhase.PAGE -> whenever(blocks.read(7L, 0)).thenThrow(failure)
            FailurePhase.BATCH -> whenever(contactMembers.getAll(listOf(8L))).thenThrow(failure)
        }

        assertSame(failure, assertThrows(RuntimeException::class.java) { query.read(7L, 0) })

        val order = inOrder(members, blocks, contactMembers)
        order.verify(members).get(7L)
        if (phase != FailurePhase.CALLER) {
            order.verify(blocks).count(7L)
        }
        if (phase == FailurePhase.PAGE || phase == FailurePhase.BATCH) {
            order.verify(blocks).read(7L, 0)
        }
        if (phase == FailurePhase.BATCH) {
            order.verify(contactMembers).getAll(listOf(8L))
        }
        verifyNoMoreInteractions(members, blocks, contactMembers)
    }

    enum class FailurePhase { CALLER, COUNT, PAGE, BATCH }

    private companion object {
        val CURRENT = ContactMember(7L, "현재 회원", true, false)
    }
}
