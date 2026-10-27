package com.pawbridge.backend.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.request.CreateAnimalRequest
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.request.UpdateAnimalDescriptionRequest
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.request.UpdateAnimalStatusRequest
import com.pawbridge.backend.animal.internal.command.infrastructure.service.AnimalCommandService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Invariants of the animal writers under real concurrency. As in the store contracts, the writes are slowed inside their
 * transaction ([withSlowWrites]) so that removing the lock under test always lets the other worker read the old state.
 */
class AnimalJpaConcurrencyContractTest : JpaContractBase() {
    @Autowired
    lateinit var animals: AnimalCommandService

    private val json = ObjectMapper().findAndRegisterModules()

    @BeforeEach
    fun seed() {
        for (admin in ADMINS) {
            seedKeeper(admin, "ROLE_ADMIN", null)
        }
        seedKeeper(KEEPER, "ROLE_SHELTER", "CR-A")
        seedShelter(SHELTER_A, "CR-A")
    }

    private fun registration() = json.readValue(
        """{"careRegNo":"CR-A","species":"DOG","gender":"MALE","neuterStatus":"UNKNOWN","status":"PROTECT",
            "noticeStartDate":"2026-03-01","noticeEndDate":"2026-03-20","breed":"믹스견","description":"설명"}""",
        CreateAnimalRequest::class.java,
    )

    @Test
    fun `관리자 넷이 동시에 수동 등록할 때__공고번호를 만들면__모두 서로 다른 번호를 받는다`() {
        // given
        // when
        val results = withSlowWrites("animals", "INSERT", schema = "pawbridge_animal") {
            runTogether(ADMINS.size) { index -> animals.create(ADMINS[index], registration()) }
        }

        // then
        assertThat(results.filter { it.isFailure }.map { it.exceptionOrNull() }).isEmpty()
        val numbers = ContractPostgres.admin.queryForList("SELECT apms_notice_no FROM pawbridge_animal.animals", String::class.java)
        assertThat(numbers).hasSize(ADMINS.size)
        assertThat(numbers.toSet()).`as`("Every registration gets its own manual notice number").hasSize(ADMINS.size)
    }

    @Test
    fun `담당자와 관리자가 같은 동물을 동시에 바꿀 때__상태와 설명을 바꾸면__두 변경이 모두 남는다`() {
        // given
        seedAnimal(ANIMAL, SHELTER_A, description = "처음")

        // when
        val results = withSlowWrites("animals", "UPDATE", schema = "pawbridge_animal") {
            runTogether(2) { index ->
                if (index == 0) {
                    animals.updateStatus(KEEPER, ANIMAL, json.readValue("""{"newStatus":"ADOPTED"}""", UpdateAnimalStatusRequest::class.java))
                } else {
                    animals.updateDescription(ADMINS[0], ANIMAL, json.readValue("""{"description":"새 설명"}""", UpdateAnimalDescriptionRequest::class.java))
                }
            }
        }

        // then
        assertThat(results.filter { it.isFailure }.map { it.exceptionOrNull() }).isEmpty()
        assertThat(scalar("SELECT status FROM pawbridge_animal.animals WHERE id = ?", ANIMAL)).isEqualTo("ADOPTED")
        assertThat(scalar("SELECT description FROM pawbridge_animal.animals WHERE id = ?", ANIMAL)).isEqualTo("새 설명")
    }

    private companion object {
        val ADMINS = (0 until 4).map { 8_300_001L + it }
        const val KEEPER = 8_300_011L
        const val SHELTER_A = 8_300_101L
        const val ANIMAL = 8_300_201L
    }
}
