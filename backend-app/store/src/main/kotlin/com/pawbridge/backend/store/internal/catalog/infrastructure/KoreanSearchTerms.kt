package com.pawbridge.backend.store.internal.catalog.infrastructure

import org.apache.lucene.analysis.ko.KoreanAnalyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import java.io.IOException

class KoreanSearchTerms : AutoCloseable {

    private val analyzer = KoreanAnalyzer()

    fun document(text: String?): String = tokens(text).joinToString(" ")

    fun query(text: String?): String {
        // Java String.isBlank semantics (Character.isWhitespace); Kotlin's isBlank also treats NBSP as blank.
        if (text == null || text.all { Character.isWhitespace(it) }) {
            return ""
        }
        if (text.length > 200 || text.indexOf('\u0000') >= 0) {
            throw IllegalArgumentException("검색어는 200자 이내여야 합니다.")
        }
        val terms = ArrayList(LinkedHashSet(tokens(text)))
        if (terms.size > 32) {
            throw IllegalArgumentException("검색 단어가 너무 많습니다.")
        }
        // Match any analyzed term, as the existing ES multi_match default does.
        return terms
            .map { term -> "'" + term.replace("\\", "\\\\").replace("'", "''") + "'" }
            .joinToString(" | ")
    }

    private fun tokens(text: String?): List<String> {
        if (text == null || text.all { Character.isWhitespace(it) }) {
            return listOf()
        }
        val result = ArrayList<String>()
        try {
            analyzer.tokenStream("text", text).use { stream ->
                val term = stream.addAttribute(CharTermAttribute::class.java)
                stream.reset()
                while (stream.incrementToken()) {
                    result.add(term.toString())
                }
                stream.end()
            }
        } catch (failure: IOException) {
            throw IllegalStateException("Korean search analysis failed", failure)
        }
        return result
    }

    override fun close() {
        analyzer.close()
    }

    companion object {
        const val VERSION = "nori-9.12.3-v1"
    }
}
