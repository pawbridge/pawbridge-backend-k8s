package com.pawbridge.backend.user.internal.animalactivity.infrastructure

import com.pawbridge.backend.animal.api.AnimalFavoriteCounts
import com.pawbridge.backend.user.internal.animalactivity.application.FavoriteActivityRejectedException
import com.pawbridge.backend.user.internal.animalactivity.application.FavoriteActivityRejectedException.Reason
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities.Animal
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities.AnimalLookup
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities.AnimalPage
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities.Favorite
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities.FavoriteAnimal
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities.Favorites
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities.PageRequest
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities.Shelter
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.support.SqlValue
import org.springframework.transaction.support.TransactionOperations
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.Calendar
import java.util.TimeZone
import java.util.function.Function
import java.util.function.Supplier
import java.util.stream.Collectors

class JdbcUserAnimalActivities(
    private val jdbc: JdbcOperations,
    private val write: TransactionOperations,
    private val read: TransactionOperations,
    private val animals: AnimalLookup,
    private val counts: AnimalFavoriteCounts,
    private val now: Supplier<LocalDateTime?>,
) : UserAnimalActivities {

    constructor(
        jdbc: JdbcOperations,
        write: TransactionOperations,
        read: TransactionOperations,
        animals: AnimalLookup,
        counts: AnimalFavoriteCounts,
    ) : this(jdbc, write, read, animals, counts, Supplier { LocalDateTime.now() })

    /** The favorite row and the animal's count commit or roll back together; an absent animal rejects the add. */
    override fun add(currentUserId: Long, animalId: Long): Favorite {
        return write.execute {
            if (exists(currentUserId, animalId)) {
                throw FavoriteActivityRejectedException(Reason.ALREADY_EXISTS)
            }
            requireUser(currentUserId)
            val created: LocalDateTime? = now.get()
            val id: Long? = try {
                jdbc.queryForObject(
                    INSERT_FAVORITE,
                    Long::class.javaObjectType,
                    currentUserId,
                    animalId,
                    timestamp(created),
                )
            } catch (conflict: DataIntegrityViolationException) {
                throw FavoriteActivityRejectedException(Reason.ALREADY_EXISTS)
            }
            if (!counts.adjust(animalId, 1)) {
                throw FavoriteActivityRejectedException(Reason.ANIMAL_NOT_FOUND)
            }
            // Auditing's in-memory LocalDateTime is returned before any DB precision refresh, as in JPA.
            Favorite(id, currentUserId, animalId, created)
        }!!
    }

    /** A favorite of an animal that no longer exists can still be removed; the count update is then skipped. */
    override fun remove(currentUserId: Long, animalId: Long) {
        write.executeWithoutResult {
            if (!exists(currentUserId, animalId)) {
                throw FavoriteActivityRejectedException(Reason.NOT_FOUND)
            }
            val deleted = jdbc.update(DELETE_FAVORITE, currentUserId, animalId)
            if (deleted > 0) {
                counts.adjust(animalId, -1)
            }
        }
    }

    // Animal lookups run after the User read transaction has returned its connection. They were independent
    // transactions on a separate pool; nesting them here would hold two connections of the one shared pool.
    override fun favorites(currentUserId: Long): Favorites {
        val favorites: List<Favorite> = read.execute {
            requireUser(currentUserId)
            jdbc.query(
                SELECT_FAVORITES,
                RowMapper { rs, _ ->
                    val value: Timestamp? = rs.getTimestamp("created_at", utc())
                    Favorite(
                        rs.getLong("favorite_id"),
                        rs.getLong("user_id"),
                        rs.getLong("animal_id"),
                        value?.toLocalDateTime(),
                    )
                },
                currentUserId,
            )
        }!!
        if (favorites.isEmpty()) {
            return Favorites(currentUserId, 0, listOf())
        }
        val found = animals.batch(favorites.map { it.animalId })!!
        // Collectors.toMap keeps the original duplicate-key failure (IllegalStateException).
        val byId: Map<Long?, Animal> = found.stream().collect(
            Collectors.toMap<Animal, Long?, Animal>(Function { animal -> animal.id }, Function.identity()),
        )
        val content = favorites.map { favorite -> favoriteAnimal(favorite, byId[favorite.animalId]) }
        return Favorites(currentUserId, content.size, content)
    }

    override fun registered(currentUserId: Long, request: PageRequest): AnimalPage {
        val user: CurrentUser = read.execute {
            val users = jdbc.query(
                SELECT_ROLE,
                RowMapper { rs, _ -> CurrentUser(rs.getString("role"), rs.getString("care_reg_no")) },
                currentUserId,
            )
            if (users.isEmpty()) {
                throw UserNotFoundException()
            }
            val found = users.first()
            // The source UnauthorizedException is a RuntimeException handled by its generic 500 advice.
            if ("ROLE_SHELTER" != found.role) {
                throw IllegalStateException("Shelter membership is required")
            }
            if (found.careRegNo == null || found.careRegNo.isEmpty()) {
                throw IllegalStateException("Shelter registration is missing")
            }
            found
        }!!
        val shelter: Shelter
        try {
            shelter = animals.shelter(user.careRegNo) ?: throw IllegalStateException("Shelter response missing")
        } catch (unavailable: Exception) {
            throw IllegalStateException("Shelter lookup failed", unavailable)
        }
        try {
            if (shelter.id == null) {
                throw IllegalStateException("Shelter identity missing")
            }
            return animals.byShelter(shelter.id, request) ?: throw IllegalStateException("Animal page missing")
        } catch (unavailable: Exception) {
            throw IllegalStateException("Animal page lookup failed", unavailable)
        }
    }

    private fun favoriteAnimal(favorite: Favorite, animal: Animal?): FavoriteAnimal {
        if (animal == null) {
            return FavoriteAnimal(
                favorite.favoriteId,
                favorite.userId,
                favorite.animalId,
                favorite.createdAt,
                null,
                null,
                null,
                null,
                null,
                null,
                "DELETED",
                null,
                null,
            )
        }
        return FavoriteAnimal(
            favorite.favoriteId,
            favorite.userId,
            favorite.animalId,
            favorite.createdAt,
            animal.breed,
            animal.species,
            animal.gender,
            animal.age,
            animal.imageUrl,
            animal.shelterName,
            animal.status,
            animal.apmsNoticeNo,
            animal.specialMark,
        )
    }

    // `!!` keeps the NullPointerException of the Java auto-unboxing when the query returns no value.
    private fun exists(currentUserId: Long, animalId: Long): Boolean =
        jdbc.queryForObject(EXISTS_FAVORITE, Boolean::class.javaObjectType, currentUserId, animalId)!!

    private fun requireUser(currentUserId: Long) {
        if (!jdbc.queryForObject(EXISTS_USER, Boolean::class.javaObjectType, currentUserId)!!) {
            throw UserNotFoundException()
        }
    }

    private data class CurrentUser(val role: String?, val careRegNo: String?)

    private companion object {
        private const val INSERT_FAVORITE =
            "INSERT INTO pawbridge_user.favorites(user_id,animal_id,created_at) VALUES(?,?,?) RETURNING favorite_id"
        private const val DELETE_FAVORITE = "DELETE FROM pawbridge_user.favorites WHERE user_id=? AND animal_id=?"
        private const val SELECT_FAVORITES =
            "SELECT favorite_id,user_id,animal_id,created_at FROM pawbridge_user.favorites " +
                "WHERE user_id=? ORDER BY created_at DESC"
        private const val SELECT_ROLE = "SELECT role,care_reg_no FROM pawbridge_user.users WHERE user_id=?"
        private const val EXISTS_FAVORITE =
            "SELECT EXISTS(SELECT 1 FROM pawbridge_user.favorites WHERE user_id=? AND animal_id=?)"
        private const val EXISTS_USER = "SELECT EXISTS(SELECT 1 FROM pawbridge_user.users WHERE user_id=?)"

        private fun utc(): Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))

        private fun timestamp(value: LocalDateTime?): SqlValue = object : SqlValue {
            override fun setValue(statement: PreparedStatement, index: Int) {
                statement.setTimestamp(index, Timestamp.valueOf(value), utc())
            }

            override fun cleanup() {
            }
        }
    }
}
