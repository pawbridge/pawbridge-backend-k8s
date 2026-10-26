package com.pawbridge.backend.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.request.CreateAnimalRequest
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.request.UpdateAnimalDescriptionRequest
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.request.UpdateAnimalStatusRequest
import com.pawbridge.backend.animal.internal.command.infrastructure.service.AnimalCommandService
import com.pawbridge.backend.animal.internal.command.infrastructure.service.AnimalCommandForbiddenException
import jakarta.persistence.EntityNotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/** The animal register, status, description and delete adapter (JPA) with manual notice numbers, on the real tables. */
class AnimalCommandJpaContractTest : JpaContractBase() {
    @Autowired
    lateinit var animals: AnimalCommandService

    private val json = ObjectMapper().findAndRegisterModules()

    @BeforeEach
    fun seed() {
        seedKeeper(ADMIN, "ROLE_ADMIN", null)
        seedKeeper(KEEPER, "ROLE_SHELTER", "CR-A")
        seedKeeper(RIVAL, "ROLE_SHELTER", "CR-B")
        seedKeeper(PLAIN, "ROLE_USER", null)
        seedKeeper(LEAVING, "ROLE_ADMIN", null)
        execute("INSERT INTO pawbridge_user.contact_deletions(user_id) VALUES (?)", LEAVING)
        seedShelter(SHELTER_A, "CR-A")
        seedShelter(SHELTER_B, "CR-B")
    }

    private fun registration(care: String = "CR-A", source: String? = null, notice: String? = null, description: String = "순한 아이") =
        json.readValue(
            json.writeValueAsString(
                mapOf(
                    "careRegNo" to care, "apmsNoticeNo" to notice, "apiSource" to source, "species" to "DOG", "gender" to "MALE",
                    "neuterStatus" to "UNKNOWN", "status" to "PROTECT", "noticeStartDate" to "2026-03-01", "noticeEndDate" to "2026-03-20",
                    "breed" to "믹스견", "color" to "갈색", "happenPlace" to "합성 장소", "description" to description,
                ),
            ),
            CreateAnimalRequest::class.java,
        )

    private fun statusChange(status: String) = json.readValue("""{"newStatus":"$status"}""", UpdateAnimalStatusRequest::class.java)

    private fun descriptionChange(text: String) = json.readValue("""{"description":"$text"}""", UpdateAnimalDescriptionRequest::class.java)

    private fun animalRows() = count("SELECT count(*) FROM pawbridge_animal.animals")

    @Test
    fun `보호소 담당자일 때__수동으로 등록하면__오늘 날짜의 순번 공고번호와 수동 출처로 저장하고 검색 문서를 만든다`() {
        // given
        // when
        val first = animals.create(KEEPER, registration())
        val second = animals.create(ADMIN, registration())

        // then
        val numbers = listOf(first.id!!, second.id!!).map { scalar("SELECT apms_notice_no FROM pawbridge_animal.animals WHERE id = ?", it)!! }
        assertThat(numbers[0]).matches("MAN-[0-9]{6}-000001")
        assertThat(numbers[1]).isEqualTo(numbers[0].dropLast(1) + "2")
        assertThat(scalar("SELECT api_source FROM pawbridge_animal.animals WHERE id = ?", first.id!!)).isEqualTo("MANUAL")
        assertThat(count("SELECT count(*) FROM pawbridge_animal.animal_search_documents WHERE animal_id = ?", first.id!!)).isEqualTo(1)
    }

    @Test
    fun `중간 공고번호가 지워졌을 때__다시 등록하면__현재 최댓값 뒤로 번호를 올린다`() {
        // given
        val first = animals.create(ADMIN, registration())
        val second = animals.create(ADMIN, registration())
        val prefix = scalar("SELECT apms_notice_no FROM pawbridge_animal.animals WHERE id = ?", first.id!!)!!.dropLast(6)
        animals.delete(ADMIN, first.id)

        // when
        val third = animals.create(ADMIN, registration())

        // then
        assertThat(scalar("SELECT apms_notice_no FROM pawbridge_animal.animals WHERE id = ?", second.id!!)).isEqualTo(prefix + "000002")
        assertThat(scalar("SELECT apms_notice_no FROM pawbridge_animal.animals WHERE id = ?", third.id!!)).isEqualTo(prefix + "000003")
    }

    @Test
    fun `다른 보호소나 권한 없는 회원이거나 보호소가 없을 때__등록하면__거부하고 APMS 출처는 공고번호가 필요하다`() {
        // given
        val before = ContractPostgres.snapshot()

        // when
        // then
        assertThatThrownBy { animals.create(KEEPER, registration(care = "CR-B")) }.isInstanceOf(AnimalCommandForbiddenException::class.java)
        for (user in listOf(PLAIN, LEAVING, UNKNOWN)) {
            assertThatThrownBy { animals.create(user, registration()) }.isInstanceOf(AnimalCommandForbiddenException::class.java)
        }
        assertThatThrownBy { animals.create(ADMIN, registration(care = "CR-NONE")) }.isInstanceOf(EntityNotFoundException::class.java)
        assertThatThrownBy { animals.create(ADMIN, registration(source = "APMS_ANIMAL")) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(ContractPostgres.snapshot()).isEqualTo(before)
        val imported = animals.create(ADMIN, registration(source = "APMS_ANIMAL", notice = "경기-양평-2026-00001"))
        assertThat(scalar("SELECT apms_notice_no FROM pawbridge_animal.animals WHERE id = ?", imported.id!!)).isEqualTo("경기-양평-2026-00001")
    }

    @Test
    fun `4바이트 문자가 있을 때__등록하면__제약 위반으로 실패하고 모든 쓰기를 되돌린다`() {
        // given
        val before = ContractPostgres.snapshot()

        // when
        val failure = runCatching { animals.create(ADMIN, registration(description = "😀")) }.exceptionOrNull()

        // then
        assertThat(failure).isNotNull()
        assertThat(sqlState(failure)).isEqualTo("23514")
        assertThat(ContractPostgres.snapshot()).isEqualTo(before)
    }

    @Test
    fun `소속 보호소와 다른 보호소의 동물이 있을 때__상태와 설명을 바꾸면__소속 담당자와 관리자만 바꾼다`() {
        // given
        seedAnimal(ANIMAL_A, SHELTER_A, description = "처음")
        seedAnimal(ANIMAL_B, SHELTER_B)

        // when
        val byKeeper = animals.updateStatus(KEEPER, ANIMAL_A, statusChange("ADOPTED"))
        animals.updateDescription(ADMIN, ANIMAL_A, descriptionChange("새 설명"))

        // then
        assertThat(byKeeper.status.toString()).contains("ADOPTED")
        assertThat(row("pawbridge_animal.animals", "id", ANIMAL_A).let { it.get("status").asText() to it.get("description").asText() })
            .isEqualTo("ADOPTED" to "새 설명")
        assertThat(count("SELECT count(*) FROM pawbridge_animal.animal_search_documents WHERE animal_id = ?", ANIMAL_A)).isEqualTo(1)
        assertThatThrownBy { animals.updateStatus(KEEPER, ANIMAL_B, statusChange("ADOPTED")) }.isInstanceOf(AnimalCommandForbiddenException::class.java)
        assertThatThrownBy { animals.updateDescription(RIVAL, ANIMAL_A, descriptionChange("x")) }.isInstanceOf(AnimalCommandForbiddenException::class.java)
        assertThatThrownBy { animals.updateStatus(ADMIN, 99_999_999L, statusChange("ADOPTED")) }.isInstanceOf(EntityNotFoundException::class.java)
        assertThat(scalar("SELECT status FROM pawbridge_animal.animals WHERE id = ?", ANIMAL_B)).isEqualTo("PROTECT")
    }

    @Test
    fun `챗봇 대화가 달린 동물과 달리지 않은 동물이 있을 때__삭제하면__달린 동물은 외래키로 실패하고 나머지는 지운다`() {
        // given
        seedAnimal(ANIMAL_A, SHELTER_A)
        seedAnimal(ANIMAL_B, SHELTER_A)
        execute(
            "INSERT INTO pawbridge_animal.chatbot_sessions(id, anonymous_session_id, animal_id, created_at) VALUES ('s-1', 'a-1', ?, '2026-03-01 00:00:00')",
            ANIMAL_A,
        )

        // when
        animals.delete(KEEPER, ANIMAL_B)
        val failure = runCatching { animals.delete(KEEPER, ANIMAL_A) }.exceptionOrNull()

        // then
        assertThat(count("SELECT count(*) FROM pawbridge_animal.animals WHERE id = ?", ANIMAL_B)).isZero()
        assertThat(sqlState(failure)).isEqualTo("23503")
        assertThat(count("SELECT count(*) FROM pawbridge_animal.animals WHERE id = ?", ANIMAL_A)).isEqualTo(1)
        assertThatThrownBy { animals.delete(RIVAL, ANIMAL_A) }.isInstanceOf(AnimalCommandForbiddenException::class.java)
        assertThat(animalRows()).isEqualTo(1)
    }

    private companion object {
        const val ADMIN = 8_200_001L
        const val KEEPER = 8_200_002L
        const val RIVAL = 8_200_003L
        const val PLAIN = 8_200_004L
        const val LEAVING = 8_200_005L
        const val UNKNOWN = 8_200_099L
        const val SHELTER_A = 8_200_101L
        const val SHELTER_B = 8_200_102L
        const val ANIMAL_A = 8_200_201L
        const val ANIMAL_B = 8_200_202L
    }
}
