package com.pawbridge.backend.contract

import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberUnavailableException
import com.pawbridge.backend.user.api.UserNicknameUnavailableException
import com.pawbridge.backend.user.internal.contact.infrastructure.JdbcContactMemberQuery
import com.pawbridge.backend.user.internal.contact.infrastructure.JdbcContactMembersQuery
import com.pawbridge.backend.user.internal.directory.application.UserDirectoryQuery.Order
import com.pawbridge.backend.user.internal.directory.application.UserDirectoryQuery.Request
import com.pawbridge.backend.user.internal.directory.infrastructure.JdbcUserDirectoryQuery
import com.pawbridge.backend.user.internal.favorite.infrastructure.JdbcFavoriteStatusQuery
import com.pawbridge.backend.user.internal.nickname.infrastructure.JdbcUserNicknameQuery
import com.pawbridge.backend.user.internal.nickname.infrastructure.JdbcUserNicknameStore
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import com.pawbridge.backend.user.internal.profile.infrastructure.JdbcUserProfileQuery
import com.pawbridge.backend.user.internal.statistics.application.SignupStatisticsQuery.DailySignupStats
import com.pawbridge.backend.user.internal.statistics.infrastructure.JdbcSignupStatisticsQuery
import com.pawbridge.backend.user.internal.statistics.infrastructure.JdbcTotalUserCountQuery
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.function.Supplier
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.dao.InvalidDataAccessApiUsageException

/**
 * Read-side user repositories called directly: nickname and profile lookups, the contact projections, the favorite
 * status, the member counts, the administrator directory page and the signup statistics.
 */
class UserQueryRepositoryContractTest : UserRepositoryContractBase() {
    private val modified: LocalDateTime = LocalDateTime.of(2026, 3, 5, 10, 11, 12, 345_678_000)

    // ---- Nickname ---------------------------------------------------------------------------------------------

    @Test
    fun `닉네임이 있는 회원과 없는 회원일 때__닉네임 저장소로 조회하고 중복을 확인하면__대소문자를 무시하고 본인도 센다`() {
        // given
        insertUser(9_941_001, nickname = "MixedCase")
        val nicknames = JdbcUserNicknameStore(write)

        // when
        // then
        assertThat(nicknames.findNickname(9_941_001).get()).isEqualTo("MixedCase")
        assertThat(nicknames.findNickname(9_941_999)).isEmpty()
        assertThat(nicknames.existsByNickname("mixedcase")).isTrue()
        assertThat(nicknames.existsByNickname("MIXEDCASE")).isTrue()
        assertThat(nicknames.existsByNickname("OtherName")).isFalse()
    }

    @Test
    @Tag("timezone-sensitive")
    fun `회원이 있을 때__닉네임을 바꾸면__닉네임과 수정 시각만 바뀌고 없는 회원은 예기치 않은 실패다`() {
        // given
        insertUser(9_941_002, nickname = "BeforeName")
        insertUser(9_941_003, nickname = "Bystander")
        val before = userRow(9_941_002)
        val bystander = userRow(9_941_003)
        val nicknames = JdbcUserNicknameStore(write, Supplier { modified })

        // when
        nicknames.updateNickname(9_941_002, "AfterName")

        // then
        val after = userRow(9_941_002)
        assertThat(after.get("nickname").asText()).isEqualTo("AfterName")
        assertThat(after.get("updated_at").asText()).isEqualTo(storedWallClock(modified))
        assertThat(omitting(after, "nickname", "updated_at")).isEqualTo(omitting(before, "nickname", "updated_at"))
        assertThat(userRow(9_941_003)).isEqualTo(bystander)
        assertThatThrownBy { nicknames.updateNickname(9_941_999, "Nobody") }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `닉네임 열을 읽을 수 없을 때__닉네임 질의로 조회하면__조회 불가 예외이고 복구되면 값을 준다`() {
        // given
        insertUser(9_941_004, nickname = "QueryName")
        val query = JdbcUserNicknameQuery(write, readOnlyTransaction())

        // when
        // then
        assertThat(query.findNickname(9_941_004).get()).isEqualTo("QueryName")
        assertThat(query.findNickname(9_941_999)).isEmpty()
        ContractPostgres.withRevoked("SELECT (nickname)", "pawbridge_user.users") {
            assertThatThrownBy { query.findNickname(9_941_004) }.isInstanceOf(UserNicknameUnavailableException::class.java)
        }
        assertThat(query.findNickname(9_941_004).get()).isEqualTo("QueryName")
    }

    // ---- Profile ----------------------------------------------------------------------------------------------

    @Test
    @Tag("timezone-sensitive")
    fun `저장된 프로필일 때__필수 조회하면__일곱 필드를 그대로 주고 가입 시각이 없으면 null이다`() {
        // given
        insertUser(9_942_001, email = "profile@fixture.invalid", provider = "GOOGLE", providerId = "g-profile", role = "ROLE_ADMIN")
        insertUser(9_942_002, createdAt = null)
        val profiles = JdbcUserProfileQuery(write, readOnlyTransaction())

        // when
        val profile = profiles.require(9_942_001)
        val withoutTime = profiles.require(9_942_002)

        // then
        assertThat(profile.userId).isEqualTo(9_942_001)
        assertThat(profile.email).isEqualTo("profile@fixture.invalid")
        assertThat(profile.name).isEqualTo("이름9942001")
        assertThat(profile.nickname).isEqualTo("회원9942001")
        assertThat(profile.provider).isEqualTo("GOOGLE")
        assertThat(profile.role).isEqualTo("ROLE_ADMIN")
        assertThat(profile.createdAt.toString()).isEqualTo(shownInJvmZone("2025-01-01 01:02:03.123456"))
        assertThat(withoutTime.createdAt).isNull()
    }

    @Test
    fun `회원이 없을 때__필수 조회하면__찾을 수 없음 예외이고 다음 조회는 계속된다`() {
        // given
        insertUser(9_942_003)
        val profiles = JdbcUserProfileQuery(write, readOnlyTransaction())

        // when
        // then
        assertThatThrownBy { profiles.require(9_942_999) }.isInstanceOf(UserNotFoundException::class.java)
        assertThat(profiles.require(9_942_003).userId).isEqualTo(9_942_003)
        assertNoTransactionOpen()
    }

    // ---- Contact projections ----------------------------------------------------------------------------------

    @Test
    fun `활성 회원과 삭제 대기 회원과 없는 회원일 때__연락처를 한 명 조회하면__닉네임과 상태를 각각 맞게 준다`() {
        // given
        insertUser(9_943_001, nickname = "ActiveContact")
        insertUser(9_943_002, nickname = "PendingContact")
        execute("INSERT INTO pawbridge_user.contact_deletions(user_id) VALUES (?)", 9_943_002)
        val contacts = JdbcContactMemberQuery(write, readOnlyTransaction())

        // when
        // then
        assertThat(contacts.get(9_943_001)).isEqualTo(ContactMember(9_943_001, "ActiveContact", true, false))
        assertThat(contacts.get(9_943_002)).isEqualTo(ContactMember(9_943_002, "탈퇴한 회원", false, true))
        assertThat(contacts.get(9_943_999)).isEqualTo(ContactMember(9_943_999, "탈퇴한 회원", false, false))
    }

    @Test
    fun `정렬되지 않은 활성 대기 없음 중복 아이디일 때__연락처를 여럿 조회하면__입력 순서로 처음 나온 아이디마다 하나를 준다`() {
        // given
        insertUser(9_943_003, nickname = "First")
        insertUser(9_943_004, nickname = "Second")
        insertUser(9_943_005, nickname = "Gone")
        execute("INSERT INTO pawbridge_user.contact_deletions(user_id) VALUES (?)", 9_943_005)
        val contacts = JdbcContactMembersQuery(write, readOnlyTransaction())

        // when
        val members = contacts.getAll(listOf(9_943_004L, 9_943_999L, 9_943_003L, 9_943_004L, 9_943_005L))

        // then
        assertThat(members).containsExactly(
            ContactMember(9_943_004, "Second", true, false),
            ContactMember(9_943_999, "탈퇴한 회원", false, false),
            ContactMember(9_943_003, "First", true, false),
            ContactMember(9_943_005, "탈퇴한 회원", false, true),
        )
    }

    @Test
    fun `범위를 벗어난 아이디 목록일 때__연락처를 여럿 조회하면__데이터베이스 없이 거부하고 정확히 스물한 개는 받는다`() {
        // given
        val contacts = JdbcContactMembersQuery(write, readOnlyTransaction())
        val twentyOne = (1L..21L).map { 9_944_000L + it }

        // when
        // then
        assertThatThrownBy { contacts.getAll(null) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { contacts.getAll(emptyList()) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { contacts.getAll(listOf(1L, 0L)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { contacts.getAll(listOf(1L, null)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { contacts.getAll(twentyOne + 9_944_100L) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(contacts.getAll(twentyOne)).hasSize(21)
    }

    @Test
    fun `삭제 대기 표를 읽을 수 없을 때__연락처를 조회하면__조회 불가 예외이고 복구되면 조회된다`() {
        // given
        insertUser(9_943_006, nickname = "Recovering")
        val one = JdbcContactMemberQuery(write, readOnlyTransaction())
        val many = JdbcContactMembersQuery(write, readOnlyTransaction())

        // when
        // then
        ContractPostgres.withRevoked("SELECT (user_id)", "pawbridge_user.contact_deletions") {
            assertThatThrownBy { one.get(9_943_006) }.isInstanceOf(ContactMemberUnavailableException::class.java)
            assertThatThrownBy { many.getAll(listOf(9_943_006L)) }.isInstanceOf(ContactMemberUnavailableException::class.java)
        }
        assertThat(one.get(9_943_006).nickname).isEqualTo("Recovering")
        assertThat(many.getAll(listOf(9_943_006L)).single().nickname).isEqualTo("Recovering")
    }

    // ---- Favorite status and counts ---------------------------------------------------------------------------

    @Test
    fun `즐겨찾기한 동물과 다른 동물과 다른 회원일 때__상태를 확인하면__정확한 쌍만 참이다`() {
        // given
        insertUser(9_945_001)
        insertUser(9_945_002)
        execute("INSERT INTO pawbridge_user.favorites(user_id,animal_id,created_at) VALUES (?,?,now())", 9_945_001, 77)
        val favorites = JdbcFavoriteStatusQuery(write, readOnlyTransaction())

        // when
        // then
        assertThat(favorites.isFavorite(9_945_001, 77)).isTrue()
        assertThat(favorites.isFavorite(9_945_001, 78)).isFalse()
        assertThat(favorites.isFavorite(9_945_002, 77)).isFalse()
        assertThat(favorites.isFavorite(9_945_001, 0)).isFalse()
        assertThat(favorites.isFavorite(9_945_001, -1)).isFalse()
    }

    @Test
    fun `회원이 늘어날 때__전체 회원 수를 세면__행 수를 그대로 주고 int 범위를 가정하지 않는다`() {
        // given
        val counts = JdbcTotalUserCountQuery(write, readOnlyTransaction())
        val before = counts.count()
        insertUser(9_946_001)
        insertUser(9_946_002)

        // when
        val after = counts.count()

        // then
        assertThat(before).isEqualTo(userCount() - 2)
        assertThat(after).isEqualTo(before + 2)
    }

    // ---- Directory --------------------------------------------------------------------------------------------

    @Test
    fun `키워드가 이메일 이름 닉네임에 걸릴 때__목록을 조회하면__대소문자를 무시하고 역할과 함께 걸러 일곱 필드를 준다`() {
        // given
        seedDirectory()
        val directory = JdbcUserDirectoryQuery(write, readOnlyTransaction())
        val byId = Request(0, 10, listOf(Order("userId", true, false)))

        // when
        val byEmail = directory.page("DIRFX-ALICE", null, byId)
        val byName = directory.page("kim", null, byId)
        val byNickname = directory.page("catnick", null, byId)
        val withRole = directory.page("dirfx", "ROLE_SHELTER", byId)
        val wildcard = directory.page("dirfx-_lice", null, byId)

        // then
        assertThat(byEmail.content().map { it.userId }).containsExactly(9_947_001L)
        assertThat(byEmail.total()).isOne()
        val alice = byEmail.content().single()
        assertThat(alice.email).isEqualTo("dirfx-alice@fixture.invalid")
        assertThat(alice.name).isEqualTo("Alice Kim")
        assertThat(alice.nickname).isEqualTo("앨리스")
        assertThat(alice.provider).isEqualTo("LOCAL")
        assertThat(alice.role).isEqualTo("ROLE_USER")
        assertThat(alice.createdAt.toString()).isEqualTo(shownInJvmZone("2025-01-01 00:00:01"))
        assertThat(byName.content().map { it.userId }).containsExactly(9_947_001L)
        assertThat(byNickname.content().map { it.userId }).containsExactly(9_947_002L)
        assertThat(withRole.content().map { it.userId }).containsExactly(9_947_003L)
        assertThat(wildcard.content().map { it.userId }).`as`("An underscore is a wildcard, not escaped text").containsExactly(9_947_001L)
    }

    @Test
    fun `정렬 방향이 주어졌을 때__목록을 조회하면__지정한 열과 방향대로 준다`() {
        // given
        seedDirectory()
        val directory = JdbcUserDirectoryQuery(write, readOnlyTransaction())

        // when
        val newestFirst = directory.page("dirfx", null, Request(0, 10, listOf(Order("createdAt", false, false))))
        val emailAscending = directory.page("dirfx", null, Request(0, 10, listOf(Order("email", true, true))))
        val roleThenId = directory.page("dirfx", null, Request(0, 10, listOf(Order("role", false, false), Order("userId", false, false))))

        // then
        assertThat(newestFirst.content().map { it.userId }).containsExactly(9_947_005L, 9_947_004L, 9_947_003L, 9_947_002L, 9_947_001L)
        assertThat(emailAscending.content().map { it.userId }).containsExactly(9_947_001L, 9_947_002L, 9_947_003L, 9_947_004L, 9_947_005L)
        assertThat(roleThenId.content().map { it.userId }).containsExactly(9_947_005L, 9_947_004L, 9_947_001L, 9_947_003L, 9_947_002L)
    }

    @Test
    fun `다섯 명의 목록일 때__쪽을 나눠 조회하면__꽉 찬 쪽과 마지막 쪽과 빈 쪽 모두 같은 전체 수를 준다`() {
        // given
        seedDirectory()
        val directory = JdbcUserDirectoryQuery(write, readOnlyTransaction())
        val order = listOf(Order("userId", true, false))

        // when
        val first = directory.page("dirfx", null, Request(0, 2, order))
        val last = directory.page("dirfx", null, Request(2, 2, order))
        val beyond = directory.page("dirfx", null, Request(5, 2, order))
        val short = directory.page("dirfx", null, Request(0, 10, order))

        // then
        assertThat(first.content().map { it.userId }).containsExactly(9_947_001L, 9_947_002L)
        assertThat(first.total()).isEqualTo(5)
        assertThat(last.content().map { it.userId }).containsExactly(9_947_005L)
        assertThat(last.total()).isEqualTo(5)
        assertThat(beyond.content()).isEmpty()
        assertThat(beyond.total()).isEqualTo(5)
        assertThat(short.content()).hasSize(5)
        assertThat(short.total()).isEqualTo(5)
    }

    @Test
    fun `열람할 수 없는 열이나 너무 큰 쪽일 때__목록을 조회하면__데이터베이스에 묻기 전에 거부한다`() {
        // given
        val directory = JdbcUserDirectoryQuery(write, readOnlyTransaction())

        // when
        // then
        assertThatThrownBy { directory.page(null, null, Request(0, 10, listOf(Order("password", true, false)))) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { directory.page(null, null, Request(Int.MAX_VALUE, 100, emptyList())) }
            .isInstanceOf(InvalidDataAccessApiUsageException::class.java)
        assertNoTransactionOpen()
    }

    // ---- Signup statistics ------------------------------------------------------------------------------------

    @Test
    fun `서울 날짜 경계의 가입 시각일 때__일별로 조회하면__서울 날짜로 묶은 행만 날짜순으로 주고 0을 채우지 않는다`() {
        // given
        seedSignups()
        val statistics = JdbcSignupStatisticsQuery(write, readOnlyTransaction(), Clock.systemUTC())

        // when
        val daily = statistics.daily(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 3))
        val reversed = statistics.daily(LocalDate.of(2026, 3, 3), LocalDate.of(2026, 3, 1))

        // then
        assertThat(daily).containsExactly(
            DailySignupStats(LocalDate.of(2026, 3, 1), 1),
            DailySignupStats(LocalDate.of(2026, 3, 2), 2),
        )
        assertThat(reversed).isEmpty()
    }

    @Test
    fun `기간 안에 듬성한 가입이 있을 때__기간별로 조회하면__서울의 오늘 기준으로 빠진 날을 0으로 채운다`() {
        // given
        seedSignups()
        val clock = Clock.fixed(Instant.parse("2026-03-04T05:00:00Z"), ZoneOffset.UTC)
        val statistics = JdbcSignupStatisticsQuery(write, readOnlyTransaction(), clock)

        // when
        val periods = statistics.periods()

        // then
        assertThat(periods.today).containsExactly(DailySignupStats(LocalDate.of(2026, 3, 4), 1))
        assertThat(periods.last7Days).hasSize(7)
        assertThat(periods.last7Days!!.first().date).isEqualTo(LocalDate.of(2026, 2, 26))
        assertThat(periods.last7Days!!.map { it.count }).containsExactly(0L, 0L, 0L, 1L, 2L, 0L, 1L)
        assertThat(periods.last30Days).hasSize(30)
        assertThat(periods.last30Days!!.sumOf { it.count!! }).isEqualTo(4)
        assertThat(periods.thisMonth!!.map { it.count }).containsExactly(1L, 2L, 0L, 1L)
    }

    private fun seedDirectory() {
        insertUser(9_947_001, email = "dirfx-alice@fixture.invalid", name = "Alice Kim", nickname = "앨리스", createdAt = "2025-01-01 00:00:01")
        insertUser(9_947_002, email = "dirfx-bob@Example.invalid", name = "bob lee", nickname = "BobCatNick", provider = "GOOGLE", providerId = "g-bob", role = "ROLE_ADMIN", createdAt = "2025-01-02 00:00:00")
        insertUser(9_947_003, email = "dirfx-carol@fixture.invalid", name = "Carol", nickname = "carolnick", role = "ROLE_SHELTER", createdAt = "2025-01-03 00:00:00")
        insertUser(9_947_004, email = "dirfx-dave@fixture.invalid", name = "dave", nickname = "davenick", role = "ROLE_USER", createdAt = "2025-01-04 00:00:00")
        insertUser(9_947_005, email = "dirfx-erin@fixture.invalid", name = "Erin", nickname = "erinnick", role = "ROLE_USER", createdAt = "2025-01-05 00:00:00")
    }

    private fun seedSignups() {
        insertUser(9_948_001, createdAt = "2026-03-01 14:59:59.999999")
        insertUser(9_948_002, createdAt = "2026-03-01 15:00:00")
        insertUser(9_948_003, createdAt = "2026-03-02 03:00:00")
        insertUser(9_948_004, createdAt = "2026-03-04 03:00:00")
        insertUser(9_948_005, createdAt = null)
    }
}
