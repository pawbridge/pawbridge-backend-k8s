package com.pawbridge.backend.contract

import com.pawbridge.backend.animal.api.AnimalCommandActors
import com.pawbridge.backend.store.api.StoreOrderActors
import com.pawbridge.backend.store.api.StoreOrderAdministrators
import com.pawbridge.backend.user.internal.animalcommand.infrastructure.JdbcAnimalCommandActors
import com.pawbridge.backend.user.internal.storeorder.infrastructure.JdbcStoreOrderActors
import com.pawbridge.backend.user.internal.storeorder.infrastructure.JdbcStoreOrderAdministrators
import java.util.concurrent.Callable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The three "current user in the caller's transaction" repositories other modules rely on: the animal command actor,
 * the store order actor and the store order administrator. They must run inside the shared writer (or read-only)
 * transaction, lock the user row for writes, and report a pending deletion as an inactive, deleted member.
 */
class UserActorsRepositoryContractTest : UserRepositoryContractBase() {
    private lateinit var animalActors: JdbcAnimalCommandActors
    private lateinit var orderActors: JdbcStoreOrderActors
    private lateinit var administrators: JdbcStoreOrderAdministrators

    @BeforeEach
    fun build() {
        insertUser(ACTIVE, role = "ROLE_SHELTER", careRegNo = "CARE-ACTOR", nickname = "활성")
        insertUser(PENDING, role = "ROLE_ADMIN", careRegNo = null, nickname = "대기")
        execute("INSERT INTO pawbridge_user.contact_deletions(user_id) VALUES (?)", PENDING)
        animalActors = JdbcAnimalCommandActors(write)
        orderActors = JdbcStoreOrderActors(write)
        administrators = JdbcStoreOrderAdministrators(write)
    }

    // ---- Animal command actor ---------------------------------------------------------------------------------

    @Test
    fun `활성 삭제 대기 없는 회원일 때__동물 명령 주체를 찾으면__현재 역할과 등록번호와 상태를 맞게 준다`() {
        // given
        // when
        val found = writeTransaction().execute { listOf(animalActors.find(ACTIVE), animalActors.find(PENDING), animalActors.find(NOBODY)) }!!

        // then
        assertThat(found[0]).isEqualTo(AnimalCommandActors.Actor(ACTIVE, "ROLE_SHELTER", "CARE-ACTOR", true, false))
        assertThat(found[1]).isEqualTo(AnimalCommandActors.Actor(PENDING, "ROLE_ADMIN", null, false, true))
        assertThat(found[2]).isEqualTo(AnimalCommandActors.Actor(NOBODY, null, null, false, true))
    }

    @Test
    fun `쓰기 트랜잭션이 아니거나 아이디가 양수가 아닐 때__동물 명령 주체를 찾으면__거부한다`() {
        // given
        // when
        // then
        assertThatThrownBy { animalActors.find(ACTIVE) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { readOnlyTransaction().execute { animalActors.find(ACTIVE) } }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { writeTransaction().execute { animalActors.find(0) } }.isInstanceOf(IllegalArgumentException::class.java)
        assertNoTransactionOpen()
    }

    @Test
    fun `회원 행이 다른 연결에 잠겨 있을 때__동물 명령 주체를 찾으면__잠금이 풀릴 때까지 기다린다`() {
        // given
        // when
        val found = assertWaitsForUserRowLock(ACTIVE, Callable { writeTransaction().execute { animalActors.find(ACTIVE) } })

        // then
        assertThat(found!!.role).isEqualTo("ROLE_SHELTER")
    }

    // ---- Store order actor ------------------------------------------------------------------------------------

    @Test
    fun `활성 삭제 대기 없는 회원일 때__주문 주체를 쓰기와 읽기로 읽으면__같은 상태를 준다`() {
        // given
        // when
        val forWrite = writeTransaction().execute { listOf(orderActors.currentForWrite(ACTIVE), orderActors.currentForWrite(PENDING), orderActors.currentForWrite(NOBODY)) }!!
        val forRead = readOnlyTransaction().execute { listOf(orderActors.currentForRead(ACTIVE), orderActors.currentForRead(PENDING), orderActors.currentForRead(NOBODY)) }!!

        // then
        val expected = listOf(
            StoreOrderActors.Actor(ACTIVE, true, false),
            StoreOrderActors.Actor(PENDING, false, true),
            StoreOrderActors.Actor(NOBODY, false, true),
        )
        assertThat(forWrite).isEqualTo(expected)
        assertThat(forRead).isEqualTo(expected)
    }

    @Test
    fun `트랜잭션이 없거나 읽기 전용에서 쓰기를 요구하거나 아이디가 틀릴 때__주문 주체를 읽으면__거부한다`() {
        // given
        // when
        // then
        assertThatThrownBy { orderActors.currentForRead(ACTIVE) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { orderActors.currentForWrite(ACTIVE) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { readOnlyTransaction().execute { orderActors.currentForWrite(ACTIVE) } }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { writeTransaction().execute { orderActors.currentForRead(-1) } }.isInstanceOf(IllegalArgumentException::class.java)
        assertNoTransactionOpen()
    }

    @Test
    fun `회원 행이 다른 연결에 잠겨 있을 때__주문 주체를 읽으면__쓰기는 기다리고 읽기는 기다리지 않는다`() {
        // given
        // when
        val read = assertIgnoresUserRowLock(ACTIVE, Callable { readOnlyTransaction().execute { orderActors.currentForRead(ACTIVE) } })
        val written = assertWaitsForUserRowLock(ACTIVE, Callable { writeTransaction().execute { orderActors.currentForWrite(ACTIVE) } })

        // then
        assertThat(read!!.active).isTrue()
        assertThat(written!!.active).isTrue()
    }

    // ---- Store order administrator ----------------------------------------------------------------------------

    @Test
    fun `활성 삭제 대기 없는 회원일 때__주문 관리자 권한을 읽으면__데이터베이스 역할을 보존하고 삭제 대기는 비활성이다`() {
        // given
        // when
        val forWrite = writeTransaction().execute {
            listOf(administrators.currentForWrite(ACTIVE), administrators.currentForWrite(PENDING), administrators.currentForWrite(NOBODY))
        }!!
        val forRead = readOnlyTransaction().execute {
            listOf(administrators.currentForRead(ACTIVE), administrators.currentForRead(PENDING), administrators.currentForRead(NOBODY))
        }!!

        // then
        val expected = listOf(
            StoreOrderAdministrators.Actor(ACTIVE, true, false, "ROLE_SHELTER"),
            StoreOrderAdministrators.Actor(PENDING, false, true, "ROLE_ADMIN"),
            StoreOrderAdministrators.Actor(NOBODY, false, true, null),
        )
        assertThat(forWrite).isEqualTo(expected)
        assertThat(forRead).isEqualTo(expected)
    }

    @Test
    fun `트랜잭션 종류가 맞지 않거나 아이디가 틀릴 때__주문 관리자 권한을 읽으면__거부한다`() {
        // given
        // when
        // then
        assertThatThrownBy { administrators.currentForRead(ACTIVE) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { writeTransaction().execute { administrators.currentForRead(ACTIVE) } }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { readOnlyTransaction().execute { administrators.currentForWrite(ACTIVE) } }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { writeTransaction().execute { administrators.currentForWrite(0) } }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertNoTransactionOpen()
    }

    @Test
    fun `회원 행이 다른 연결에 잠겨 있을 때__주문 관리자 권한을 읽으면__쓰기는 기다리고 읽기는 기다리지 않는다`() {
        // given
        // when
        val read = assertIgnoresUserRowLock(ACTIVE, Callable { readOnlyTransaction().execute { administrators.currentForRead(ACTIVE) } })
        val written = assertWaitsForUserRowLock(ACTIVE, Callable { writeTransaction().execute { administrators.currentForWrite(ACTIVE) } })

        // then
        assertThat(read!!.role).isEqualTo("ROLE_SHELTER")
        assertThat(written!!.role).isEqualTo("ROLE_SHELTER")
    }

    private companion object {
        const val ACTIVE = 9_981_001L
        const val PENDING = 9_981_002L
        const val NOBODY = 9_981_900L
    }
}
