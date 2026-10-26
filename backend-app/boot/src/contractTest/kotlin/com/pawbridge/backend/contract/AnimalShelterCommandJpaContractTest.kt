package com.pawbridge.backend.contract

import com.pawbridge.backend.animal.internal.sheltercommand.application.ShelterCommandConflictException
import com.pawbridge.backend.animal.internal.sheltercommand.application.ShelterCommandForbiddenException
import com.pawbridge.backend.animal.internal.sheltercommand.application.ShelterCommands
import jakarta.persistence.EntityNotFoundException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/** The shelter create, update and delete adapter (JPA) with the user row lock and the shelter row lock, on the real tables. */
class AnimalShelterCommandJpaContractTest : JpaContractBase() {
    @Autowired
    lateinit var shelters: ShelterCommands

    @BeforeEach
    fun seed() {
        seedKeeper(ADMIN, "ROLE_ADMIN", null)
        seedKeeper(KEEPER, "ROLE_SHELTER", "CR-A")
        seedKeeper(RIVAL, "ROLE_SHELTER", "CR-B")
        seedKeeper(PLAIN, "ROLE_USER", null)
        seedKeeper(LEAVING, "ROLE_ADMIN", null)
        execute("INSERT INTO pawbridge_user.contact_deletions(user_id) VALUES (?)", LEAVING)
        seedShelter(SHELTER_A, "CR-A")
    }

    private fun create(careRegNo: String = "CR-NEW", name: String = "새 보호소", address: String? = "서울 어딘가") =
        ShelterCommands.Create(careRegNo, name, "02-111-1111", address, "대표", "단체", "a@fixture.invalid", "소개", "절차", "09-18")

    private fun update(phone: String? = "02-999-9999", introduction: String? = "새 소개") =
        ShelterCommands.Update(phone, "b@fixture.invalid", introduction, "새 절차", "10-17")

    private fun shelterCount() = count("SELECT count(*) FROM pawbridge_animal.shelters")

    @Test
    fun `관리자일 때__보호소를 등록하면__행과 검색 문서를 저장하고 검색 표시를 지운다`() {
        // given
        // when
        val created = shelters.create(ADMIN, create())

        // then
        assertThat(created.id).isNotNull()
        assertThat(row("pawbridge_animal.shelters", "id", created.id!!).let { listOf(it.get("care_reg_no").asText(), it.get("name").asText(), it.get("search_dirty").asBoolean()) })
            .isEqualTo(listOf("CR-NEW", "새 보호소", false))
        assertThat(count("SELECT count(*) FROM pawbridge_animal.shelter_search_documents WHERE shelter_id = ?", created.id!!)).isEqualTo(1)
    }

    @Test
    fun `관리자가 아니거나 탈퇴가 시작됐거나 없는 회원일 때__등록하면__거부하고 아무것도 저장하지 않는다`() {
        // given
        val before = ContractPostgres.snapshot()

        // when
        // then
        for (user in listOf(KEEPER, PLAIN, LEAVING, UNKNOWN)) {
            assertThatThrownBy { shelters.create(user, create()) }.isInstanceOf(ShelterCommandForbiddenException::class.java)
        }
        assertThatThrownBy { shelters.create(null, create()) }.isInstanceOf(ShelterCommandForbiddenException::class.java)
        assertThat(ContractPostgres.snapshot()).isEqualTo(before)
    }

    @Test
    fun `보충 문자이거나 등록번호가 겹칠 때__등록하면__거부하고 검색 문서도 남기지 않는다`() {
        // given
        val before = ContractPostgres.snapshot()

        // when
        val duplicate = runCatching { shelters.create(ADMIN, create(careRegNo = "CR-A")) }.exceptionOrNull()

        // then
        assertThatThrownBy { shelters.create(ADMIN, create(name = "이모지 😀")) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(sqlState(duplicate)).isEqualTo("23505")
        assertThat(ContractPostgres.snapshot()).isEqualTo(before)
    }

    @Test
    fun `관리자와 소속 보호소 담당자와 다른 보호소 담당자가 있을 때__수정하면__허용된 쪽만 다섯 필드를 바꾼다`() {
        // given
        val before = row("pawbridge_animal.shelters", "id", SHELTER_A)

        // when
        val byKeeper = shelters.update(KEEPER, SHELTER_A, update(phone = "02-555-5555"))
        val afterKeeper = row("pawbridge_animal.shelters", "id", SHELTER_A)
        shelters.update(ADMIN, SHELTER_A, update(phone = "02-777-7777"))

        // then
        assertThat(byKeeper.phone).isEqualTo("02-555-5555")
        assertThat(omitting(afterKeeper, "phone", "email", "introduction", "adoption_procedure", "operating_hours", "updated_at", "search_dirty", "search_revision"))
            .isEqualTo(omitting(before, "phone", "email", "introduction", "adoption_procedure", "operating_hours", "updated_at", "search_dirty", "search_revision"))
        assertThat(afterKeeper.get("introduction").asText()).isEqualTo("새 소개")
        assertThat(afterKeeper.get("email").asText()).isEqualTo("b@fixture.invalid")
        assertThat(scalar("SELECT phone FROM pawbridge_animal.shelters WHERE id = ?", SHELTER_A)).isEqualTo("02-777-7777")
        for (user in listOf(RIVAL, PLAIN, LEAVING, UNKNOWN)) {
            assertThatThrownBy { shelters.update(user, SHELTER_A, update()) }.isInstanceOf(ShelterCommandForbiddenException::class.java)
        }
        assertThatThrownBy { shelters.update(ADMIN, 99_999_999L, update()) }.isInstanceOf(EntityNotFoundException::class.java)
        assertThatThrownBy { shelters.update(KEEPER, SHELTER_A, update(introduction = "😀")) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(scalar("SELECT phone FROM pawbridge_animal.shelters WHERE id = ?", SHELTER_A)).isEqualTo("02-777-7777")
    }

    @Test
    fun `동물이 있거나 소속 회원이 남은 보호소일 때__삭제하면__충돌이고 비어 있으면 연관 행과 함께 지우되 관측 이력은 남긴다`() {
        // given
        seedShelter(WITH_ANIMAL, "CR-ANIMAL")
        seedAnimal(ANIMAL, WITH_ANIMAL)
        seedShelter(WITH_MEMBER, "CR-MEMBER")
        seedKeeper(MEMBER_OF, "ROLE_SHELTER", "CR-MEMBER")
        seedShelter(EMPTY, "CR-EMPTY")
        execute("INSERT INTO pawbridge_animal.shelter_public_information(shelter_id, details, collected_at) VALUES (?, '{}'::jsonb, '2026-03-01 00:00:00')", EMPTY)
        execute(
            "INSERT INTO pawbridge_animal.shelter_daily_observations(shelter_id, observation_date, observed_at, protected_count) " +
                "VALUES (?, '2026-03-01', '2026-03-01T00:00:00+09:00', 4)",
            EMPTY,
        )

        // when
        shelters.delete(ADMIN, EMPTY)

        // then
        assertThatThrownBy { shelters.delete(ADMIN, WITH_ANIMAL) }.isInstanceOf(ShelterCommandConflictException::class.java)
        assertThatThrownBy { shelters.delete(ADMIN, WITH_MEMBER) }.isInstanceOf(ShelterCommandConflictException::class.java)
        assertThatThrownBy { shelters.delete(KEEPER, SHELTER_A) }.isInstanceOf(ShelterCommandForbiddenException::class.java)
        assertThatThrownBy { shelters.delete(ADMIN, 99_999_999L) }.isInstanceOf(EntityNotFoundException::class.java)
        assertThat(count("SELECT count(*) FROM pawbridge_animal.shelters WHERE id IN (?, ?)", WITH_ANIMAL, WITH_MEMBER)).isEqualTo(2)
        assertThat(count("SELECT count(*) FROM pawbridge_animal.shelters WHERE id = ?", EMPTY)).isZero()
        assertThat(count("SELECT count(*) FROM pawbridge_animal.shelter_public_information WHERE shelter_id = ?", EMPTY)).isZero()
        assertThat(count("SELECT count(*) FROM pawbridge_animal.shelter_daily_observations WHERE shelter_id = ?", EMPTY)).isEqualTo(1)
        assertThat(shelterCount()).isEqualTo(3)
    }

    @Test
    fun `회원 승인이 보호소 행에 키 공유 잠금을 쥐고 소속을 쓰는 중일 때__다른 관리자가 삭제하면__잠금이 풀릴 때까지 기다린 뒤 커밋된 소속을 보고 충돌로 끝난다`() {
        // given
        seedShelter(EMPTY, "CR-EMPTY")
        val executor = Executors.newSingleThreadExecutor()
        try {
            ContractPostgres.adminConnection { approval ->
                approval.autoCommit = false
                approval.createStatement().use {
                    it.execute("SELECT id FROM pawbridge_animal.shelters WHERE id = $EMPTY FOR KEY SHARE")
                    it.execute("UPDATE pawbridge_user.users SET care_reg_no = 'CR-EMPTY' WHERE user_id = $PLAIN")
                }

                // when
                val delete = executor.submit(Callable { shelters.delete(ADMIN, EMPTY) })

                // then
                assertThatThrownBy { delete.get(700, TimeUnit.MILLISECONDS) }.isInstanceOf(TimeoutException::class.java)
                approval.commit()
                assertThatThrownBy { delete.get(10, TimeUnit.SECONDS) }.hasRootCauseInstanceOf(ShelterCommandConflictException::class.java)
                assertThat(count("SELECT count(*) FROM pawbridge_animal.shelters WHERE id = ?", EMPTY)).isEqualTo(1)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `다른 연결이 담당자 회원 행에 키 공유 잠금을 쥐고 있을 때__그 담당자가 보호소를 수정하면__회원 행 잠금이 풀릴 때까지 기다린다`() {
        // given
        val executor = Executors.newSingleThreadExecutor()
        try {
            ContractPostgres.adminConnection { holder ->
                holder.autoCommit = false
                holder.createStatement().use { it.execute("SELECT user_id FROM pawbridge_user.users WHERE user_id = $KEEPER FOR KEY SHARE") }

                // when
                val update = executor.submit(Callable { shelters.update(KEEPER, SHELTER_A, update()) })

                // then
                assertThatThrownBy { update.get(700, TimeUnit.MILLISECONDS) }.isInstanceOf(TimeoutException::class.java)
                holder.commit()
                assertThat(update.get(10, TimeUnit.SECONDS).phone).isEqualTo("02-999-9999")
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private companion object {
        const val ADMIN = 8_100_001L
        const val KEEPER = 8_100_002L
        const val RIVAL = 8_100_003L
        const val PLAIN = 8_100_004L
        const val LEAVING = 8_100_005L
        const val MEMBER_OF = 8_100_006L
        const val UNKNOWN = 8_100_099L
        const val SHELTER_A = 8_100_101L
        const val WITH_ANIMAL = 8_100_102L
        const val WITH_MEMBER = 8_100_103L
        const val EMPTY = 8_100_104L
        const val ANIMAL = 8_100_201L
    }
}
