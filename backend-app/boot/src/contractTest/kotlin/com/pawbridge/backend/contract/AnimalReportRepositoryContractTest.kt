package com.pawbridge.backend.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.community.internal.post.application.PostMediaRemover
import com.pawbridge.backend.community.internal.post.application.PostMediaUploader
import com.pawbridge.backend.community.internal.post.application.PostUpload
import com.pawbridge.backend.community.internal.report.application.AnimalReports.AnimalType
import com.pawbridge.backend.community.internal.report.application.AnimalReports.Draft
import com.pawbridge.backend.community.internal.report.application.AnimalReports.Filter
import com.pawbridge.backend.community.internal.report.application.AnimalReports.Kind
import com.pawbridge.backend.community.internal.report.infrastructure.JdbcAnimalReports
import com.pawbridge.backend.user.api.UserNicknameQuery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Optional
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException

/**
 * The animal report repository called directly: list filters and paging, detail, insert, update and soft delete on the
 * real table. The nickname lookup, the photo upload and the photo removal are mocks.
 */
class AnimalReportRepositoryContractTest : ApiContractTestBase() {
    @Autowired
    @Qualifier("writeJdbc")
    private lateinit var write: JdbcTemplate

    @Autowired
    @Qualifier("writeTransaction")
    private lateinit var writeTx: TransactionTemplate

    @Autowired
    @Qualifier("readJdbc")
    private lateinit var read: JdbcTemplate

    @Autowired
    @Qualifier("readOnlyTransaction")
    private lateinit var readTx: TransactionTemplate

    @Autowired
    private lateinit var mapper: ObjectMapper

    private val at: Instant = Instant.parse("2026-03-05T01:02:03.456789Z")
    private val users = mockk<UserNicknameQuery>()
    private val uploads = mockk<PostMediaUploader>()
    private val remover = mockk<PostMediaRemover>(relaxed = true)
    private lateinit var reports: JdbcAnimalReports

    @BeforeEach
    fun build() {
        every { users.findNickname(any()) } answers { Optional.of("닉네임 ${firstArg<Long>()}") }
        every { uploads.upload(any()) } returns listOf("https://fixture.invalid/p1.png")
        reports = JdbcAnimalReports(read, write, readTx, writeTx, users, uploads, remover, mapper, Clock.fixed(at, ZoneOffset.UTC))
    }

    @Test
    fun `올바른 제보와 사진일 때__작성하면__정리된 값으로 한 행이 저장되고 작성자 닉네임과 사진 주소를 돌려준다`() {
        // given
        val photo = PostUpload("a.png", "image/png", 12, PostUpload.Content { PNG.inputStream() })

        // when
        val saved = reports.create(draft(region = "  강남구 역삼동  ", species = " 고양이 ", landmark = "   "), listOf(photo), AUTHOR)
        val stored = row("pawbridge_community.animal_reports", "report_id", saved.reportId!!)

        // then
        assertThat(saved.title).isEqualTo("실종 · 강남구 역삼동 · 고양이")
        assertThat(saved.authorNickname).isEqualTo("닉네임 $AUTHOR")
        assertThat(saved.imageUrls).isEqualTo(listOf("https://fixture.invalid/p1.png"))
        assertThat(saved.createdAt).isEqualTo(LocalDateTime.of(2026, 3, 5, 1, 2, 3, 456_789_000))
        assertThat(stored.get("author_id").asLong()).isEqualTo(AUTHOR)
        assertThat(stored.get("region").asText()).isEqualTo("강남구 역삼동")
        assertThat(stored.get("species").asText()).isEqualTo("고양이")
        assertThat(stored.get("landmark").isNull).`as`("A blank optional value is stored as null").isTrue()
        assertThat(stored.get("image_urls").toString()).isEqualTo("[\"https://fixture.invalid/p1.png\"]")
        assertThat(stored.get("province").asText()).isEqualTo("서울특별시")
        assertThat(stored.get("animal_type").asText()).isEqualTo("CAT")
        assertThat(stored.get("deleted_at").isNull).isTrue()
    }

    @Test
    fun `필수값이 비었거나 미래이거나 분류가 어긋날 때__작성하면__400이고 업로드도 행도 만들지 않는다`() {
        // given
        val bad = listOf(
            draft(region = " "), draft(species = ""), draft(description = " "), draft(occurredOn = LocalDate.parse("2999-01-01")),
            draft(province = "없는시"), draft(province = null, district = "강남구"), draft(region = "가".repeat(121)),
        )

        // when
        // then
        assertNoSideEffects {
            for (draft in bad) {
                assertThatThrownBy { reports.create(draft, null, AUTHOR) }.isStatus(HttpStatus.BAD_REQUEST)
            }
            assertThatThrownBy { reports.create(draft(), null, null) }.isStatus(HttpStatus.UNAUTHORIZED)
        }
        verify(exactly = 0) { uploads.upload(any()) }
    }

    @Test
    fun `저장 행이 거부될 때__작성하면__올린 사진을 보상으로 지우고 예외를 그대로 낸다`() {
        // given
        every { uploads.upload(any()) } returns listOf("https://fixture.invalid/orphan.png")
        execute("ALTER TABLE pawbridge_community.animal_reports ADD CONSTRAINT contract_reject CHECK (region <> '거부 지역') NOT VALID")

        // when
        // then
        try {
            assertThatThrownBy { reports.create(draft(region = "거부 지역"), emptyList(), AUTHOR) }.isInstanceOf(RuntimeException::class.java)
            verify { remover.remove(listOf("https://fixture.invalid/orphan.png")) }
        } finally {
            execute("ALTER TABLE pawbridge_community.animal_reports DROP CONSTRAINT contract_reject")
        }
    }

    @Test
    fun `종류와 지역과 날짜와 키워드 조건일 때__목록을 읽으면__조건을 모두 만족하는 활성 제보만 최신순으로 쪼개 준다`() {
        // given
        seedReport(1, kind = "MISSING", createdAt = "2026-03-01 10:00:00", occurredOn = "2026-02-20", district = "강남구", type = "DOG", description = "갈색 푸들 100% 실종")
        seedReport(2, kind = "MISSING", createdAt = "2026-03-02 10:00:00", occurredOn = "2026-02-25", district = "서초구", type = "CAT", description = "흰 고양이")
        seedReport(3, author = OTHER, kind = "SIGHTING", createdAt = "2026-03-03 10:00:00", occurredOn = "2026-03-01", province = "경기도", district = null, type = "DOG", description = "골든 리트리버 목격")
        seedReport(4, author = OTHER, kind = "MISSING", createdAt = "2026-03-03 10:00:00", occurredOn = "2026-03-02", district = "강남구", type = "DOG", description = "같은 시각 두번째")
        seedReport(5, kind = "MISSING", createdAt = "2026-03-04 10:00:00", deletedAt = "2026-03-05 00:00:00", description = "삭제됨")

        fun ids(filter: Filter) = reports.list(filter).content.map { it.reportId!! - REPORT_BASE }
        fun filter(page: Int = 0, size: Int = 12, kind: Kind? = null, keyword: String? = null, province: String? = null, district: String? = null,
                   type: AnimalType? = null, from: String? = null, to: String? = null) =
            Filter(page, size, kind, keyword, province, district, type, from?.let(LocalDate::parse), to?.let(LocalDate::parse))

        // when
        // then
        assertThat(ids(filter())).`as`("created_at desc, report_id desc").isEqualTo(listOf(4L, 3L, 2L, 1L))
        assertThat(ids(filter(size = 2))).isEqualTo(listOf(4L, 3L))
        assertThat(ids(filter(size = 2, page = 1))).isEqualTo(listOf(2L, 1L))
        assertThat(reports.list(filter(size = 2, page = 9)).let { it.content.size to it.total }).isEqualTo(0 to 4L)
        assertThat(reports.list(filter(size = 2)).total).isEqualTo(4)
        assertThat(ids(filter(kind = Kind.MISSING, province = "서울특별시", district = "강남구"))).isEqualTo(listOf(4L, 1L))
        assertThat(ids(filter(type = AnimalType.DOG))).isEqualTo(listOf(4L, 3L, 1L))
        assertThat(ids(filter(from = "2026-02-25", to = "2026-03-01"))).isEqualTo(listOf(3L, 2L))
        assertThat(ids(filter(keyword = " 골든 "))).isEqualTo(listOf(3L))
        assertThat(ids(filter(keyword = "100%"))).`as`("A percent sign is literal text").isEqualTo(listOf(1L))
        assertThat(ids(filter(keyword = "리트리버".uppercase()))).isEqualTo(listOf(3L))
    }

    @Test
    fun `범위를 벗어난 조건일 때__목록을 읽으면__400이다`() {
        // given
        fun filter(page: Int = 0, size: Int = 12, province: String? = null, district: String? = null, from: LocalDate? = null, to: LocalDate? = null) =
            Filter(page, size, null, null, province, district, null, from, to)

        // when
        // then
        for (bad in listOf(
            filter(size = 0), filter(size = 51), filter(page = -1), filter(province = "없는시"), filter(district = "강남구"),
            filter(from = LocalDate.parse("2999-01-01")), filter(from = LocalDate.parse("2026-03-02"), to = LocalDate.parse("2026-03-01")),
        )) {
            assertThatThrownBy { reports.list(bad) }.isStatus(HttpStatus.BAD_REQUEST)
        }
    }

    @Test
    fun `활성 제보와 삭제된 제보일 때__단건을 읽으면__활성 제보만 주고 닉네임을 못 읽으면 대체 이름이다`() {
        // given
        seedReport(1, description = "상세 설명")
        seedReport(2, deletedAt = "2026-03-05 00:00:00")

        // when
        val found = reports.get(REPORT_BASE + 1)
        every { users.findNickname(any()) } throws IllegalStateException("down")
        val fallback = reports.get(REPORT_BASE + 1)

        // then
        assertThat(found.description).isEqualTo("상세 설명")
        assertThat(found.authorNickname).isEqualTo("닉네임 $AUTHOR")
        assertThat(fallback.authorNickname).isEqualTo("사용자$AUTHOR")
        assertThatThrownBy { reports.get(REPORT_BASE + 2) }.isStatus(HttpStatus.NOT_FOUND)
        assertThatThrownBy { reports.get(REPORT_BASE + 99) }.isStatus(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `작성자가 내용을 바꿀 때__수정하면__바뀐 열만 저장하고 분류를 생략하면 기존 분류를 지키며 같은 내용이면 쓰지 않는다`() {
        // given
        seedReport(1, description = "수정 전", province = "서울특별시", district = "강남구", type = "DOG")
        val before = row("pawbridge_community.animal_reports", "report_id", REPORT_BASE + 1)

        // when
        val unchanged = reports.update(REPORT_BASE + 1, draftOf(before), null, AUTHOR)
        val untouched = row("pawbridge_community.animal_reports", "report_id", REPORT_BASE + 1)
        reports.update(REPORT_BASE + 1, draft(description = "수정 후", region = "새 지역", province = null, district = null, animalType = null), null, AUTHOR)
        val after = row("pawbridge_community.animal_reports", "report_id", REPORT_BASE + 1)

        // then
        assertThat(unchanged.description).isEqualTo("수정 전")
        assertThat(untouched).`as`("An identical update writes nothing").isEqualTo(before)
        assertThat(after.get("description").asText()).isEqualTo("수정 후")
        assertThat(after.get("region").asText()).isEqualTo("새 지역")
        assertThat(after.get("province").asText()).isEqualTo("서울특별시")
        assertThat(after.get("district").asText()).isEqualTo("강남구")
        assertThat(after.get("animal_type").asText()).isEqualTo("DOG")
        assertThat(after.get("updated_at").asText()).isEqualTo("2026-03-05T01:02:03.456789")
        for (kept in listOf("report_id", "author_id", "report_kind", "created_at", "deleted_at", "image_urls")) {
            assertThat(after.get(kept)).`as`(kept).isEqualTo(before.get(kept))
        }
    }

    @Test
    fun `작성자가 아니거나 종류를 바꾸거나 사진을 보내거나 삭제되었을 때__수정하면__403과 400과 404이고 행은 그대로다`() {
        // given
        seedReport(1, kind = "MISSING")
        seedReport(2, deletedAt = "2026-03-05 00:00:00")
        val photo = PostUpload("a.png", "image/png", 12, PostUpload.Content { PNG.inputStream() })

        // when
        // then
        assertNoSideEffects {
            assertThatThrownBy { reports.update(REPORT_BASE + 1, draft(), null, OTHER) }.isStatus(HttpStatus.FORBIDDEN)
            assertThatThrownBy { reports.update(REPORT_BASE + 1, draft(kind = Kind.SIGHTING), null, AUTHOR) }.isStatus(HttpStatus.BAD_REQUEST)
            assertThatThrownBy { reports.update(REPORT_BASE + 1, draft(), listOf(photo), AUTHOR) }.isStatus(HttpStatus.BAD_REQUEST)
            assertThatThrownBy { reports.update(REPORT_BASE + 2, draft(), null, AUTHOR) }.isStatus(HttpStatus.NOT_FOUND)
            assertThatThrownBy { reports.update(REPORT_BASE + 99, draft(), null, AUTHOR) }.isStatus(HttpStatus.NOT_FOUND)
        }
    }

    @Test
    fun `작성자가 아닌 사람이 삭제하면__403이고 작성자가 삭제하면__논리 삭제되며 다시 삭제하면 404이다`() {
        // given
        seedReport(1)

        // when
        assertThatThrownBy { reports.delete(REPORT_BASE + 1, OTHER) }.isStatus(HttpStatus.FORBIDDEN)
        val untouched = scalar("SELECT deleted_at FROM pawbridge_community.animal_reports WHERE report_id = ?", REPORT_BASE + 1)
        reports.delete(REPORT_BASE + 1, AUTHOR)
        val stored = row("pawbridge_community.animal_reports", "report_id", REPORT_BASE + 1)

        // then
        assertThat(untouched).isNull()
        assertThat(stored.get("deleted_at").asText()).isEqualTo("2026-03-05T01:02:03.456789")
        assertThatThrownBy { reports.delete(REPORT_BASE + 1, AUTHOR) }.isStatus(HttpStatus.NOT_FOUND)
        assertThat(reports.list(Filter(0, 12, null, null, null, null, null, null, null)).content).isEmpty()
    }

    private fun org.assertj.core.api.AbstractThrowableAssert<*, *>.isStatus(status: HttpStatus) {
        this.isInstanceOfSatisfying(ResponseStatusException::class.java) { assertThat(it.statusCode).isEqualTo(status) }
    }

    private fun draft(
        kind: Kind = Kind.MISSING,
        occurredOn: LocalDate = LocalDate.parse("2026-03-01"),
        region: String = "강남구 역삼동",
        species: String = "고양이",
        description: String = "골목에서 사라졌습니다",
        landmark: String? = null,
        province: String? = "서울특별시",
        district: String? = "강남구",
        animalType: AnimalType? = AnimalType.CAT,
    ) = Draft(kind, occurredOn, null, region, landmark, species, null, null, null, null, null, description, province, district, animalType)

    /** The same content as a stored row, to prove that an identical update is a no-op. */
    private fun draftOf(row: com.fasterxml.jackson.databind.JsonNode) = Draft(
        Kind.valueOf(row.get("report_kind").asText()), LocalDate.parse(row.get("occurred_on").asText()), null, row.get("region").asText(), null,
        row.get("species").asText(), row.get("animal_name").asText(), null, null, row.get("distinguishing_features").asText(), null,
        row.get("description").asText(), row.get("province").asText(), row.get("district").asText(), AnimalType.valueOf(row.get("animal_type").asText()),
    )

    private fun seedReport(
        index: Long,
        author: Long = AUTHOR,
        kind: String = "MISSING",
        createdAt: String = "2026-03-01 10:00:00",
        occurredOn: String = "2026-02-20",
        province: String? = "서울특별시",
        district: String? = "강남구",
        type: String? = "CAT",
        description: String = "시험 설명",
        deletedAt: String? = null,
    ) {
        val id = REPORT_BASE + index
        execute(
            "INSERT INTO pawbridge_community.animal_reports(report_id,author_id,report_kind,description,occurred_on,region," +
                "species,animal_name,distinguishing_features,province,district,animal_type,created_at,updated_at,deleted_at) " +
                "VALUES (?,?,?,?,?::date,?,?,?,?,?,?,?,?::timestamp,?::timestamp,?::timestamp)",
            id, author, kind, description, occurredOn, "지역 $id", "종 $id", "이름 $id", "특징 $id", province, district, type,
            createdAt, createdAt, deletedAt,
        )
    }

    private companion object {
        const val AUTHOR = 9_923_001L
        const val OTHER = 9_923_002L
        const val REPORT_BASE = 9_923_100L

        /** A PNG signature: the media check reads the first bytes only. */
        val PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 0x1a, 10, 0, 0, 0, 0)
    }
}
