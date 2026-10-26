package com.pawbridge.backend.community.internal.report.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.community.internal.post.application.PostMediaRemover
import com.pawbridge.backend.community.internal.post.application.PostMediaUploader
import com.pawbridge.backend.community.internal.post.application.PostUpload
import com.pawbridge.backend.community.internal.report.application.AnimalReports.AnimalType
import com.pawbridge.backend.community.internal.report.application.AnimalReports.Details
import com.pawbridge.backend.community.internal.report.application.AnimalReports.Draft
import com.pawbridge.backend.community.internal.report.application.AnimalReports.Filter
import com.pawbridge.backend.community.internal.report.application.AnimalReports.Kind
import com.pawbridge.backend.community.internal.report.web.AnimalReportExceptionHandler
import com.pawbridge.backend.user.api.UserNicknameQuery
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Collections
import java.util.Optional

class JdbcAnimalReportsTest {

    private class Manager(val events: MutableList<String>, val name: String) : AbstractPlatformTransactionManager() {
        var failCommit = false

        override fun doGetTransaction(): Any = Any()

        override fun doBegin(transaction: Any, definition: TransactionDefinition) {
            events.add("$name-begin")
        }

        override fun doCommit(status: DefaultTransactionStatus) {
            events.add("$name-commit")
            if (failCommit) {
                throw TransactionSystemException("private-commit")
            }
        }

        override fun doRollback(status: DefaultTransactionStatus) {
            events.add("$name-rollback")
        }
    }

    private class Jdbc(val events: MutableList<String>) : JdbcTemplate() {
        var rows: List<Details> = listOf(row())
        val sql = ArrayList<String>()
        val arguments = ArrayList<Array<out Any?>>()
        var failInsert = false
        var count = 25L

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> query(q: String, mapper: RowMapper<T>, vararg args: Any?): List<T> {
            sql.add(q)
            arguments.add(args)
            events.add("select")
            return rows as List<T>
        }

        override fun <T : Any?> queryForObject(q: String, type: Class<T>, vararg args: Any?): T? {
            sql.add(q)
            arguments.add(args)
            if (q.startsWith("INSERT")) {
                events.add("insert")
                if (failInsert) {
                    throw DataAccessResourceFailureException("private-db")
                }
                return type.cast(43L)
            }
            events.add("count")
            return type.cast(count)
        }

        override fun update(q: String, vararg args: Any?): Int {
            sql.add(q)
            arguments.add(args)
            events.add("update")
            return 1
        }
    }

    private class Fixture {
        val events = ArrayList<String>()
        val read = Jdbc(events)
        val write = Jdbc(events)
        val readManager = Manager(events, "read")
        val writeManager = Manager(events, "write")
        val uploader = mockk<PostMediaUploader>()
        val remover = mockk<PostMediaRemover>()
        val users = mockk<UserNicknameQuery>()
        val reports = JdbcAnimalReports(
            read,
            write,
            TransactionTemplate(readManager),
            TransactionTemplate(writeManager),
            users,
            uploader,
            remover,
            ObjectMapper(),
            Clock.fixed(TIME, ZoneOffset.UTC),
        )
    }

    @Test
    fun `올바른 신고일 때__작성하면__검증 업로드 INSERT 커밋 뒤에 닉네임을 읽고 outbox는 쓰지 않는다`() {
        // given
        val f = Fixture()

        every { f.uploader.upload(any()) } answers {
            f.events.add("upload")
            listOf("https://fixture.invalid/new.png")
        }
        every { f.users.findNickname(any()) } answers {
            f.events.add("nickname")
            Optional.of("작성자")
        }

        // when
        val result = f.reports.create(draft(Kind.MISSING, " 서울특별시 ", " 강남구 ", AnimalType.DOG), listOf(photo()), 7L)

        // then
        assertThat(result.reportId).isEqualTo(43)
        assertThat(result.title).isEqualTo("실종 · 서울 · 개")
        assertThat(result.authorNickname).isEqualTo("작성자")
        assertThat(result.approximateTime).isNull()
        assertThat(result.province).isEqualTo("서울특별시")
        assertThat(result.district).isEqualTo("강남구")
        assertThat(f.events).containsExactly("write-begin", "upload", "insert", "write-commit", "nickname")
        assertThat(f.write.sql).hasSize(1)
        assertThat(f.write.sql.first()).contains("INSERT INTO pawbridge_community.animal_reports", "RETURNING report_id")
        assertThat(f.write.sql.first().count { it == '?' }.toLong())
            .isEqualTo(f.write.arguments.first().size.toLong())
        verify(exactly = 1) { f.users.findNickname(7L) }
        confirmVerified(f.remover)
    }

    @ParameterizedTest
    @ValueSource(strings = ["create", "update"])
    fun `닉네임 조회가 실패할 때__작성하거나 수정하면__신고를 먼저 커밋하고 작성자 트랜잭션 밖에서 대체 닉네임을 쓴다`(
        operation: String,
    ) {
        // On the shared pool a failing read inside the writer transaction would mark it rollback-only.

        // given
        val f = Fixture()
        val insideWriter = ArrayList<Boolean>()

        every { f.uploader.upload(any()) } answers {
            f.events.add("upload")
            listOf("https://fixture.invalid/new.png")
        }
        every { f.users.findNickname(any()) } answers {
            insideWriter.add(TransactionSynchronizationManager.isActualTransactionActive())
            f.events.add("nickname")
            throw IllegalStateException("private-user")
        }

        // when
        val result = if (operation == "create") {
            f.reports.create(draft(Kind.MISSING, null, null, null), listOf(photo()), 7L)
        } else {
            f.reports.update(
                42,
                Draft(
                    Kind.MISSING, row().occurredOn, null, "부산", null, "고양이", null, null, null, null, null,
                    "새 내용", null, null, null,
                ),
                null,
                7L,
            )
        }

        // then
        assertThat(result.authorNickname).isEqualTo("사용자7")
        assertThat(insideWriter).containsExactly(false)
        assertThat(f.events).endsWith("write-commit", "nickname").doesNotContain("write-rollback")
        verify(exactly = 1) { f.users.findNickname(7L) }
        confirmVerified(f.remover)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `INSERT나 커밋이 실패할 때__작성하면__본문 실패일 때만 업로드한 사진을 보상 삭제한다`(commit: Boolean) {
        // given
        val f = Fixture()
        f.write.failInsert = !commit
        f.writeManager.failCommit = commit

        every { f.uploader.upload(any()) } answers {
            f.events.add("upload")
            listOf("https://fixture.invalid/new.png")
        }
        justRun { f.remover.remove(any()) }

        // when
        val thrown = catchThrowable { f.reports.create(draft(Kind.MISSING, null, null, null), listOf(photo()), 7L) }

        // then
        assertThat(thrown).isInstanceOf(RuntimeException::class.java)
        if (commit) {
            confirmVerified(f.remover)
            assertThat(f.events).endsWith("write-commit")
        } else {
            verify(exactly = 1) { f.remover.remove(listOf("https://fixture.invalid/new.png")) }
            assertThat(f.events).endsWith("write-rollback")
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["null", "kind", "future", "region", "province", "district", "long", "empty-photo", "fake-photo", "six-photos"],
    )
    fun `잘못된 입력일 때__작성하면__업로드나 SQL 전에 거부한다`(defect: String) {
        // given
        val f = Fixture()
        val valid = draft(Kind.MISSING, null, null, null)
        var request: Draft? = valid
        var files: List<PostUpload>? = null
        if (defect == "null") request = null
        if (defect == "kind") request = draft(null, null, null, null)
        if (defect == "province") request = draft(Kind.MISSING, "not-a-province", null, null)
        if (defect == "district") request = draft(Kind.MISSING, null, "강남구", null)
        if (defect == "future" || defect == "region" || defect == "long") {
            request = Draft(
                valid.kind,
                if (defect == "future") LocalDate.of(2026, 10, 21) else valid.occurredOn,
                if (defect == "long") "x".repeat(41) else null,
                if (defect == "region") " " else valid.region,
                null,
                valid.species,
                null, null, null, null, null,
                valid.description,
                null, null, null,
            )
        }
        if (defect == "empty-photo") {
            files = listOf(PostUpload("x.png", "image/png", 0, PostUpload.Content { ByteArrayInputStream(ByteArray(0)) }))
        }
        if (defect == "fake-photo") {
            files = listOf(PostUpload("x.png", "image/png", 12, PostUpload.Content { ByteArrayInputStream(ByteArray(12)) }))
        }
        if (defect == "six-photos") files = Collections.nCopies(6, photo())
        val input = request
        val uploads = files

        // when
        val thrown = catchThrowable { f.reports.create(input, uploads, 7L) }

        // then
        assertThat(thrown).isInstanceOfSatisfying(ResponseStatusException::class.java) { e ->
            assertThat(e.statusCode.value()).isEqualTo(400)
        }
        assertThat(f.write.sql).isEmpty()
        confirmVerified(f.uploader, f.remover, f.users)
    }

    @Test
    fun `본문이 잘못되고 작성자도 없을 때__작성하면__본문 오류가 로그인 확인보다 먼저고 올바른 본문은 로그인을 요구한다`() {
        // given
        val f = Fixture()

        // when
        val invalidBody = catchThrowable { f.reports.create(null, null, null) }
        val missingAuthor = catchThrowable { f.reports.create(draft(Kind.MISSING, null, null, null), null, 0L) }

        // then
        assertThat(invalidBody).isInstanceOfSatisfying(ResponseStatusException::class.java) { e ->
            assertThat(e.statusCode.value()).isEqualTo(400)
        }
        assertThat(missingAuthor).isInstanceOfSatisfying(ResponseStatusException::class.java) { e ->
            assertThat(e.statusCode.value()).isEqualTo(401)
        }
        confirmVerified(f.uploader, f.users, f.remover)
        assertThat(f.write.sql).isEmpty()
    }

    @Test
    fun `구버전 클라이언트가 분류를 생략할 때__수정하면__분류와 이미지를 보존하고 감사 시각은 그대로 둔다`() {
        // given
        val f = Fixture()

        every { f.users.findNickname(any()) } returns Optional.of("작성자")

        // when
        val result = f.reports.update(42, draft(Kind.MISSING, null, null, null), null, 7L)

        // then
        assertThat(result.province).isEqualTo("서울특별시")
        assertThat(result.district).isEqualTo("강남구")
        assertThat(result.animalType).isEqualTo(AnimalType.DOG)
        assertThat(result.imageUrls).isEqualTo(row().imageUrls)
        assertThat(result.updatedAt).isEqualTo(row().updatedAt)
        assertThat(f.write.sql).hasSize(1)
        verify(exactly = 1) { f.users.findNickname(7L) }
        confirmVerified(f.uploader, f.remover)
    }

    @Test
    fun `필드가 바뀌었을 때__수정하면__신고 컬럼만 바인딩하고 이미지는 유지하며 flush 전 감사 시각을 돌려준다`() {
        // given
        val f = Fixture()
        val input = Draft(
            Kind.MISSING, row().occurredOn, null, "부산", null, "고양이", null, null, null, null, null,
            "새 내용", "부산광역시", null, AnimalType.CAT,
        )

        every { f.users.findNickname(any()) } answers {
            f.events.add("nickname")
            Optional.of("작성자")
        }

        // when
        val result = f.reports.update(42, input, null, 7L)

        // then
        assertThat(f.write.sql).hasSize(2)
        assertThat(f.write.sql.last()).contains("UPDATE pawbridge_community.animal_reports").doesNotContain("image_urls=")
        assertThat(result.region).isEqualTo("부산")
        assertThat(result.district).isNull()
        assertThat(result.imageUrls).isEqualTo(row().imageUrls)
        assertThat(result.updatedAt).isEqualTo(row().updatedAt)
        assertThat(f.events).endsWith("write-commit", "nickname")
        verify(exactly = 1) { f.users.findNickname(7L) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["photos", "missing", "foreign", "kind"])
    fun `수정이 거부될 때__수정하면__기존 실패 우선순위를 지키고 쓰기는 하지 않는다`(defect: String) {
        // given
        val f = Fixture()
        if (defect == "missing") f.write.rows = listOf()
        val kind = if (defect == "kind") Kind.SIGHTING else Kind.MISSING
        val author = if (defect == "foreign") 8L else 7L

        // when
        val thrown = catchThrowable {
            f.reports.update(
                42,
                draft(kind, null, null, null),
                if (defect == "photos") listOf(photo()) else null,
                author,
            )
        }

        // then
        assertThat(thrown).isInstanceOfSatisfying(ResponseStatusException::class.java) { e ->
            assertThat(e.statusCode.value())
                .isEqualTo(if (defect == "missing") 404 else if (defect == "foreign") 403 else 400)
        }
        assertThat(f.write.sql).noneMatch { sql -> sql.startsWith("UPDATE") }
        confirmVerified(f.uploader, f.remover, f.users)
    }

    @Test
    fun `보이는 소유 신고일 때__삭제하면__소프트 삭제만 하고 반복하거나 남의 신고는 거부한다`() {
        // given
        val f = Fixture()

        // when
        f.reports.delete(42, 7L)
        val deleteSql = ArrayList(f.write.sql)
        f.write.rows = listOf()
        val repeated = catchThrowable { f.reports.delete(42, 7L) }
        f.write.rows = listOf(row())
        val foreign = catchThrowable { f.reports.delete(42, 8L) }

        // then
        assertThat(deleteSql).hasSize(2)
        assertThat(deleteSql.last()).contains("SET deleted_at=?,updated_at=?").doesNotContain("DELETE FROM")
        assertThat(repeated).isInstanceOfSatisfying(ResponseStatusException::class.java) { e ->
            assertThat(e.statusCode.value()).isEqualTo(404)
        }
        assertThat(foreign).isInstanceOfSatisfying(ResponseStatusException::class.java) { e ->
            assertThat(e.statusCode.value()).isEqualTo(403)
        }
        confirmVerified(f.remover, f.uploader, f.users)
    }

    @Test
    fun `필터와 부분 페이지가 있고 닉네임을 조회할 수 없을 때__목록을 조회하면__문자 그대로 검색하고 안정 정렬하며 대체 닉네임을 독립 적용한다`() {
        // given
        val f = Fixture()
        val filter = Filter(
            1, 12, Kind.MISSING, "  DOG%_  ", "서울특별시", "강남구", AnimalType.DOG,
            LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 20),
        )

        every { f.users.findNickname(any()) } answers {
            f.events.add("nickname")
            throw IllegalStateException("private-user")
        }

        // when
        val result = f.reports.list(filter)

        // then
        assertThat(result.total).isEqualTo(13)
        assertThat(result.content.first().authorNickname).isEqualTo("사용자7")
        assertThat(f.read.sql).hasSize(1)
        assertThat(f.read.sql.first())
            .contains(
                "deleted_at IS NULL",
                "occurred_on >= ?",
                "occurred_on <= ?",
                "position(? in lower(description))",
                "ORDER BY created_at DESC, report_id DESC LIMIT ? OFFSET ?",
            )
            .doesNotContain("DOG%")
        assertThat(f.read.arguments.first()).contains("dog%_", 12, 12L)
        assertThat(f.events).containsExactly("read-begin", "select", "read-commit", "nickname")
        verify(exactly = 1) { f.users.findNickname(7L) }
    }

    @Test
    fun `뒤쪽 페이지가 비어 있을 때__목록을 조회하면__같은 필터로 개수를 세고 없는 상세는 404이다`() {
        // given
        val f = Fixture()
        f.read.rows = listOf()

        // when
        val total = f.reports.list(Filter(3, 12, null, null, null, null, null, null, null)).total
        val listSql = ArrayList(f.read.sql)
        val missing = catchThrowable { f.reports.get(42) }

        // then
        assertThat(total).isEqualTo(25)
        assertThat(listSql).hasSize(2)
        assertThat(listSql.last()).startsWith("SELECT count(*)").contains("deleted_at IS NULL")
        assertThat(missing).isInstanceOfSatisfying(ResponseStatusException::class.java) { e ->
            assertThat(e.statusCode.value()).isEqualTo(404)
        }
    }

    @Test
    fun `JPA 오프셋 경계일 때__목록을 조회하면__int 최댓값은 허용하고 넘치면 트랜잭션 안에서 SQL 전에 500으로 거부한다`() {
        // given
        val exact = Fixture()
        val overflow = Fixture()

        // when
        exact.reports.list(Filter(Integer.MAX_VALUE, 1, null, null, null, null, null, null, null))
        val failure = catchThrowable {
            overflow.reports.list(Filter(1_073_741_824, 2, null, null, null, null, null, null, null))
        }
        val response = AnimalReportExceptionHandler().failure(failure as RuntimeException)

        // then
        assertThat(exact.read.arguments.first()).containsExactly(1, Integer.MAX_VALUE.toLong())
        assertThat(failure).isInstanceOf(InvalidDataAccessApiUsageException::class.java)
            .hasMessage("Page offset exceeds Integer.MAX_VALUE (2147483647)")
        assertThat(overflow.read.sql).isEmpty()
        assertThat(overflow.events).containsExactly("read-begin", "read-rollback")
        confirmVerified(overflow.users, overflow.uploader, overflow.remover)
        assertThat(response.statusCode.value()).isEqualTo(500)
        assertThat(response.body!!.message).isEqualTo("서버 에러!")
    }

    @ParameterizedTest
    @ValueSource(strings = ["page", "size", "keyword", "province", "dates", "future"])
    fun `잘못된 검색 조건일 때__목록을 조회하면__읽기나 사용자 호출을 하지 않는다`(defect: String) {
        // given
        val f = Fixture()
        val filter = Filter(
            if (defect == "page") -1 else 0,
            if (defect == "size") 51 else 12,
            null,
            if (defect == "keyword") "x".repeat(101) else null,
            if (defect == "province") "wrong" else null,
            null,
            null,
            if (defect == "dates") LocalDate.of(2026, 10, 20) else null,
            if (defect == "dates") {
                LocalDate.of(2026, 10, 19)
            } else if (defect == "future") {
                LocalDate.of(2026, 10, 21)
            } else {
                null
            },
        )

        // when
        val thrown = catchThrowable { f.reports.list(filter) }

        // then
        assertThat(thrown).isInstanceOf(ResponseStatusException::class.java)
        assertThat(f.read.sql).isEmpty()
        confirmVerified(f.users)
    }

    private companion object {
        val TIME: Instant = Instant.parse("2026-10-20T03:00:00Z")

        fun draft(kind: Kind?, province: String?, district: String?, type: AnimalType?): Draft =
            Draft(
                kind,
                LocalDate.of(2026, 10, 20),
                " ",
                " 서울 ",
                null,
                " 개 ",
                null, null, null, null, null,
                " 내용 ",
                province,
                district,
                type,
            )

        fun row(): Details =
            Details(
                42L,
                7L,
                null,
                "실종 · 서울 · 개",
                "내용",
                listOf("https://fixture.invalid/reports/images/old.png"),
                Kind.MISSING,
                LocalDate.of(2026, 10, 20),
                null,
                "서울",
                null,
                "개",
                null, null, null, null, null,
                LocalDateTime.of(2026, 10, 19, 12, 0),
                LocalDateTime.of(2026, 10, 19, 12, 0),
                "서울특별시",
                "강남구",
                AnimalType.DOG,
            )

        fun photo(): PostUpload =
            PostUpload(
                "photo.png",
                "image/png",
                8,
                PostUpload.Content {
                    ByteArrayInputStream(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
                },
            )
    }
}
