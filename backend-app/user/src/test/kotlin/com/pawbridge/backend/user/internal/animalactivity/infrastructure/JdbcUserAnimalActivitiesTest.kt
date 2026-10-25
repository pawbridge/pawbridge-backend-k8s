package com.pawbridge.backend.user.internal.animalactivity.infrastructure

import com.pawbridge.backend.animal.api.AnimalFavoriteCounts
import com.pawbridge.backend.user.internal.animalactivity.application.FavoriteActivityRejectedException
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities.Animal
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities.AnimalLookup
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities.AnimalPage
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities.Favorite
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities.Favorites
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities.PageRequest
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities.Shelter
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Calendar
import java.util.Collections
import java.util.function.Supplier

class JdbcUserAnimalActivitiesTest {
    @Test
    fun given__a_new_favorite__when__adding__then__insert_the_favorite_and_increment_the_count_in_one_write_transaction() {
        val f = Fixture()

        val row = f.service.add(7, -8)

        assertEquals(Favorite(91L, 7L, -8L, NOW), row)
        assertEquals(listOf("begin", "exists", "user", "now", "favorite-insert", "count", "commit", "cleanup"), f.events)
        assertEquals(listOf(listOf<Any>(-8L, 1)), f.adjustments)
        assertTrue(f.jdbc.sql.none { sql -> sql.contains("outbox_events") || sql.contains("FOR UPDATE") })
        assertEquals(0, f.animalCalls)
    }

    @Test
    fun given__a_duplicate_and_missing_user__when__adding__then__reject_duplicate_before_user_lookup_and_no_count_change() {
        val f = Fixture()
        f.jdbc.exists = true
        f.jdbc.user = false

        assertReason("ALREADY_EXISTS") { f.service.add(7, 8) }

        assertEquals(listOf("begin", "exists", "rollback", "cleanup"), f.events)
        assertTrue(f.adjustments.isEmpty())
    }

    @Test
    fun given__a_missing_user__when__adding_or_listing__then__retain_source_user400_before_insert_or_animal() {
        val add = Fixture()
        add.jdbc.user = false
        assertThrows(UserNotFoundException::class.java) { add.service.add(7, 8) }
        assertTrue(add.adjustments.isEmpty())
        assertFalse(add.events.contains("favorite-insert"))
        val list = Fixture()
        list.jdbc.user = false
        assertThrows(UserNotFoundException::class.java) { list.service.favorites(7) }
        assertEquals(0, list.animalCalls)
    }

    @Test
    fun given__a_unique_violation_on_insert__when__adding__then__map_to_409_without_changing_the_count() {
        val f = Fixture()
        f.jdbc.failure = "favorite"

        assertReason("ALREADY_EXISTS") { f.service.add(7, 8) }

        assertTrue(f.adjustments.isEmpty())
        assertTrue(f.events.contains("rollback"))
        assertFalse(f.events.contains("commit"))
        assertEquals("cleanup", f.events.last())
    }

    @Test
    fun given__a_commit_failure__when__adding__then__propagate_it_unchanged_without_retry() {
        val f = Fixture()
        val failure: RuntimeException = DataIntegrityViolationException("synthetic-private")
        f.manager.failure = failure

        assertSame(failure, assertThrows(RuntimeException::class.java) { f.service.add(7, 8) })

        assertEquals(1, Collections.frequency(f.events, "begin"))
        assertEquals("cleanup", f.events.last())
    }

    @Test
    fun given__a_missing_animal__when__adding__then__reject_404_and_roll_back_the_favorite_row() {
        val f = Fixture()
        f.animalExists = false

        assertReason("ANIMAL_NOT_FOUND") { f.service.add(7, 8) }

        assertEquals(listOf("begin", "exists", "user", "now", "favorite-insert", "count", "rollback", "cleanup"), f.events)
        assertEquals(404, FavoriteActivityRejectedException.Reason.ANIMAL_NOT_FOUND.status())
    }

    @Test
    fun given__a_count_failure__when__adding__then__propagate_it_as_is_not_as_409_and_roll_back() {
        val f = Fixture()
        val failure: RuntimeException = DataIntegrityViolationException("synthetic-private")
        f.countFailure = failure

        assertSame(failure, assertThrows(RuntimeException::class.java) { f.service.add(7, 8) })

        assertTrue(f.events.contains("rollback"))
        assertFalse(f.events.contains("commit"))
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1])
    fun given__an_existing_favorite__when__the_bulk_delete_races__then__return_success_and_decrement_only_for_actual_deleted_rows(
        deleted: Int,
    ) {
        val f = Fixture()
        f.jdbc.exists = true
        f.jdbc.deleted = deleted

        f.service.remove(7, 8)

        assertTrue(f.events.contains("delete"))
        assertEquals(if (deleted == 0) listOf() else listOf(listOf<Any>(8L, -1)), f.adjustments)
        assertFalse(f.events.contains("user"))
        assertEquals(0, f.animalCalls)
        assertEquals("commit", f.events[f.events.size - 2])
    }

    @Test
    fun given__a_favorite_of_a_deleted_animal__when__removing__then__still_delete_the_favorite_and_commit() {
        val f = Fixture()
        f.jdbc.exists = true
        f.animalExists = false

        f.service.remove(7, 8)

        assertEquals(listOf("begin", "exists", "delete", "count", "commit", "cleanup"), f.events)
    }

    @Test
    fun given__a_count_failure__when__removing__then__roll_back_the_delete_and_propagate() {
        val f = Fixture()
        f.jdbc.exists = true
        val failure: RuntimeException = IllegalStateException("private")
        f.countFailure = failure

        assertSame(failure, assertThrows(RuntimeException::class.java) { f.service.remove(7, 8) })

        assertTrue(f.events.contains("rollback"))
        assertFalse(f.events.contains("commit"))
    }

    @Test
    fun given__no_favorite__when__removing__then__return_not_found_without_delete_or_user_lookup() {
        val f = Fixture()

        assertReason("NOT_FOUND") { f.service.remove(7, 8) }

        assertEquals(listOf("begin", "exists", "rollback", "cleanup"), f.events)
        assertTrue(f.adjustments.isEmpty())
    }

    @Test
    fun given__no_favorites__when__listing__then__return_empty_without_animal_http() {
        val f = Fixture()

        val result = f.service.favorites(7)

        assertEquals(Favorites(7L, 0, listOf()), result)
        assertEquals(0, f.animalCalls)
        assertTrue(f.manager.readOnly)
    }

    @Test
    fun given__ordered_favorites_and_an_absent_animal__when__joining_one_batch__then__preserve_order_and_exact_deleted_fallback() {
        val f = Fixture()
        f.jdbc.favoriteRows = listOf(8L, 9L)
        f.batch = listOf(animal(8))

        val result = f.service.favorites(7)

        assertEquals(listOf(8L, 9L), result.favorites!!.map { favorite -> favorite.animalId })
        assertEquals("DOG", result.favorites!!.first().species)
        assertEquals("DELETED", result.favorites!!.last().status)
        assertNull(result.favorites!!.last().breed)
        assertEquals(1, f.animalCalls)
        assertEquals(listOf(8L, 9L), f.batchIds)
        assertTrue(f.jdbc.sql.last().endsWith("ORDER BY created_at DESC"))
    }

    @Test
    fun given__favorites__when__listing__then__read_the_animal_batch_only_after_the_user_read_transaction_completed() {
        val f = Fixture()
        f.jdbc.favoriteRows = listOf(8L)
        f.batch = listOf(animal(8))

        f.service.favorites(7)

        assertEquals(listOf("begin", "user", "favorites", "commit", "cleanup"), f.events)
        assertEquals(1, f.animalCalls)
    }

    @ParameterizedTest
    @ValueSource(strings = ["http", "duplicate", "null"])
    fun given__a_batch_failure_or_duplicate_identity__when__listing__then__propagate_without_turning_failure_into_deleted_animals(
        phase: String,
    ) {
        val f = Fixture()
        f.jdbc.favoriteRows = listOf(8L)
        f.batchFailure = phase == "http"
        f.batch = if (phase == "null") null else listOf(animal(8), animal(8))

        assertThrows(RuntimeException::class.java) { f.service.favorites(7) }

        assertEquals(1, f.animalCalls)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "ROLE_USER", "ROLE_ADMIN", "no-registration"])
    fun given__missing_or_non_shelter_database_membership__when__reading_registered_animals__then__preserve_source_failure_before_animal_http(
        state: String,
    ) {
        val f = Fixture()
        if (state == "missing") {
            f.jdbc.user = false
        } else if (state == "no-registration") {
            f.jdbc.care = ""
        } else {
            f.jdbc.role = state
        }

        if (state == "missing") {
            assertThrows(UserNotFoundException::class.java) { f.service.registered(7, request()) }
        } else {
            assertThrows(IllegalStateException::class.java) { f.service.registered(7, request()) }
        }
        assertEquals(0, f.animalCalls)
    }

    @Test
    fun given__shelter_membership__when__reading_registered_animals__then__run_both_lookups_after_the_readonly_transaction_and_forward_raw_sort() {
        val f = Fixture()

        val result = f.service.registered(7, request())

        assertSame(f.page, result)
        assertEquals(request(), f.request)
        // Animal lookups are independent transactions; nesting them would hold two connections of the shared pool.
        assertEquals(listOf("begin", "current", "commit", "cleanup", "shelter", "animals"), f.events)
        assertTrue(f.manager.readOnly)
    }

    @ParameterizedTest
    @ValueSource(strings = ["shelter", "animals"])
    fun given__external_lookup_failure__when__reading_registered_animals__then__propagate_generic_runtime_and_no_empty_page(
        phase: String,
    ) {
        val f = Fixture()
        f.externalFailure = phase

        assertThrows(IllegalStateException::class.java) { f.service.registered(7, request()) }

        assertEquals(listOf("begin", "current", "commit", "cleanup"), f.events.subList(0, 4))
    }

    private fun request(): PageRequest {
        return PageRequest(3, 20, "name,desc,createdAt,asc")
    }

    private fun animal(id: Long): Animal {
        return Animal(
            id, "notice", "DOG", "breed", "UNKNOWN", 2025, 1, "mark", "PROTECT",
            LocalDate.of(2026, 11, 1), "https://fixture.invalid/image", 2, 4L, "shelter", NOW,
        )
    }

    private fun assertReason(reason: String, action: () -> Unit) {
        assertEquals(
            reason,
            assertThrows(FavoriteActivityRejectedException::class.java) { action() }.reason().name,
        )
    }

    private class Fixture {
        val events = ArrayList<String>()
        val jdbc = FakeJdbc(events)
        val manager = Manager(events)
        val service: JdbcUserAnimalActivities
        var batch: List<Animal>? = listOf()
        var batchIds: List<Long?>? = null
        var batchFailure = false
        var externalFailure = ""
        var animalCalls = 0
        var animalExists = true
        var countFailure: RuntimeException? = null
        val adjustments = ArrayList<List<Any>>()
        var request: PageRequest? = null
        val page = AnimalPage(listOf(), 0, 20, 0, 0, true)

        init {
            val write = TransactionTemplate(manager)
            val read = TransactionTemplate(manager)
            read.isReadOnly = true
            service = JdbcUserAnimalActivities(
                jdbc,
                write,
                read,
                object : AnimalLookup {
                    override fun batch(ids: List<Long?>): List<Animal>? {
                        animalCalls++
                        batchIds = ids
                        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
                        if (batchFailure) {
                            throw IllegalStateException("private")
                        }
                        return batch
                    }

                    override fun shelter(careRegNo: String?): Shelter? {
                        events.add("shelter")
                        animalCalls++
                        assertEquals(" reg ", careRegNo)
                        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
                        if (externalFailure == "shelter") {
                            throw IllegalStateException("private")
                        }
                        return Shelter(4L, "name")
                    }

                    override fun byShelter(shelterId: Long?, request: PageRequest): AnimalPage? {
                        events.add("animals")
                        animalCalls++
                        this@Fixture.request = request
                        if (externalFailure == "animals") {
                            throw IllegalStateException("private")
                        }
                        assertEquals(4L, shelterId)
                        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
                        return page
                    }
                },
                object : AnimalFavoriteCounts {
                    override fun adjust(animalId: Long, delta: Int): Boolean {
                        events.add("count")
                        adjustments.add(listOf(animalId, delta))
                        if (countFailure != null) {
                            throw countFailure!!
                        }
                        return animalExists
                    }
                },
                Supplier {
                    events.add("now")
                    NOW
                },
            )
        }
    }

    private class FakeJdbc(private val events: MutableList<String>) : JdbcTemplate() {
        val sql = ArrayList<String>()
        var exists = false
        var user = true
        var deleted = 1
        var failure = ""
        var role = "ROLE_SHELTER"
        var care = " reg "
        var favoriteRows: List<Long> = listOf()

        override fun <T> queryForObject(query: String, requiredType: Class<T>, vararg args: Any?): T? {
            sql.add(query)
            if (query.startsWith("INSERT")) {
                events.add("favorite-insert")
                if (failure == "favorite") {
                    throw DataIntegrityViolationException("private")
                }
                return requiredType.cast(91L)
            }
            val users = query.contains("FROM pawbridge_user.users")
            events.add(if (users) "user" else "exists")
            return requiredType.cast(if (users) user else exists)
        }

        override fun update(query: String, vararg args: Any?): Int {
            sql.add(query)
            if (query.startsWith("DELETE")) {
                events.add("delete")
                return deleted
            }
            throw AssertionError("unexpected update: " + query)
        }

        override fun <T> query(query: String, rowMapper: RowMapper<T>, vararg args: Any?): MutableList<T> {
            sql.add(query)
            try {
                if (query.contains("FROM pawbridge_user.users")) {
                    events.add("current")
                    if (!user) {
                        return ArrayList()
                    }
                    val rs = mock(ResultSet::class.java)
                    `when`(rs.getString("role")).thenReturn(role)
                    `when`(rs.getString("care_reg_no")).thenReturn(care)
                    return arrayListOf(rowMapper.mapRow(rs, 0)!!)
                }
                events.add("favorites")
                val result = ArrayList<T>()
                for (id in favoriteRows) {
                    val rs = mock(ResultSet::class.java)
                    `when`(rs.getLong("favorite_id")).thenReturn(id + 100)
                    `when`(rs.getLong("user_id")).thenReturn(7L)
                    `when`(rs.getLong("animal_id")).thenReturn(id)
                    `when`(rs.getTimestamp(eq("created_at"), any(Calendar::class.java))).thenReturn(Timestamp.valueOf(NOW))
                    result.add(rowMapper.mapRow(rs, result.size)!!)
                }
                return result
            } catch (e: SQLException) {
                throw IllegalStateException(e)
            }
        }
    }

    private class Manager(private val events: MutableList<String>) : AbstractPlatformTransactionManager() {
        var readOnly = false
        var failure: RuntimeException? = null

        override fun doGetTransaction(): Any {
            return Any()
        }

        override fun doBegin(tx: Any, definition: TransactionDefinition) {
            readOnly = definition.isReadOnly
            events.add("begin")
        }

        override fun doCommit(status: DefaultTransactionStatus) {
            events.add("commit")
            if (failure != null) {
                throw failure!!
            }
        }

        override fun doRollback(status: DefaultTransactionStatus) {
            events.add("rollback")
        }

        override fun doCleanupAfterCompletion(tx: Any) {
            events.add("cleanup")
        }
    }

    companion object {
        private val NOW: LocalDateTime = LocalDateTime.parse("2026-10-20T01:02:03.123456789")
    }
}
