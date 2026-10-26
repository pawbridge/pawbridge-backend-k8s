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
import com.pawbridge.backend.user.internal.animalactivity.application.ShelterMembershipRequiredException
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
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.lang.reflect.Proxy
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Collections
import java.util.function.Supplier

/** A tiny in-memory row: ResultSet has too many overloads to stub one by one with strict mocks. */
private fun rowOf(
    strings: Map<String, String?> = mapOf(),
    longs: Map<String, Long> = mapOf(),
    timestamps: Map<String, Timestamp> = mapOf(),
): ResultSet {
    return Proxy.newProxyInstance(
        ResultSet::class.java.classLoader,
        arrayOf(ResultSet::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getString" -> strings.getValue(args[0] as String)
            "getLong" -> longs.getValue(args[0] as String)
            "getTimestamp" -> timestamps.getValue(args[0] as String)
            else -> throw UnsupportedOperationException(method.name)
        }
    } as ResultSet
}

class JdbcUserAnimalActivitiesTest {
    @Test
    fun `새 즐겨찾기일 때__추가하면__한 쓰기 트랜잭션에서 즐겨찾기를 넣고 건수를 올린다`() {
        // given
        val f = Fixture()

        // when
        val row = f.service.add(7, -8)

        // then
        assertEquals(Favorite(91L, 7L, -8L, NOW), row)
        assertEquals(listOf("begin", "exists", "user", "now", "favorite-insert", "count", "commit", "cleanup"), f.events)
        assertEquals(listOf(listOf<Any>(-8L, 1)), f.adjustments)
        assertTrue(f.jdbc.sql.none { sql -> sql.contains("outbox_events") || sql.contains("FOR UPDATE") })
        assertEquals(0, f.animalCalls)
    }

    @Test
    fun `이미 있는 즐겨찾기이고 사용자가 없을 때__추가하면__사용자 조회 전에 중복으로 거부하고 건수를 바꾸지 않는다`() {
        // given
        val f = Fixture()
        f.jdbc.exists = true
        f.jdbc.user = false

        // when
        assertReason("ALREADY_EXISTS") { f.service.add(7, 8) }

        // then
        assertEquals(listOf("begin", "exists", "rollback", "cleanup"), f.events)
        assertTrue(f.adjustments.isEmpty())
    }

    @Test
    fun `사용자가 없을 때__추가하거나 목록을 조회하면__삽입이나 동물 조회 전에 원래의 사용자 400을 유지한다`() {
        // given
        val add = Fixture()
        add.jdbc.user = false
        val list = Fixture()
        list.jdbc.user = false

        // when
        assertThrows(UserNotFoundException::class.java) { add.service.add(7, 8) }
        assertThrows(UserNotFoundException::class.java) { list.service.favorites(7) }

        // then
        assertTrue(add.adjustments.isEmpty())
        assertFalse(add.events.contains("favorite-insert"))
        assertEquals(0, list.animalCalls)
    }

    @Test
    fun `삽입에서 유일성 위반이 날 때__추가하면__건수를 바꾸지 않고 409로 매핑한다`() {
        // given
        val f = Fixture()
        f.jdbc.failure = "favorite"

        // when
        assertReason("ALREADY_EXISTS") { f.service.add(7, 8) }

        // then
        assertTrue(f.adjustments.isEmpty())
        assertTrue(f.events.contains("rollback"))
        assertFalse(f.events.contains("commit"))
        assertEquals("cleanup", f.events.last())
    }

    @Test
    fun `커밋이 실패할 때__추가하면__재시도 없이 그대로 전파한다`() {
        // given
        val f = Fixture()
        val failure: RuntimeException = DataIntegrityViolationException("synthetic-private")
        f.manager.failure = failure

        // when
        assertSame(failure, assertThrows(RuntimeException::class.java) { f.service.add(7, 8) })

        // then
        assertEquals(1, Collections.frequency(f.events, "begin"))
        assertEquals("cleanup", f.events.last())
    }

    @Test
    fun `동물이 없을 때__추가하면__404로 거부하고 즐겨찾기 행을 롤백한다`() {
        // given
        val f = Fixture()
        f.animalExists = false

        // when
        assertReason("ANIMAL_NOT_FOUND") { f.service.add(7, 8) }

        // then
        assertEquals(listOf("begin", "exists", "user", "now", "favorite-insert", "count", "rollback", "cleanup"), f.events)
        assertEquals(404, FavoriteActivityRejectedException.Reason.ANIMAL_NOT_FOUND.status())
    }

    @Test
    fun `건수 반영이 실패할 때__추가하면__409가 아니라 그대로 전파하고 롤백한다`() {
        // given
        val f = Fixture()
        val failure: RuntimeException = DataIntegrityViolationException("synthetic-private")
        f.countFailure = failure

        // when
        assertSame(failure, assertThrows(RuntimeException::class.java) { f.service.add(7, 8) })

        // then
        assertTrue(f.events.contains("rollback"))
        assertFalse(f.events.contains("commit"))
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1])
    fun `즐겨찾기가 있고 일괄 삭제가 경합할 때__삭제하면__성공을 돌려주고 실제 삭제된 행만큼만 줄인다`(
        deleted: Int,
    ) {
        // given
        val f = Fixture()
        f.jdbc.exists = true
        f.jdbc.deleted = deleted

        // when
        f.service.remove(7, 8)

        // then
        assertTrue(f.events.contains("delete"))
        assertEquals(if (deleted == 0) listOf() else listOf(listOf<Any>(8L, -1)), f.adjustments)
        assertFalse(f.events.contains("user"))
        assertEquals(0, f.animalCalls)
        assertEquals("commit", f.events[f.events.size - 2])
    }

    @Test
    fun `삭제된 동물의 즐겨찾기일 때__삭제하면__즐겨찾기를 지우고 커밋한다`() {
        // given
        val f = Fixture()
        f.jdbc.exists = true
        f.animalExists = false

        // when
        f.service.remove(7, 8)

        // then
        assertEquals(listOf("begin", "exists", "delete", "count", "commit", "cleanup"), f.events)
    }

    @Test
    fun `건수 반영이 실패할 때__삭제하면__삭제를 롤백하고 전파한다`() {
        // given
        val f = Fixture()
        f.jdbc.exists = true
        val failure: RuntimeException = IllegalStateException("private")
        f.countFailure = failure

        // when
        assertSame(failure, assertThrows(RuntimeException::class.java) { f.service.remove(7, 8) })

        // then
        assertTrue(f.events.contains("rollback"))
        assertFalse(f.events.contains("commit"))
    }

    @Test
    fun `즐겨찾기가 없을 때__삭제하면__삭제나 사용자 조회 없이 없음을 돌려준다`() {
        // given
        val f = Fixture()

        // when
        assertReason("NOT_FOUND") { f.service.remove(7, 8) }

        // then
        assertEquals(listOf("begin", "exists", "rollback", "cleanup"), f.events)
        assertTrue(f.adjustments.isEmpty())
    }

    @Test
    fun `즐겨찾기가 없을 때__목록을 조회하면__동물 HTTP 없이 빈 목록을 돌려준다`() {
        // given
        val f = Fixture()

        // when
        val result = f.service.favorites(7)

        // then
        assertEquals(Favorites(7L, 0, listOf()), result)
        assertEquals(0, f.animalCalls)
        assertTrue(f.manager.readOnly)
    }

    @Test
    fun `순서가 있는 즐겨찾기에 없는 동물이 섞였을 때__한 번에 묶어 조인하면__순서와 정확한 삭제됨 대체값을 유지한다`() {
        // given
        val f = Fixture()
        f.jdbc.favoriteRows = listOf(8L, 9L)
        f.batch = listOf(animal(8))

        // when
        val result = f.service.favorites(7)

        // then
        assertEquals(listOf(8L, 9L), result.favorites!!.map { favorite -> favorite.animalId })
        assertEquals("DOG", result.favorites!!.first().species)
        assertEquals("DELETED", result.favorites!!.last().status)
        assertNull(result.favorites!!.last().breed)
        assertEquals(1, f.animalCalls)
        assertEquals(listOf(8L, 9L), f.batchIds)
        assertTrue(f.jdbc.sql.last().endsWith("ORDER BY created_at DESC"))
    }

    @Test
    fun `즐겨찾기가 있을 때__목록을 조회하면__사용자 읽기 트랜잭션이 끝난 뒤에만 동물 묶음을 읽는다`() {
        // given
        val f = Fixture()
        f.jdbc.favoriteRows = listOf(8L)
        f.batch = listOf(animal(8))

        // when
        f.service.favorites(7)

        // then
        assertEquals(listOf("begin", "user", "favorites", "commit", "cleanup"), f.events)
        assertEquals(1, f.animalCalls)
    }

    @ParameterizedTest
    @ValueSource(strings = ["http", "duplicate", "null"])
    fun `묶음 조회가 실패하거나 식별자가 중복될 때__목록을 조회하면__실패를 삭제된 동물로 바꾸지 않고 전파한다`(
        phase: String,
    ) {
        // given
        val f = Fixture()
        f.jdbc.favoriteRows = listOf(8L)
        f.batchFailure = phase == "http"
        f.batch = if (phase == "null") null else listOf(animal(8), animal(8))

        // when
        assertThrows(RuntimeException::class.java) { f.service.favorites(7) }

        // then
        assertEquals(1, f.animalCalls)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "ROLE_USER", "ROLE_ADMIN", "no-registration"])
    fun `데이터베이스 회원이 없거나 보호소 회원이 아닐 때__등록 동물을 읽으면__동물 HTTP 전에 거부한다`(
        state: String,
    ) {
        // given
        val f = Fixture()
        if (state == "missing") {
            f.jdbc.user = false
        } else if (state == "no-registration") {
            f.jdbc.care = ""
        } else {
            f.jdbc.role = state
        }

        // when
        val expected = if (state == "missing") UserNotFoundException::class.java else ShelterMembershipRequiredException::class.java
        assertThrows(expected) { f.service.registered(7, request()) }

        // then
        assertEquals(0, f.animalCalls)
    }

    @Test
    fun `보호소 회원일 때__등록 동물을 읽으면__읽기 전용 트랜잭션 뒤에 두 조회를 실행하고 원본 정렬을 전달한다`() {
        // given
        val f = Fixture()

        // when
        val result = f.service.registered(7, request())

        // then
        assertSame(f.page, result)
        assertEquals(request(), f.request)
        // Animal lookups are independent transactions; nesting them would hold two connections of the shared pool.
        assertEquals(listOf("begin", "current", "commit", "cleanup", "shelter", "animals"), f.events)
        assertTrue(f.manager.readOnly)
    }

    @ParameterizedTest
    @ValueSource(strings = ["shelter", "animals"])
    fun `외부 조회가 실패할 때__등록 동물을 읽으면__일반 런타임 예외를 전파하고 빈 페이지를 돌려주지 않는다`(
        phase: String,
    ) {
        // given
        val f = Fixture()
        f.externalFailure = phase

        // when
        assertThrows(IllegalStateException::class.java) { f.service.registered(7, request()) }

        // then
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
                    val rs = rowOf(strings = mapOf("role" to role, "care_reg_no" to care))
                    return arrayListOf(rowMapper.mapRow(rs, 0)!!)
                }
                events.add("favorites")
                val result = ArrayList<T>()
                for (id in favoriteRows) {
                    val rs = rowOf(
                        longs = mapOf("favorite_id" to id + 100, "user_id" to 7L, "animal_id" to id),
                        timestamps = mapOf("created_at" to Timestamp.valueOf(NOW)),
                    )
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
