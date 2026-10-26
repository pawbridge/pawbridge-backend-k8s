package com.pawbridge.backend.community.internal.post.infrastructure.search

import com.pawbridge.backend.community.internal.post.application.InvalidPostSearchInputException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullAndEmptySource
import org.junit.jupiter.params.provider.ValueSource

class KoreanSearchTermsTest {
    private val terms = KoreanSearchTerms()

    @AfterEach
    fun closeAnalyzer() {
        terms.close()
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = [" ", "\t\n", "!!!"])
    fun `공백이거나 토큰이 없는 입력일 때__질의하면__빈 표현식을 돌려준다`(keyword: String?) {
        // given
        val input = keyword

        // when
        val result = terms.query(input)

        // then
        assertEquals("", result)
    }

    @Test
    fun `길이를 넘는 공백일 때__질의하면__길이 검증보다 공백 판정이 먼저라 빈 표현식을 돌려준다`() {
        // given
        val input = " ".repeat(201)

        // when
        val result = terms.query(input)

        // then
        assertEquals("", result)
    }

    @Test
    fun `공백이 아니면서 너무 길거나 NUL이 든 입력일 때__질의하면__잘못된 입력으로 거부한다`() {
        // given
        val tooLong = "a".repeat(201)
        val withNul = "alpha\u0000beta"
        val tooLongEmoji = "😀".repeat(101)
        val maxLength = "a".repeat(200)
        val maxLengthEmoji = "😀".repeat(100)

        // when
        val rejectTooLong = Executable { terms.query(tooLong) }
        val rejectNul = Executable { terms.query(withNul) }
        val rejectEmoji = Executable { terms.query(tooLongEmoji) }
        val acceptMax = Executable { terms.query(maxLength) }
        val acceptEmoji = Executable { terms.query(maxLengthEmoji) }

        // then
        assertThrows(InvalidPostSearchInputException::class.java, rejectTooLong)
        assertThrows(InvalidPostSearchInputException::class.java, rejectNul)
        assertThrows(InvalidPostSearchInputException::class.java, rejectEmoji)
        assertDoesNotThrow(acceptMax)
        assertDoesNotThrow(acceptEmoji)
    }

    @Test
    fun `토큰이 반복될 때__질의하면__중복 없이 순서를 지킨 OR 표현식을 만들고 문서 토큰은 그대로 둔다`() {
        // given
        val repeated = "alpha alpha beta"
        val manyRepeats = "abc ".repeat(40)

        // when
        val query = terms.query(repeated)
        val document = terms.document(repeated)
        val collapsed = terms.query(manyRepeats)

        // then
        assertEquals("'alpha' | 'beta'", query)
        assertEquals("alpha alpha beta", document)
        assertEquals("'abc'", collapsed)
    }

    @Test
    fun `서로 다른 토큰이 경계에 있을 때__질의하면__서로 다른 토큰은 최대 32개까지만 허용한다`() {
        // given
        val words = (0 until 33).joinToString(" ") { index ->
            "aaa" + ('a' + index / 26) + ('a' + index % 26)
        }
        val thirtyTwoWords = words.substring(0, words.lastIndexOf(' '))

        // when
        val acceptBoundary = Executable { terms.query(thirtyTwoWords) }
        val rejectOver = Executable { terms.query(words) }

        // then
        assertDoesNotThrow(acceptBoundary)
        assertThrows(InvalidPostSearchInputException::class.java, rejectOver)
    }
}
