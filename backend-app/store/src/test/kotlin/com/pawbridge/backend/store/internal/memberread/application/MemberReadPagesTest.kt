package com.pawbridge.backend.store.internal.memberread.application

import com.pawbridge.backend.store.api.StoreMemberReads
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.support.PageableExecutionUtils
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.LongSupplier
import java.util.stream.Stream

class MemberReadPagesTest {
    @ParameterizedTest
    @MethodSource("pages")
    fun given__repository_page_content__when__assembling__then__match_real_spring_page_total_and_count_execution(
        page: Int,
        size: Int,
        elements: Int,
        count: Long,
    ) {
        val content: List<Int> = (0 until elements).toList()
        val request = StoreMemberReads.Request(page, size, listOf(StoreMemberReads.Order("createdAt", false, false)))
        val originalCalls = AtomicInteger()
        val candidateCalls = AtomicInteger()
        val original = PageableExecutionUtils.getPage(
            content,
            PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")),
            LongSupplier {
                originalCalls.incrementAndGet()
                count
            },
        )
        val candidate = MemberReadPages.page(
            content,
            request,
            LongSupplier {
                candidateCalls.incrementAndGet()
                count
            },
        )

        assertEquals(original.content, candidate.content)
        assertEquals(original.number, candidate.number)
        assertEquals(original.size, candidate.size)
        assertEquals(original.totalElements, candidate.totalElements)
        assertEquals(originalCalls.get(), candidateCalls.get())
        assertEquals(request.sort(), candidate.sort)
    }

    companion object {
        @JvmStatic
        fun pages(): Stream<Arguments> = Stream.of(
            Arguments.of(0, 20, 0, 999L),
            Arguments.of(0, 20, 3, 999L),
            Arguments.of(0, 2, 2, 9L),
            Arguments.of(2, 2, 1, 999L),
            Arguments.of(2, 2, 0, 2L),
            Arguments.of(2, 2, 2, 5L),
            Arguments.of(2, 2, 2, 9L),
        )
    }
}
