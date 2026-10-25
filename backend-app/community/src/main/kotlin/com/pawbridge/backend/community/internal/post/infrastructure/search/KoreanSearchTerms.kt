package com.pawbridge.backend.community.internal.post.infrastructure.search

import com.pawbridge.backend.community.internal.post.application.InvalidPostSearchInputException
import java.io.IOException
import java.util.LinkedHashSet
import org.apache.lucene.analysis.ko.KoreanAnalyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute

class KoreanSearchTerms : AutoCloseable {

    private val analyzer = KoreanAnalyzer()

    fun document(text: String?): String = tokens(text).joinToString(" ")

    private fun tokens(text: String?): List<String> {
        if (text == null || isBlank(text)) {
            return emptyList()
        }
        val tokens = ArrayList<String>()
        try {
            analyzer.tokenStream("text", text).use { stream ->
                val term = stream.addAttribute(CharTermAttribute::class.java)
                stream.reset()
                while (stream.incrementToken()) {
                    tokens.add(term.toString())
                }
                stream.end()
            }
        } catch (failure: IOException) {
            throw IllegalStateException("Korean search analysis failed", failure)
        }
        return tokens
    }

    fun query(text: String?): String {
        if (text == null || isBlank(text)) {
            return ""
        }
        if (text.length > 200 || text.indexOf('\u0000') >= 0) {
            throw InvalidPostSearchInputException()
        }
        val terms = ArrayList(LinkedHashSet(tokens(text)))
        if (terms.size > 32) {
            throw InvalidPostSearchInputException()
        }
        return terms
            .map { term -> "'" + term.replace("\\", "\\\\").replace("'", "''") + "'" }
            .joinToString(" | ")
    }

    override fun close() {
        analyzer.close()
    }

    companion object {
        const val VERSION = "nori-9.12.3-v1"

        // Java String.isBlank semantics (Character.isWhitespace); Kotlin's isBlank also treats NBSP as blank.
        private fun isBlank(value: String): Boolean = value.all { Character.isWhitespace(it) }
    }
}
