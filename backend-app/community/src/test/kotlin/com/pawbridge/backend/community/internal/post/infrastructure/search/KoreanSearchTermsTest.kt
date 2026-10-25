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
    fun given__blank_or_tokenless_input__when__query__then__empty_expression(keyword: String?) {
        assertEquals("", terms.query(keyword))
    }

    @Test
    fun given__overlong_whitespace__when__query__then__blank_precedes_length_validation() {
        assertEquals("", terms.query(" ".repeat(201)))
    }

    @Test
    fun given__nonblank_overlong_or_nul_input__when__query__then__reject_invalid_input() {
        assertThrows(InvalidPostSearchInputException::class.java) { terms.query("a".repeat(201)) }
        assertThrows(InvalidPostSearchInputException::class.java) { terms.query("alpha\u0000beta") }
        assertThrows(InvalidPostSearchInputException::class.java) { terms.query("😀".repeat(101)) }
        assertDoesNotThrow(Executable { terms.query("a".repeat(200)) })
        assertDoesNotThrow(Executable { terms.query("😀".repeat(100)) })
    }

    @Test
    fun given__repeated_tokens__when__query__then__distinct_ordered_or_expression_preserves_document_tokens() {
        assertEquals("'alpha' | 'beta'", terms.query("alpha alpha beta"))
        assertEquals("alpha alpha beta", terms.document("alpha alpha beta"))
        assertEquals("'abc'", terms.query("abc ".repeat(40)))
    }

    @Test
    fun given__distinct_token_boundary__when__query__then__at_most_thirty_two_distinct_tokens() {
        val words = (0 until 33).joinToString(" ") { index ->
            "aaa" + ('a' + index / 26) + ('a' + index % 26)
        }
        assertDoesNotThrow(Executable { terms.query(words.substring(0, words.lastIndexOf(' '))) })
        assertThrows(InvalidPostSearchInputException::class.java) { terms.query(words) }
    }
}
