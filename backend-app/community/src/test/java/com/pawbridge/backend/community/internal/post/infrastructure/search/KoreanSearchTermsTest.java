package com.pawbridge.backend.community.internal.post.infrastructure.search;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.pawbridge.backend.community.internal.post.application.InvalidPostSearchInputException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class KoreanSearchTermsTest {
    private final KoreanSearchTerms terms = new KoreanSearchTerms();

    @AfterEach
    void closeAnalyzer() { terms.close(); }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "!!!"})
    void given__blank_or_tokenless_input__when__query__then__empty_expression(String keyword) {
        assertEquals("", terms.query(keyword));
    }

    @Test
    void given__overlong_whitespace__when__query__then__blank_precedes_length_validation() {
        assertEquals("", terms.query(" ".repeat(201)));
    }

    @Test
    void given__nonblank_overlong_or_nul_input__when__query__then__reject_invalid_input() {
        assertThrows(InvalidPostSearchInputException.class, () -> terms.query("a".repeat(201)));
        assertThrows(InvalidPostSearchInputException.class, () -> terms.query("alpha\0beta"));
        assertThrows(InvalidPostSearchInputException.class, () -> terms.query("😀".repeat(101)));
        assertDoesNotThrow(() -> terms.query("a".repeat(200)));
        assertDoesNotThrow(() -> terms.query("😀".repeat(100)));
    }

    @Test
    void given__repeated_tokens__when__query__then__distinct_ordered_or_expression_preserves_document_tokens() {
        assertEquals("'alpha' | 'beta'", terms.query("alpha alpha beta"));
        assertEquals("alpha alpha beta", terms.document("alpha alpha beta"));
        assertEquals("'abc'", terms.query("abc ".repeat(40)));
    }

    @Test
    void given__distinct_token_boundary__when__query__then__at_most_thirty_two_distinct_tokens() {
        String words = IntStream.range(0, 33).mapToObj(index -> "aaa" + (char) ('a' + index / 26)
                + (char) ('a' + index % 26)).collect(Collectors.joining(" "));
        assertDoesNotThrow(() -> terms.query(words.substring(0, words.lastIndexOf(' '))));
        assertThrows(InvalidPostSearchInputException.class, () -> terms.query(words));
    }
}
