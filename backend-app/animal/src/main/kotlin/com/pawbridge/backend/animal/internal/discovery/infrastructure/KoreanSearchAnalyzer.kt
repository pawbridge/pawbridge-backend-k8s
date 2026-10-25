package com.pawbridge.backend.animal.internal.discovery.infrastructure

import org.apache.lucene.analysis.ko.KoreanAnalyzer
import org.apache.lucene.analysis.ko.KoreanTokenizer
import org.apache.lucene.analysis.ko.POS
import org.apache.lucene.analysis.ko.dict.UserDictionary
import org.apache.lucene.analysis.ko.tokenattributes.PartOfSpeechAttribute
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute
import java.io.IOException
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.util.HexFormat

/** Versioned text analysis shared by the opt-in PostgreSQL projector and quality evaluations. */
class KoreanSearchAnalyzer @Throws(IOException::class) constructor() : AutoCloseable {
    private val analyzer: KoreanAnalyzer
    private val relationAnalyzer: KoreanAnalyzer

    init {
        // Reuse the repository's breed/POS vocabulary. DISCARD avoids requiring both
        // a compound and its components in an AND query. This is not ES score parity.
        val breeds = UserDictionary.open(
            StringReader(
                java.lang.String.join(
                    "\n",
                    "믹스견", "시츄", "말티즈", "푸들", "치와와", "포메라니안", "요크셔테리어",
                    "비글", "웰시코기", "리트리버", "진돗개", "삽살개",
                ),
            ),
        )
        val stopTags: Set<POS.Tag> = java.util.Set.of(
            POS.Tag.E, POS.Tag.IC, POS.Tag.J, POS.Tag.MAG, POS.Tag.MAJ, POS.Tag.MM,
            POS.Tag.SP, POS.Tag.SSC, POS.Tag.SSO, POS.Tag.SC, POS.Tag.SE, POS.Tag.XPN, POS.Tag.XSA,
            POS.Tag.XSN, POS.Tag.XSV, POS.Tag.UNA, POS.Tag.NA, POS.Tag.VSV,
        )
        analyzer = KoreanAnalyzer(breeds, KoreanTokenizer.DecompoundMode.DISCARD, stopTags, false)
        relationAnalyzer = KoreanAnalyzer(breeds, KoreanTokenizer.DecompoundMode.DISCARD, java.util.Set.of(), false)
    }

    @Throws(IOException::class)
    fun tokens(value: String?): List<String> {
        if (value == null || isBlank(value)) {
            return listOf()
        }
        val tokens = ArrayList<String>()
        analyzer.tokenStream("text", value).use { stream ->
            val term = stream.addAttribute(CharTermAttribute::class.java)
            stream.reset()
            while (stream.incrementToken()) {
                val token = term.toString()
                // Generic color suffix must not make all color names match each other.
                if (token == "색") {
                    continue
                }
                // Bounded domain synonyms, not a general Korean stemmer. Nori emits
                // different lemmas for 접힘/접힌 and 좋아함/좋아해요. Apply on both sides.
                tokens.add(normalize(token))
            }
            stream.end()
        }
        return tokens
    }

    /** Shared analysis for transactional writes and bounded document rebuilds. */
    @Throws(IOException::class)
    fun animalTerms(breed: String?, color: String?, mark: String?, description: String?, place: String?): AnimalTerms {
        val relations = ArrayList(relations(mark))
        relations.addAll(relations(description))
        return AnimalTerms(
            join(mark),
            join(breed),
            join(color),
            join(description),
            join(place),
            relations.joinToString(" "),
        )
    }

    @Throws(IOException::class)
    fun shelterTerms(name: String?, address: String?): ShelterTerms {
        return ShelterTerms(join(name), join(address))
    }

    @Throws(IOException::class)
    private fun join(text: String?): String = tokens(text).joinToString(" ")

    @JvmRecord
    data class AnimalTerms(
        val mark: String,
        val breed: String,
        val color: String,
        val description: String,
        val place: String,
        val relations: String,
    )

    @JvmRecord
    data class ShelterTerms(
        val name: String,
        val address: String,
    )

    /** Recognize only a simple noun + predicate query, not arbitrary natural language. */
    @Throws(IOException::class)
    fun queryRelation(value: String?): Relation? {
        val core = morphemes(value).filter { part -> part.noun() || part.predicate() }
        if (core.size != 2 || !core[0].noun() || !core[1].predicate()) {
            return null
        }
        val noun = normalize(core[0].term)
        val predicate = normalize(core[1].term)
        val evidence = relations(value)
        if (evidence.contains(relationKey(noun, predicate, true))) {
            return Relation(noun, predicate, true)
        }
        if (evidence.contains(relationKey(noun, predicate, false))) {
            return Relation(noun, predicate, false)
        }
        return null
    }

    /**
     * Field-local lexical evidence. Other predicates and clause boundaries close the scope.
     * This is a bounded Korean search rule, not a dependency parser or semantic classifier.
     */
    @Throws(IOException::class)
    fun relations(value: String?): Set<String> {
        val result = LinkedHashSet<String>()
        val scope = PredicateScope(result)
        var previousEnd = 0
        for (part in morphemes(value)) {
            if (part.start > previousEnd && value!!.substring(previousEnd, part.start).matches(CLAUSE_BOUNDARY)) {
                scope.finish()
            }
            previousEnd = Math.max(previousEnd, part.end)
            if (part.pos == POS.Tag.E && CLAUSE_ENDINGS.contains(part.term)) {
                scope.finish()
            } else if (part.noun()) {
                if (scope.predicate != null) {
                    scope.finish()
                }
                scope.nouns.add(normalize(part.term))
            } else if ((part.pos == POS.Tag.MAG && NEGATIVE_ADVERBS.contains(part.term)) ||
                (part.pos == POS.Tag.VX && part.term == "않") ||
                part.pos == POS.Tag.VCN
            ) {
                scope.negative = true
            } else if (part.predicate()) {
                if (scope.predicate != null) {
                    scope.finish()
                }
                scope.predicate = normalize(part.term)
            }
        }
        scope.finish()
        return result
    }

    @Throws(IOException::class)
    private fun morphemes(value: String?): List<Morpheme> {
        if (value == null || isBlank(value)) {
            return listOf()
        }
        val result = ArrayList<Morpheme>()
        relationAnalyzer.tokenStream("text", value).use { stream ->
            val term = stream.addAttribute(CharTermAttribute::class.java)
            val pos = stream.addAttribute(PartOfSpeechAttribute::class.java)
            val offset = stream.addAttribute(OffsetAttribute::class.java)
            stream.reset()
            while (stream.incrementToken()) {
                result.add(Morpheme(term.toString(), pos.leftPOS, offset.startOffset(), offset.endOffset()))
            }
            stream.end()
        }
        return result
    }

    @JvmRecord
    data class Relation(
        val noun: String,
        val predicate: String,
        val negative: Boolean,
    ) {
        fun key(): String = relationKey(noun, predicate, negative)

        fun tokens(): List<String> = java.util.List.of(noun, predicate)
    }

    private class Morpheme(
        val term: String,
        val pos: POS.Tag,
        val start: Int,
        val end: Int,
    ) {
        fun noun(): Boolean = pos == POS.Tag.NNG || pos == POS.Tag.NNP || pos == POS.Tag.NP

        fun predicate(): Boolean = pos == POS.Tag.VV || pos == POS.Tag.VA
    }

    private class PredicateScope(
        private val result: MutableSet<String>,
    ) {
        val nouns: MutableSet<String> = LinkedHashSet()
        var predicate: String? = null
        var negative: Boolean = false

        fun finish() {
            val current = predicate
            if (current != null) {
                for (noun in nouns) {
                    result.add(relationKey(noun, current, negative))
                }
            }
            nouns.clear()
            predicate = null
            negative = false
        }
    }

    override fun close() {
        analyzer.close()
        relationAnalyzer.close()
    }

    companion object {
        const val VERSION: String = "nori-9.12.3-pawbridge-v2"

        private val CLAUSE_BOUNDARY = Regex("(?s).*[.!?;/,\\r\\n].*")

        // Set.of keeps the original null rejection on contains().
        private val CLAUSE_ENDINGS: Set<String> =
            java.util.Set.of("고", "며", "지만", "는데", "음", "ㅁ", "다", "아요", "어요", "습니다")
        private val NEGATIVE_ADVERBS: Set<String> = java.util.Set.of("안", "못")

        // Java String.isBlank(): empty or only Character.isWhitespace characters (Kotlin isBlank also accepts NBSP).
        private fun isBlank(value: String): Boolean = value.all { Character.isWhitespace(it) }

        private fun normalize(term: String): String {
            return when (term) {
                "접힘" -> "접히"
                "좋아하" -> "좋"
                else -> term
            }
        }

        private fun relationKey(noun: String, predicate: String, negative: Boolean): String {
            // A single safe tsvector lexeme; separators cannot collide with source words.
            return "r" + HexFormat.of().formatHex((noun + "\u0000" + predicate + "\u0000" + negative).toByteArray(StandardCharsets.UTF_8))
        }
    }
}
