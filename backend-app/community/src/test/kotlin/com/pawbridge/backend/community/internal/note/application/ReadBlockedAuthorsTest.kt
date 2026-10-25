package com.pawbridge.backend.community.internal.note.application

import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.api.ContactMemberUnavailableException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Test
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when` as whenever
import org.springframework.dao.DataAccessResourceFailureException
import java.util.Collections

class ReadBlockedAuthorsTest {
    private val members = mock(ContactMemberQuery::class.java)
    private val blocks = mock(NoteBlockStatusReader::class.java)
    private val query = ReadBlockedAuthors(members, blocks)

    @Test
    fun given__valid_authors_with_duplicates_self_and_max_id__when__reading__then__check_current_once_and_pass_distinct_ids_without_target_lookups() {
        whenever(members.get(7L)).thenReturn(CURRENT)
        val storedIds = listOf(99L, Long.MAX_VALUE)
        whenever(blocks.blockedAuthors(7L, listOf(Long.MAX_VALUE, 7L, 99L))).thenReturn(storedIds)

        val result = query.read(7L, listOf(Long.MAX_VALUE, 7L, 99L, Long.MAX_VALUE, 99L))

        assertSame(storedIds, result.memberIds)
        val order = inOrder(members, blocks)
        order.verify(members).get(7L)
        order.verify(blocks).blockedAuthors(7L, listOf(Long.MAX_VALUE, 7L, 99L))
        verifyNoMoreInteractions(members, blocks)
    }

    @ParameterizedTest
    @EnumSource(InvalidInput::class)
    fun given__invalid_original_list_and_current_id__when__reading__then__reject_members_before_login_or_any_lookup(
        input: InvalidInput,
    ) {
        val ids: List<Long?>? = when (input) {
            InvalidInput.NULL -> null
            InvalidInput.EMPTY -> listOf()
            InvalidInput.NULL_ELEMENT -> listOf(8L, null, 9L)
            InvalidInput.ZERO -> listOf(8L, 0L)
            InvalidInput.NEGATIVE -> listOf(-1L, 8L)
            InvalidInput.DISTINCT_101 -> (1L..101L).toList()
            InvalidInput.DUPLICATE_101 -> Collections.nCopies(101, 8L)
        }

        assertEquals(
            BlockStatusRejectedException.Reason.INVALID_MEMBERS,
            assertThrows(BlockStatusRejectedException::class.java) { query.read(-1L, ids) }.reason(),
        )

        verifyNoInteractions(members, blocks)
    }

    @ParameterizedTest
    @EnumSource(ValidBoundary::class)
    fun given__one_or_one_hundred_original_ids__when__reading__then__allow_boundary_and_deduplicate_only_after_current_lookup(
        boundary: ValidBoundary,
    ) {
        val ids: List<Long> = when (boundary) {
            ValidBoundary.ONE -> listOf(8L)
            ValidBoundary.DISTINCT_100 -> (1L..100L).toList()
            ValidBoundary.DUPLICATE_100 -> Collections.nCopies(100, 8L)
        }
        val distinctIds = ids.distinct()
        whenever(members.get(7L)).thenReturn(CURRENT)
        whenever(blocks.blockedAuthors(7L, distinctIds)).thenReturn(listOf())

        assertEquals(listOf<Long>(), query.read(7L, ids).memberIds)

        val order = inOrder(members, blocks)
        order.verify(members).get(7L)
        order.verify(blocks).blockedAuthors(7L, distinctIds)
        verifyNoMoreInteractions(members, blocks)
    }

    @ParameterizedTest
    @ValueSource(longs = [0L, -1L])
    fun given__valid_list_and_invalid_current_id__when__reading__then__reject_login_before_user_or_block_lookup(
        currentId: Long,
    ) {
        assertEquals(
            BlockStatusRejectedException.Reason.LOGIN,
            assertThrows(BlockStatusRejectedException::class.java) { query.read(currentId, listOf(8L)) }.reason(),
        )

        verifyNoInteractions(members, blocks)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun given__missing_or_pending_current_member__when__reading__then__reject_inactive_before_block_lookup(
        pending: Boolean,
    ) {
        whenever(members.get(7L)).thenReturn(ContactMember(7L, "탈퇴한 회원", false, pending))

        assertEquals(
            BlockStatusRejectedException.Reason.INACTIVE,
            assertThrows(BlockStatusRejectedException::class.java) { query.read(7L, listOf(8L)) }.reason(),
        )

        verify(members).get(7L)
        verifyNoMoreInteractions(members)
        verifyNoInteractions(blocks)
    }

    @ParameterizedTest
    @EnumSource(FailurePhase::class)
    fun given__user_or_block_lookup_failure__when__reading__then__preserve_failure_origin_and_stop_later_lookups(
        phase: FailurePhase,
    ) {
        val failure: RuntimeException = when (phase) {
            FailurePhase.USER ->
                ContactMemberUnavailableException(DataAccessResourceFailureException("private User SQL"))
            FailurePhase.BLOCK_DAO -> DataAccessResourceFailureException("private block SQL")
            FailurePhase.BLOCK_RUNTIME -> IllegalStateException("private block runtime")
        }
        if (phase == FailurePhase.USER) {
            whenever(members.get(7L)).thenThrow(failure)
        } else {
            whenever(members.get(7L)).thenReturn(CURRENT)
            whenever(blocks.blockedAuthors(7L, listOf(8L))).thenThrow(failure)
        }

        assertSame(
            failure,
            assertThrows(RuntimeException::class.java) { query.read(7L, listOf(8L, 8L)) },
        )

        val order = inOrder(members, blocks)
        order.verify(members).get(7L)
        if (phase != FailurePhase.USER) {
            order.verify(blocks).blockedAuthors(7L, listOf(8L))
        }
        verifyNoMoreInteractions(members, blocks)
    }

    enum class InvalidInput { NULL, EMPTY, NULL_ELEMENT, ZERO, NEGATIVE, DISTINCT_101, DUPLICATE_101 }

    enum class ValidBoundary { ONE, DISTINCT_100, DUPLICATE_100 }

    enum class FailurePhase { USER, BLOCK_DAO, BLOCK_RUNTIME }

    private companion object {
        val CURRENT = ContactMember(7L, "현재 회원", true, false)
    }
}
