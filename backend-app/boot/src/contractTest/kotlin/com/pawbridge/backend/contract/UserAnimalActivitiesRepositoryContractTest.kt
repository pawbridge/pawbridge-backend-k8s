package com.pawbridge.backend.contract

import com.pawbridge.backend.animal.api.AnimalFavoriteCounts
import com.pawbridge.backend.user.internal.animalactivity.application.FavoriteActivityRejectedException
import com.pawbridge.backend.user.internal.animalactivity.application.FavoriteActivityRejectedException.Reason
import com.pawbridge.backend.user.internal.animalactivity.application.ShelterMembershipRequiredException
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities.Animal
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities.AnimalLookup
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities.AnimalPage
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities.PageRequest
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities.Shelter
import com.pawbridge.backend.user.internal.animalactivity.infrastructure.JdbcUserAnimalActivities
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDateTime
import java.util.function.Supplier
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The favorite and registered-animal repository called directly. The animal module (favorite counter and animal
 * lookups) is a mock; the favorite rows, the user checks and their transactions run on the real database.
 */
class UserAnimalActivitiesRepositoryContractTest : UserRepositoryContractBase() {
    private val created: LocalDateTime = LocalDateTime.of(2026, 3, 5, 10, 11, 12, 345_678_000)
    private val animals = mockk<AnimalLookup>()
    private val counts = mockk<AnimalFavoriteCounts>()
    private lateinit var activities: JdbcUserAnimalActivities

    @BeforeEach
    fun build() {
        insertUser(MEMBER, nickname = "찜하는회원")
        every { counts.adjust(any(), any()) } returns true
        activities = JdbcUserAnimalActivities(write, writeTransaction(), readOnlyTransaction(), animals, counts, Supplier { created })
    }

    // ---- Add --------------------------------------------------------------------------------------------------

    @Test
    @Tag("timezone-sensitive")
    fun `새 즐겨찾기일 때__추가하면__행을 넣고 키와 입력 시각을 돌려주며 건수를 하나 올린다`() {
        // given
        // when
        val favorite = activities.add(MEMBER, 55)

        // then
        val stored = row("pawbridge_user.favorites", "favorite_id", favorite.favoriteId!!)
        assertThat(favorite.userId).isEqualTo(MEMBER)
        assertThat(favorite.animalId).isEqualTo(55)
        assertThat(favorite.createdAt).isEqualTo(created)
        assertThat(stored.get("user_id").asLong()).isEqualTo(MEMBER)
        assertThat(stored.get("animal_id").asLong()).isEqualTo(55)
        assertThat(stored.get("created_at").asText()).isEqualTo(storedWallClock(created))
        verify(exactly = 1) { counts.adjust(55, 1) }
    }

    @Test
    fun `이미 찜했거나 회원이 없을 때__추가하면__중복 또는 회원 없음으로 거부하고 건수를 바꾸지 않는다`() {
        // given
        execute("INSERT INTO pawbridge_user.favorites(user_id,animal_id,created_at) VALUES (?,?,now())", MEMBER, 55)
        val before = ContractPostgres.snapshot()

        // when
        // then
        assertRejected(Reason.ALREADY_EXISTS) { activities.add(MEMBER, 55) }
        assertThatThrownBy { activities.add(NOBODY, 55) }.isInstanceOf(UserNotFoundException::class.java)
        verify(exactly = 0) { counts.adjust(any(), any()) }
        assertThat(ContractPostgres.snapshot() == before).isTrue()
    }

    @Test
    fun `삽입 직전에 같은 쌍이 먼저 들어갈 때__추가하면__유일성 위반을 중복으로 바꾸고 건수를 바꾸지 않는다`() {
        // given
        // when
        // then
        withTrigger("INSERT", "favorites", "NEW.user_id = $MEMBER", "RAISE unique_violation USING MESSAGE = 'raced'") {
            assertRejected(Reason.ALREADY_EXISTS) { activities.add(MEMBER, 56) }
        }
        verify(exactly = 0) { counts.adjust(any(), any()) }
        assertThat(count("SELECT count(*) FROM pawbridge_user.favorites WHERE user_id = ?", MEMBER)).isZero()
    }

    @Test
    fun `동물이 없거나 건수 반영이 실패할 때__추가하면__즐겨찾기 행을 롤백한다`() {
        // given
        every { counts.adjust(57, 1) } returns false
        every { counts.adjust(58, 1) } throws IllegalStateException("counter down")

        // when
        // then
        assertRejected(Reason.ANIMAL_NOT_FOUND) { activities.add(MEMBER, 57) }
        assertThatThrownBy { activities.add(MEMBER, 58) }.isInstanceOf(IllegalStateException::class.java)
        assertThat(count("SELECT count(*) FROM pawbridge_user.favorites WHERE user_id = ?", MEMBER)).isZero()
        assertNoTransactionOpen()
    }

    // ---- Remove -----------------------------------------------------------------------------------------------

    @Test
    fun `찜한 동물일 때__삭제하면__행을 지우고 건수를 하나 줄이며 다른 회원의 같은 동물은 남긴다`() {
        // given
        insertUser(OTHER, nickname = "다른회원")
        execute("INSERT INTO pawbridge_user.favorites(user_id,animal_id,created_at) VALUES (?,?,now()),(?,?,now())", MEMBER, 55, OTHER, 55)

        // when
        activities.remove(MEMBER, 55)

        // then
        assertThat(count("SELECT count(*) FROM pawbridge_user.favorites WHERE user_id = ?", MEMBER)).isZero()
        assertThat(count("SELECT count(*) FROM pawbridge_user.favorites WHERE user_id = ?", OTHER)).isOne()
        verify(exactly = 1) { counts.adjust(55, -1) }
    }

    @Test
    fun `찜하지 않았을 때__삭제하면__찾을 수 없음으로 거부하고 건수를 바꾸지 않는다`() {
        // given
        // when
        // then
        assertRejected(Reason.NOT_FOUND) { activities.remove(MEMBER, 55) }
        verify(exactly = 0) { counts.adjust(any(), any()) }
    }

    @Test
    fun `동물이 이미 사라졌거나 다른 삭제가 먼저 지웠을 때__삭제하면__동물이 없어도 즐겨찾기를 지우고 지운 행이 없으면 줄이지 않는다`() {
        // given
        every { counts.adjust(60, -1) } returns false
        execute("INSERT INTO pawbridge_user.favorites(user_id,animal_id,created_at) VALUES (?,?,now()),(?,?,now())", MEMBER, 60, MEMBER, 61)

        // when
        activities.remove(MEMBER, 60)
        withTrigger("DELETE", "favorites", "OLD.animal_id = 61", "RETURN NULL") { activities.remove(MEMBER, 61) }

        // then
        assertThat(count("SELECT count(*) FROM pawbridge_user.favorites WHERE animal_id = 60")).isZero()
        assertThat(count("SELECT count(*) FROM pawbridge_user.favorites WHERE animal_id = 61")).isOne()
        verify(exactly = 1) { counts.adjust(60, -1) }
        verify(exactly = 0) { counts.adjust(61, any()) }
    }

    @Test
    fun `건수 반영이 실패할 때__삭제하면__삭제를 롤백하고 실패를 전파한다`() {
        // given
        execute("INSERT INTO pawbridge_user.favorites(user_id,animal_id,created_at) VALUES (?,?,now())", MEMBER, 62)
        every { counts.adjust(62, -1) } throws IllegalStateException("counter down")

        // when
        // then
        assertThatThrownBy { activities.remove(MEMBER, 62) }.isInstanceOf(IllegalStateException::class.java)
        assertThat(count("SELECT count(*) FROM pawbridge_user.favorites WHERE animal_id = 62")).isOne()
    }

    // ---- List -------------------------------------------------------------------------------------------------

    @Test
    @Tag("timezone-sensitive")
    fun `찜 목록이 있고 일부 동물이 사라졌을 때__목록을 조회하면__최신순으로 묶음 조회한 동물을 붙이고 없는 동물은 삭제됨으로 준다`() {
        // given
        insertFavorite(11, "2026-03-01 10:00:01")
        insertFavorite(12, "2026-03-02 10:00:01")
        insertFavorite(13, "2026-03-03 10:00:01")
        insertUser(OTHER, nickname = "다른회원")
        execute("INSERT INTO pawbridge_user.favorites(user_id,animal_id,created_at) VALUES (?,?,now())", OTHER, 14)
        every { animals.batch(any()) } returns listOf(animal(11, "믹스"), animal(12, "푸들"))

        // when
        val favorites = activities.favorites(MEMBER)

        // then
        verify(exactly = 1) { animals.batch(listOf(13L, 12L, 11L)) }
        assertThat(favorites.userId).isEqualTo(MEMBER)
        assertThat(favorites.totalCount).isEqualTo(3)
        val content = favorites.favorites!!
        assertThat(content.map { it.animalId }).containsExactly(13L, 12L, 11L)
        assertThat(content.map { it.status }).containsExactly("DELETED", "PROTECTING", "PROTECTING")
        assertThat(content.map { it.breed }).containsExactly(null, "푸들", "믹스")
        assertThat(content[0].createdAt.toString()).isEqualTo(shownInJvmZone("2026-03-03 10:00:01"))
        assertThat(content.map { it.favoriteId }.toSet()).hasSize(3)
    }

    @Test
    fun `찜이 없거나 회원이 없을 때__목록을 조회하면__동물 조회 없이 빈 목록이거나 회원 없음이다`() {
        // given
        // when
        val empty = activities.favorites(MEMBER)

        // then
        assertThat(empty.totalCount).isZero()
        assertThat(empty.favorites).isEmpty()
        assertThatThrownBy { activities.favorites(NOBODY) }.isInstanceOf(UserNotFoundException::class.java)
        verify(exactly = 0) { animals.batch(any()) }
    }

    @Test
    fun `동물 묶음 조회가 실패하거나 같은 동물을 두 번 줄 때__목록을 조회하면__삭제됨으로 바꾸지 않고 실패를 전파한다`() {
        // given
        insertFavorite(11, "2026-03-01 10:00:01")

        // when
        // then
        every { animals.batch(any()) } throws IllegalStateException("animal down")
        assertThatThrownBy { activities.favorites(MEMBER) }.isInstanceOf(IllegalStateException::class.java)
        every { animals.batch(any()) } returns listOf(animal(11, "믹스"), animal(11, "믹스"))
        assertThatThrownBy { activities.favorites(MEMBER) }.isInstanceOf(IllegalStateException::class.java)
        assertNoTransactionOpen()
    }

    // ---- Registered animals -----------------------------------------------------------------------------------

    @Test
    fun `승인된 보호소 회원일 때__등록 동물을 읽으면__등록번호로 보호소를 찾은 뒤 그 보호소의 동물 쪽을 준다`() {
        // given
        insertUser(SHELTER, role = "ROLE_SHELTER", careRegNo = "CARE-R", nickname = "보호소회원")
        val request = PageRequest(0, 20, "createdAt,desc")
        val page = AnimalPage(listOf(animal(21, "믹스")), 0, 20, 1, 1, true)
        every { animals.shelter("CARE-R") } returns Shelter(5, "행복 보호소")
        every { animals.byShelter(5, request) } returns page

        // when
        val registered = activities.registered(SHELTER, request)

        // then
        assertThat(registered).isEqualTo(page)
        verify(exactly = 1) { animals.shelter("CARE-R") }
        verify(exactly = 1) { animals.byShelter(5, request) }
    }

    @Test
    fun `회원이 없거나 보호소 회원이 아니거나 등록번호가 비었을 때__등록 동물을 읽으면__동물 조회 전에 거부한다`() {
        // given
        insertUser(OTHER, role = "ROLE_USER", careRegNo = "CARE-R", nickname = "일반회원")
        insertUser(SHELTER, role = "ROLE_SHELTER", careRegNo = "", nickname = "번호없음")
        insertUser(THIRD, role = "ROLE_SHELTER", careRegNo = null, nickname = "번호널")
        val request = PageRequest(0, 20, null)

        // when
        // then
        assertThatThrownBy { activities.registered(NOBODY, request) }.isInstanceOf(UserNotFoundException::class.java)
        assertThatThrownBy { activities.registered(OTHER, request) }.isInstanceOf(ShelterMembershipRequiredException::class.java)
        assertThatThrownBy { activities.registered(SHELTER, request) }.isInstanceOf(ShelterMembershipRequiredException::class.java)
        assertThatThrownBy { activities.registered(THIRD, request) }.isInstanceOf(ShelterMembershipRequiredException::class.java)
        verify(exactly = 0) { animals.shelter(any()) }
        assertNoTransactionOpen()
    }

    @Test
    fun `보호소 조회나 동물 쪽 조회가 실패하거나 비었을 때__등록 동물을 읽으면__빈 쪽으로 바꾸지 않고 실패로 전파한다`() {
        // given
        insertUser(SHELTER, role = "ROLE_SHELTER", careRegNo = "CARE-R", nickname = "보호소회원")
        val request = PageRequest(0, 20, null)

        // when
        // then
        every { animals.shelter("CARE-R") } throws IllegalStateException("animal down")
        assertThatThrownBy { activities.registered(SHELTER, request) }
            .isInstanceOf(IllegalStateException::class.java).hasMessage("Shelter lookup failed")
        every { animals.shelter("CARE-R") } returns Shelter(5, "행복 보호소")
        every { animals.byShelter(5, request) } returns null
        assertThatThrownBy { activities.registered(SHELTER, request) }
            .isInstanceOf(IllegalStateException::class.java).hasMessage("Animal page lookup failed")
    }

    private fun assertRejected(reason: Reason, action: () -> Any?) {
        assertThatThrownBy { action() }
            .isInstanceOfSatisfying(FavoriteActivityRejectedException::class.java) { assertThat(it.reason()).isEqualTo(reason) }
    }

    private fun insertFavorite(animalId: Long, createdAt: String) {
        execute("INSERT INTO pawbridge_user.favorites(user_id,animal_id,created_at) VALUES (?,?,?::timestamp)", MEMBER, animalId, createdAt)
    }

    private fun animal(id: Long, breed: String) = Animal(
        id, "NOTICE-$id", "개", breed, "수컷", 2022, 4, "특이사항", "PROTECTING", null, "https://image.invalid/$id", 3, 5, "행복 보호소",
        LocalDateTime.of(2026, 1, 1, 0, 0, 1),
    )

    private companion object {
        const val MEMBER = 9_971_001L
        const val OTHER = 9_971_002L
        const val SHELTER = 9_971_003L
        const val THIRD = 9_971_004L
        const val NOBODY = 9_971_900L
    }
}
