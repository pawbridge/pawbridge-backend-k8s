package com.pawbridge.backend.contract

import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership.Daily
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership.Member
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership.MissingShelter
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership.Shelter
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership.Status
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembershipRejectedException
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembershipRejectedException.Reason
import com.pawbridge.backend.user.internal.shelter.infrastructure.JdbcAnimalShelterMembers
import com.pawbridge.backend.user.internal.shelter.infrastructure.JdbcShelterMembership
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.function.Supplier
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/**
 * The shelter application repository called directly: submit, the two lists, detail, approve and reject (with the
 * promotion of the applicant), members and statistics. The shelter directory in the animal module is a stub here.
 */
class ShelterMembershipRepositoryContractTest : UserRepositoryContractBase() {
    private val now: LocalDateTime = LocalDateTime.of(2026, 3, 10, 12, 0, 0, 123_456_000)
    private val clock: Clock = Clock.fixed(Instant.parse("2026-03-10T03:00:00Z"), ZoneOffset.UTC)
    private var shelters = ShelterMembership.ShelterLookup { care -> Shelter(77, care) }
    private lateinit var memberships: JdbcShelterMembership

    @BeforeEach
    fun build() {
        insertUser(ADMIN, role = "ROLE_ADMIN", nickname = "관리자", email = "shelter-admin@fixture.invalid", name = "관리자이름")
        memberships = membership { care -> shelters.find(care) }
    }

    private fun membership(lookup: ShelterMembership.ShelterLookup): JdbcShelterMembership {
        val statistics = TransactionTemplate(manager)
        statistics.isReadOnly = true
        statistics.isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        return JdbcShelterMembership(write, writeTransaction(), readOnlyTransaction(), statistics, lookup, clock, Supplier { now })
    }

    // ---- Submit and own list ----------------------------------------------------------------------------------

    @Test
    @Tag("timezone-sensitive")
    fun `일반 회원이고 대기 신청이 없을 때__신청을 제출하면__앞뒤 공백을 뺀 이름으로 대기 신청 행을 남긴다`() {
        // given
        insertUser(APPLICANT)

        // when
        val application = memberships.submit(APPLICANT, "  행복 보호소 \n")

        // then
        val stored = row("pawbridge_user.shelter_applications", "id", application.id!!)
        assertThat(application.status).isEqualTo(Status.PENDING)
        assertThat(application.shelterName).isEqualTo("행복 보호소")
        assertThat(application.requestedAt).isEqualTo(now)
        assertThat(stored.get("user_id").asLong()).isEqualTo(APPLICANT)
        assertThat(stored.get("shelter_name").asText()).isEqualTo("행복 보호소")
        assertThat(stored.get("status").asText()).isEqualTo("PENDING")
        assertThat(stored.get("requested_at").asText()).isEqualTo(storedWallClock(now))
        assertThat(stored.get("reviewed_by").isNull).isTrue()
        assertThat(stored.get("care_reg_no").isNull).isTrue()
    }

    @Test
    fun `대기 신청이 있거나 일반 회원이 아니거나 이름이 틀렸을 때__신청을 제출하면__사유별로 거부하고 행을 늘리지 않는다`() {
        // given
        insertUser(APPLICANT)
        insertUser(OTHER, role = "ROLE_SHELTER", careRegNo = "CARE-X")
        insertApplication(APPLICANT)
        val before = ContractPostgres.snapshot()

        // when
        // then
        assertRejected(Reason.CONFLICT) { memberships.submit(APPLICANT, "또 신청") }
        assertRejected(Reason.FORBIDDEN) { memberships.submit(OTHER, "보호소 회원") }
        assertRejected(Reason.TOKEN_INVALID) { memberships.submit(NOBODY, "없는 회원") }
        insertUser(THIRD)
        assertRejected(Reason.INVALID) { memberships.submit(THIRD, "   ") }
        assertRejected(Reason.INVALID) { memberships.submit(THIRD, "가".repeat(101)) }
        assertThat(count("SELECT count(*) FROM pawbridge_user.shelter_applications")).isEqualTo(1)
        assertThat(ContractPostgres.snapshot().filterKeys { it != "pawbridge_user.users" } ==
            before.filterKeys { it != "pawbridge_user.users" }).isTrue()
    }

    @Test
    fun `여러 신청이 있을 때__내 신청을 조회하면__내 것만 최신순 쪽으로 주고 사유는 반려일 때만 보인다`() {
        // given
        insertUser(APPLICANT)
        insertUser(OTHER)
        val approved = insertApplication(APPLICANT, "APPROVED", reviewedBy = ADMIN, reviewedAt = "2026-03-02 03:00:00", note = "승인 메모", care = "CARE-A")
        val rejected = insertApplication(APPLICANT, "REJECTED", reviewedBy = ADMIN, reviewedAt = "2026-03-03 03:00:00", note = "반려 사유")
        val pending = insertApplication(APPLICANT)
        insertApplication(OTHER)

        // when
        val first = memberships.mine(APPLICANT, 0, 2)
        val second = memberships.mine(APPLICANT, 1, 2)

        // then
        assertThat(first.content.map { it.id }).containsExactly(pending, rejected)
        assertThat(first.total).isEqualTo(3)
        assertThat(second.content.map { it.id }).containsExactly(approved)
        assertThat(second.total).isEqualTo(3)
        assertThat(first.content[1].reason).isEqualTo("반려 사유")
        assertThat(first.content[1].reviewedBy).isEqualTo(ADMIN)
        assertThat(second.content[0].reason).`as`("An approval note is not shown to the applicant").isNull()
        assertThat(second.content[0].careRegNo).isEqualTo("CARE-A")
        assertRejected(Reason.TOKEN_INVALID) { memberships.mine(NOBODY, 0, 10) }
        assertRejected(Reason.INVALID) { memberships.mine(APPLICANT, 0, 101) }
        assertRejected(Reason.INVALID) { memberships.mine(APPLICANT, -1, 10) }
    }

    // ---- Administrator list and detail ------------------------------------------------------------------------

    @Test
    fun `신청자가 있거나 탈퇴한 신청들일 때__관리자가 목록을 조회하면__상태로 거르고 신청자 정보를 붙인다`() {
        // given
        insertUser(APPLICANT, email = "applicant@fixture.invalid", name = "신청자")
        insertUser(OTHER, role = "ROLE_USER")
        val approved = insertApplication(APPLICANT, "APPROVED", reviewedBy = ADMIN, reviewedAt = "2026-03-02 03:00:00", note = "메모", care = "CARE-A")
        val pending = insertApplication(OTHER)
        val orphan = insertApplication(NOBODY, "REJECTED", reviewedBy = ADMIN, reviewedAt = "2026-03-03 03:00:00", note = "탈퇴 전 반려")

        // when
        val all = memberships.list(ADMIN, null, 0, 10)
        val rejectedOnly = memberships.list(ADMIN, Status.REJECTED, 0, 10)
        val secondPage = memberships.list(ADMIN, null, 1, 2)

        // then
        assertThat(all.content.map { it.application!!.id }).containsExactly(orphan, pending, approved)
        assertThat(all.total).isEqualTo(3)
        val approvedView = all.content[2]
        assertThat(approvedView.applicantName).isEqualTo("신청자")
        assertThat(approvedView.applicantEmail).isEqualTo("applicant@fixture.invalid")
        assertThat(approvedView.reviewNote).isEqualTo("메모")
        assertThat(all.content[0].applicantName).isEqualTo("탈퇴 회원")
        assertThat(all.content[0].applicantEmail).isNull()
        assertThat(rejectedOnly.content.map { it.application!!.id }).containsExactly(orphan)
        assertThat(rejectedOnly.total).isOne()
        assertThat(secondPage.content.map { it.application!!.id }).containsExactly(approved)
        assertThat(secondPage.total).isEqualTo(3)
        assertRejected(Reason.FORBIDDEN) { memberships.list(APPLICANT, null, 0, 10) }
        assertRejected(Reason.TOKEN_INVALID) { memberships.list(NOBODY, null, 0, 10) }
    }

    @Test
    fun `관리자일 때__신청 상세를 조회하면__신청자 정보를 붙여 주고 없는 신청은 찾을 수 없다`() {
        // given
        insertUser(APPLICANT, email = "detail@fixture.invalid", name = "상세신청자")
        val id = insertApplication(APPLICANT)

        // when
        val detail = memberships.detail(ADMIN, id)

        // then
        assertThat(detail.application!!.id).isEqualTo(id)
        assertThat(detail.application!!.shelterName).isEqualTo("보호소")
        assertThat(detail.applicantName).isEqualTo("상세신청자")
        assertThat(detail.applicantEmail).isEqualTo("detail@fixture.invalid")
        assertRejected(Reason.NOT_FOUND) { memberships.detail(ADMIN, id + 1000) }
        assertRejected(Reason.FORBIDDEN) { memberships.detail(APPLICANT, id) }
    }

    // ---- Approve and reject -----------------------------------------------------------------------------------

    @Test
    @Tag("timezone-sensitive")
    fun `대기 중인 일반 회원의 신청일 때__승인하면__신청을 승인으로 남기고 회원을 보호소 역할과 등록번호로 올린다`() {
        // given
        insertUser(APPLICANT, email = "approve@fixture.invalid")
        insertUser(OTHER)
        val id = insertApplication(APPLICANT)
        val otherApplication = insertApplication(OTHER)
        val bystander = userRow(OTHER)
        val askedFor = ArrayList<String?>()
        val approving = membership { care -> askedFor.add(care); Shelter(77, care) }

        // when
        val approved = approving.approve(ADMIN, id, " CARE-77 ", " 확인 완료 ")

        // then
        val application = row("pawbridge_user.shelter_applications", "id", id)
        assertThat(askedFor).containsExactly("CARE-77")
        assertThat(approved.application!!.status).isEqualTo(Status.APPROVED)
        assertThat(approved.application!!.careRegNo).isEqualTo("CARE-77")
        assertThat(approved.reviewNote).isEqualTo("확인 완료")
        assertThat(application.get("status").asText()).isEqualTo("APPROVED")
        assertThat(application.get("reviewed_by").asLong()).isEqualTo(ADMIN)
        assertThat(application.get("reviewed_at").asText()).isEqualTo(storedWallClock(now))
        assertThat(application.get("review_note").asText()).isEqualTo("확인 완료")
        assertThat(application.get("care_reg_no").asText()).isEqualTo("CARE-77")
        val promoted = userRow(APPLICANT)
        assertThat(promoted.get("role").asText()).isEqualTo("ROLE_SHELTER")
        assertThat(promoted.get("care_reg_no").asText()).isEqualTo("CARE-77")
        assertThat(promoted.get("updated_at").asText()).isEqualTo(storedWallClock(now))
        assertThat(userRow(OTHER)).isEqualTo(bystander)
        assertThat(row("pawbridge_user.shelter_applications", "id", otherApplication).get("status").asText()).isEqualTo("PENDING")
    }

    @Test
    fun `처리됐거나 없거나 역할이 달라졌거나 보호소를 확인할 수 없을 때__승인하면__사유별로 거부하고 아무것도 바뀌지 않는다`() {
        // given
        insertUser(APPLICANT)
        insertUser(OTHER, role = "ROLE_SHELTER", careRegNo = "CARE-X")
        insertUser(THIRD)
        val processed = insertApplication(APPLICANT, "REJECTED", reviewedBy = ADMIN, reviewedAt = "2026-03-02 03:00:00", note = "이미 반려")
        val promotedAlready = insertApplication(OTHER)
        val pending = insertApplication(THIRD)
        val before = ContractPostgres.snapshot()

        // when
        // then
        assertRejected(Reason.CONFLICT) { memberships.approve(ADMIN, processed, "CARE-1", "메모") }
        assertRejected(Reason.CONFLICT) { memberships.approve(ADMIN, promotedAlready, "CARE-1", "메모") }
        assertRejected(Reason.NOT_FOUND) { memberships.approve(ADMIN, pending + 1000, "CARE-1", "메모") }
        assertRejected(Reason.FORBIDDEN) { memberships.approve(APPLICANT, pending, "CARE-1", "메모") }
        assertRejected(Reason.INVALID) { memberships.approve(ADMIN, pending, "  ", "메모") }
        assertRejected(Reason.SHELTER_NOT_FOUND) { membership { throw MissingShelter() }.approve(ADMIN, pending, "CARE-1", "메모") }
        assertRejected(Reason.SHELTER_UNAVAILABLE) { membership { throw IllegalStateException("down") }.approve(ADMIN, pending, "CARE-1", "메모") }
        assertRejected(Reason.SHELTER_UNAVAILABLE) { membership { Shelter(77, "OTHER-CARE") }.approve(ADMIN, pending, "CARE-1", "메모") }
        assertRejected(Reason.SHELTER_UNAVAILABLE) { membership { Shelter(null, "CARE-1") }.approve(ADMIN, pending, "CARE-1", "메모") }
        assertThat(ContractPostgres.snapshot() == before).isTrue()
    }

    @Test
    fun `신청자 행이 다른 연결에 잠겨 있을 때__승인하면__잠금이 풀릴 때까지 기다린 뒤 승인한다`() {
        // given
        insertUser(APPLICANT)
        val id = insertApplication(APPLICANT)

        // when
        val approved = assertWaitsForUserRowLock(APPLICANT, Callable { memberships.approve(ADMIN, id, "CARE-LOCK", "대기 후 승인") })

        // then
        assertThat(approved.application!!.status).isEqualTo(Status.APPROVED)
        assertThat(userRow(APPLICANT).get("role").asText()).isEqualTo("ROLE_SHELTER")
    }

    @Test
    @Tag("timezone-sensitive")
    fun `대기 중인 신청일 때__반려하면__반려와 사유만 남기고 회원 역할은 건드리지 않는다`() {
        // given
        insertUser(APPLICANT)
        val id = insertApplication(APPLICANT)
        val applicant = userRow(APPLICANT)

        // when
        val rejected = memberships.reject(ADMIN, id, " 서류 부족 ")

        // then
        val application = row("pawbridge_user.shelter_applications", "id", id)
        assertThat(rejected.application!!.status).isEqualTo(Status.REJECTED)
        assertThat(rejected.application!!.reason).isEqualTo("서류 부족")
        assertThat(application.get("status").asText()).isEqualTo("REJECTED")
        assertThat(application.get("reviewed_by").asLong()).isEqualTo(ADMIN)
        assertThat(application.get("reviewed_at").asText()).isEqualTo(storedWallClock(now))
        assertThat(application.get("review_note").asText()).isEqualTo("서류 부족")
        assertThat(application.get("care_reg_no").isNull).isTrue()
        assertThat(userRow(APPLICANT)).isEqualTo(applicant)
    }

    @Test
    fun `이미 처리됐거나 사유가 비었을 때__반려하면__거부하고 아무것도 바뀌지 않는다`() {
        // given
        insertUser(APPLICANT)
        val processed = insertApplication(APPLICANT, "APPROVED", reviewedBy = ADMIN, reviewedAt = "2026-03-02 03:00:00", note = "승인", care = "CARE-A")
        insertUser(OTHER)
        val pending = insertApplication(OTHER)
        val before = ContractPostgres.snapshot()

        // when
        // then
        assertRejected(Reason.CONFLICT) { memberships.reject(ADMIN, processed, "늦은 반려") }
        assertRejected(Reason.INVALID) { memberships.reject(ADMIN, pending, " ") }
        assertRejected(Reason.NOT_FOUND) { memberships.reject(ADMIN, pending + 1000, "사유") }
        assertThat(ContractPostgres.snapshot() == before).isTrue()
    }

    // ---- Members and statistics -------------------------------------------------------------------------------

    @Test
    fun `보호소 회원과 일반 회원이 섞여 있을 때__보호소 회원을 조회하면__해당 등록번호의 보호소 회원만 최신 승인 신청과 함께 준다`() {
        // given
        insertUser(MEMBER_ONE, role = "ROLE_SHELTER", careRegNo = "CARE-M", email = "m1@fixture.invalid", name = "첫째")
        insertUser(MEMBER_TWO, role = "ROLE_SHELTER", careRegNo = "CARE-M", email = "m2@fixture.invalid", name = "둘째")
        insertUser(APPLICANT, role = "ROLE_USER", careRegNo = "CARE-M")
        insertUser(OTHER, role = "ROLE_SHELTER", careRegNo = "CARE-OTHER")
        insertApplication(MEMBER_ONE, "APPROVED", reviewedBy = ADMIN, reviewedAt = "2026-03-01 03:00:00", care = "CARE-M")
        val latest = insertApplication(MEMBER_ONE, "APPROVED", reviewedBy = ADMIN, reviewedAt = "2026-03-02 03:00:00", care = "CARE-M")
        insertApplication(MEMBER_TWO, "APPROVED", reviewedBy = ADMIN, reviewedAt = "2026-03-02 03:00:00", care = "CARE-OTHER")

        // when
        val all = memberships.members(ADMIN, " CARE-M ", 0, 10)
        val firstPage = memberships.members(ADMIN, "CARE-M", 0, 1)

        // then
        assertThat(all.content).containsExactly(
            Member(MEMBER_TWO, "둘째", "m2@fixture.invalid", null),
            Member(MEMBER_ONE, "첫째", "m1@fixture.invalid", latest),
        )
        assertThat(all.total).isEqualTo(2)
        assertThat(firstPage.content.map { it.userId }).containsExactly(MEMBER_TWO)
        assertThat(firstPage.total).isEqualTo(2)
        assertRejected(Reason.FORBIDDEN) { memberships.members(APPLICANT, "CARE-M", 0, 10) }
        assertRejected(Reason.INVALID) { memberships.members(ADMIN, " ", 0, 10) }
    }

    @Test
    @Tag("timezone-sensitive")
    fun `기간 안팎의 신청과 처리가 섞여 있을 때__통계를 읽으면__서울 날짜로 묶은 일별 건수와 전날 건수와 현재 대기와 처리 건수를 준다`() {
        // given
        insertUser(APPLICANT)
        insertApplication(APPLICANT, requestedAt = "2026-03-06 03:00:00")
        insertApplication(NOBODY, "APPROVED", requestedAt = "2026-03-07 03:00:00", reviewedBy = ADMIN, reviewedAt = "2026-03-07 03:00:00", care = "C")
        insertApplication(NOBODY + 1, "APPROVED", requestedAt = "2026-03-08 03:00:00", reviewedBy = ADMIN, reviewedAt = "2026-03-08 03:00:00", care = "C")
        insertApplication(NOBODY + 2, "APPROVED", requestedAt = "2026-03-08 03:00:00", reviewedBy = ADMIN, reviewedAt = "2026-03-08 03:00:00", care = "C")
        insertApplication(NOBODY + 3, "REJECTED", requestedAt = "2026-03-10 03:00:00", reviewedBy = ADMIN, reviewedAt = "2026-03-09 03:00:00", note = "반려")

        // when
        val statistics = memberships.statistics(ADMIN, LocalDate.of(2026, 3, 8), LocalDate.of(2026, 3, 10))

        // then
        assertThat(statistics.daily).containsExactly(Daily(LocalDate.of(2026, 3, 8), 2), Daily(LocalDate.of(2026, 3, 10), 1))
        assertThat(statistics.previousDayCount).isOne()
        assertThat(statistics.currentPending).isOne()
        assertThat(statistics.approvedCount).isEqualTo(2)
        assertThat(statistics.rejectedCount).isOne()
    }

    @Test
    fun `거꾸로이거나 미래이거나 너무 긴 기간일 때__통계를 읽으면__거부하고 관리자가 아니면 권한 거부다`() {
        // given
        insertUser(APPLICANT)

        // when
        // then
        assertRejected(Reason.INVALID_INPUT) { memberships.statistics(ADMIN, LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 9)) }
        assertRejected(Reason.INVALID_INPUT) { memberships.statistics(ADMIN, LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 11)) }
        assertRejected(Reason.INVALID_INPUT) { memberships.statistics(ADMIN, LocalDate.of(2025, 3, 9), LocalDate.of(2026, 3, 10)) }
        assertRejected(Reason.INVALID_INPUT) { memberships.statistics(ADMIN, null, LocalDate.of(2026, 3, 10)) }
        assertRejected(Reason.FORBIDDEN) { memberships.statistics(APPLICANT, LocalDate.of(2026, 3, 9), LocalDate.of(2026, 3, 10)) }
        assertThat(memberships.statistics(ADMIN, LocalDate.of(2025, 3, 10), LocalDate.of(2026, 3, 10)).daily).isEmpty()
    }

    // ---- Shelter membership observation -----------------------------------------------------------------------

    @Test
    fun `등록번호를 가진 회원이 있거나 없을 때__쓰기 트랜잭션에서 관찰하면__역할과 상관없이 연관의 존재를 준다`() {
        // given
        insertUser(APPLICANT, role = "ROLE_USER", careRegNo = "CARE-OBSERVED")
        val members = JdbcAnimalShelterMembers(write)

        // when
        val present = writeTransaction().execute { members.exists("CARE-OBSERVED") }
        val absent = writeTransaction().execute { members.exists("CARE-NONE") }

        // then
        assertThat(present).isTrue()
        assertThat(absent).isFalse()
    }

    @Test
    fun `쓰기 트랜잭션이 아닐 때__관찰하면__데이터베이스를 묻기 전에 거부한다`() {
        // given
        val members = JdbcAnimalShelterMembers(write)

        // when
        // then
        assertThatThrownBy { members.exists("CARE-OBSERVED") }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { readOnlyTransaction().execute { members.exists("CARE-OBSERVED") } }
            .isInstanceOf(IllegalStateException::class.java)
        assertNoTransactionOpen()
    }

    private fun assertRejected(reason: Reason, action: () -> Any?) {
        assertThatThrownBy { action() }
            .isInstanceOfSatisfying(ShelterMembershipRejectedException::class.java) { assertThat(it.reason()).isEqualTo(reason) }
    }

    private fun insertApplication(
        userId: Long,
        status: String = "PENDING",
        name: String = "보호소",
        requestedAt: String = "2026-03-01 03:00:00",
        reviewedBy: Long? = null,
        reviewedAt: String? = null,
        note: String? = null,
        care: String? = null,
    ): Long {
        return ContractPostgres.admin.queryForObject(
            "INSERT INTO pawbridge_user.shelter_applications(user_id,shelter_name,status,requested_at,reviewed_by,reviewed_at," +
                "review_note,care_reg_no) VALUES (?,?,?,?::timestamp,?,?::timestamp,?,?) RETURNING id",
            Long::class.javaObjectType,
            userId, name, status, requestedAt, reviewedBy, reviewedAt, note, care,
        )!!
    }

    private companion object {
        const val ADMIN = 9_951_001L
        const val APPLICANT = 9_951_002L
        const val OTHER = 9_951_003L
        const val THIRD = 9_951_004L
        const val MEMBER_ONE = 9_951_005L
        const val MEMBER_TWO = 9_951_006L
        const val NOBODY = 9_951_900L
    }
}
