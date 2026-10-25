package com.pawbridge.backend.animal.internal.recommendation.infrastructure

import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.AnimalStatus
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Gender
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Species
import com.pawbridge.backend.animal.internal.recommendation.application.AnimalRecommendations
import com.pawbridge.backend.animal.internal.recommendation.application.AnimalRecommendations.Card
import com.pawbridge.backend.animal.internal.recommendation.application.RecommendationUnavailableException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.sql.SQLException
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.function.Function
import java.util.stream.Collectors
import javax.sql.DataSource

/** No enclosing transaction: the source read ends before Python, then candidates are read once. */
class JdbcAnimalRecommendations : AnimalRecommendations {
    private val animals: AnimalDiscovery
    private val python: PythonAnimalRecommendations
    private val jdbc: NamedParameterJdbcOperations
    private val reads: TransactionOperations

    constructor(
        animals: AnimalDiscovery,
        python: PythonAnimalRecommendations,
        source: DataSource,
        manager: PlatformTransactionManager,
    ) {
        this.animals = animals
        this.python = python
        val template = JdbcTemplate(source)
        template.queryTimeout = 5
        this.jdbc = NamedParameterJdbcTemplate(template)
        val transaction = TransactionTemplate(manager)
        transaction.isReadOnly = true
        transaction.timeout = 5
        this.reads = transaction
    }

    // Package-private in Java; public on the JVM here because Kotlin has no package-private.
    internal constructor(
        animals: AnimalDiscovery,
        python: PythonAnimalRecommendations,
        jdbc: NamedParameterJdbcOperations,
        reads: TransactionOperations,
    ) {
        this.animals = animals
        this.python = python
        this.jdbc = jdbc
        this.reads = reads
    }

    override fun recommend(animalId: Long): List<Card> {
        val source = animals.findById(animalId)
        if (source.species != Species.DOG && source.species != Species.CAT) {
            return listOf()
        }
        val ids: List<Long?>? = try {
            python.recommend(animalId, source.species.name)
        } catch (failure: Exception) {
            throw RecommendationUnavailableException()
        }
        if (ids == null || ids.size > 6 || ids.any { id -> id == null || id <= 0 }) {
            throw RecommendationUnavailableException()
        }
        // Stream.toList() returned an unmodifiable list; List.copyOf keeps that for the ids bound to the query.
        val candidates: List<Long> = java.util.List.copyOf(
            ids.filterNotNull().filter { id -> id != animalId }.distinct().take(6),
        )
        if (candidates.isEmpty()) {
            return listOf()
        }
        val current: Map<Long?, Card> = reads.execute {
            jdbc.query(
                CANDIDATES,
                MapSqlParameterSource("ids", candidates),
                RowMapper { row, number -> card(row, number) },
            ).stream().collect(Collectors.toMap(Function<Card, Long?> { card -> card.id }, Function.identity()))
        }!!
        return java.util.List.copyOf(
            candidates
                .mapNotNull { id -> current[id] }
                .filter { card -> card.species == source.species }
                .filter { card -> card.status == AnimalStatus.NOTICE || card.status == AnimalStatus.PROTECT },
        )
    }

    private companion object {
        private const val CANDIDATES = "SELECT a.id,a.apms_notice_no,a.species,a.breed,a.gender," +
            "a.birth_year,a.weight,a.color,a.happen_date,a.special_mark,a.status,a.notice_end_date," +
            "a.image_url,a.favorite_count,a.created_at,s.id AS shelter_id,s.name AS shelter_name " +
            "FROM pawbridge_animal.animals a JOIN pawbridge_animal.shelters s ON s.id=a.shelter_id " +
            "WHERE a.id IN (:ids)"

        @Throws(SQLException::class)
        @Suppress("UNUSED_PARAMETER")
        private fun card(row: ResultSet, number: Int): Card {
            val birthYear = row.getObject("birth_year", Int::class.javaObjectType)
            return Card(
                row.getObject("id", Long::class.javaObjectType),
                row.getString("apms_notice_no"),
                Species.valueOf(row.getString("species")),
                row.getString("breed"),
                Gender.valueOf(row.getString("gender")),
                birthYear,
                if (birthYear == null) null else LocalDate.now().year - birthYear,
                row.getString("weight"),
                row.getString("color"),
                row.getObject("happen_date", LocalDate::class.java),
                row.getString("special_mark"),
                AnimalStatus.valueOf(row.getString("status")),
                row.getObject("notice_end_date", LocalDate::class.java),
                row.getString("image_url"),
                row.getObject("favorite_count", Int::class.javaObjectType),
                row.getObject("shelter_id", Long::class.javaObjectType),
                row.getString("shelter_name"),
                row.getObject("created_at", LocalDateTime::class.java),
            )
        }
    }
}
