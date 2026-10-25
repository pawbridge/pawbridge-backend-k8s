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
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when` as whenever
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
        val uploader: PostMediaUploader = mock(PostMediaUploader::class.java)
        val remover: PostMediaRemover = mock(PostMediaRemover::class.java)
        val users: UserNicknameQuery = mock(UserNicknameQuery::class.java)
        val reports: JdbcAnimalReports

        init {
            whenever(users.findNickname(7L)).thenAnswer {
                events.add("nickname")
                Optional.of("작성자")
            }
            whenever(uploader.upload(any())).thenAnswer {
                events.add("upload")
                listOf("https://fixture.invalid/new.png")
            }
            reports = JdbcAnimalReports(
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
    }

    @Test
    fun given__valid_report__when__creating__then__validate_upload_insert_commit_then_nickname_without_outbox() {
        val f = Fixture()
        val result = f.reports.create(draft(Kind.MISSING, " 서울특별시 ", " 강남구 ", AnimalType.DOG), listOf(photo()), 7L)

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
        verifyNoInteractions(f.remover)
    }

    @ParameterizedTest
    @ValueSource(strings = ["create", "update"])
    fun given__failing_nickname_read__when__writing__then__commit_report_first_and_fall_back_outside_the_writer_transaction(
        operation: String,
    ) {
        // On the shared pool a failing read inside the writer transaction would mark it rollback-only.
        val f = Fixture()
        val insideWriter = ArrayList<Boolean>()
        doAnswer {
            insideWriter.add(TransactionSynchronizationManager.isActualTransactionActive())
            f.events.add("nickname")
            throw IllegalStateException("private-user")
        }.`when`(f.users).findNickname(7L)

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

        assertThat(result.authorNickname).isEqualTo("사용자7")
        assertThat(insideWriter).containsExactly(false)
        assertThat(f.events).endsWith("write-commit", "nickname").doesNotContain("write-rollback")
        verifyNoInteractions(f.remover)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun given__insert_or_commit_failure__when__creating__then__compensate_only_body_failure(commit: Boolean) {
        val f = Fixture()
        f.write.failInsert = !commit
        f.writeManager.failCommit = commit

        assertThatThrownBy { f.reports.create(draft(Kind.MISSING, null, null, null), listOf(photo()), 7L) }
            .isInstanceOf(RuntimeException::class.java)
        if (commit) {
            verifyNoInteractions(f.remover)
            assertThat(f.events).endsWith("write-commit")
        } else {
            verify(f.remover).remove(listOf("https://fixture.invalid/new.png"))
            assertThat(f.events).endsWith("write-rollback")
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["null", "kind", "future", "region", "province", "district", "long", "empty-photo", "fake-photo", "six-photos"],
    )
    fun given__invalid_input__when__creating__then__reject_before_upload_or_sql(defect: String) {
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

        assertThatThrownBy { f.reports.create(input, uploads, 7L) }
            .isInstanceOfSatisfying(ResponseStatusException::class.java) { e ->
                assertThat(e.statusCode.value()).isEqualTo(400)
            }
        assertThat(f.write.sql).isEmpty()
        verifyNoInteractions(f.uploader, f.remover, f.users)
    }

    @Test
    fun given__invalid_body_and_missing_author__when__creating__then__body_error_precedes_login_and_valid_body_requires_login() {
        val f = Fixture()

        assertThatThrownBy { f.reports.create(null, null, null) }
            .isInstanceOfSatisfying(ResponseStatusException::class.java) { e ->
                assertThat(e.statusCode.value()).isEqualTo(400)
            }
        assertThatThrownBy { f.reports.create(draft(Kind.MISSING, null, null, null), null, 0L) }
            .isInstanceOfSatisfying(ResponseStatusException::class.java) { e ->
                assertThat(e.statusCode.value()).isEqualTo(401)
            }
        verifyNoInteractions(f.uploader, f.users, f.remover)
        assertThat(f.write.sql).isEmpty()
    }

    @Test
    fun given__old_client_omitting_classification__when__updating__then__preserve_classification_images_and_audit_noop() {
        val f = Fixture()

        val result = f.reports.update(42, draft(Kind.MISSING, null, null, null), null, 7L)

        assertThat(result.province).isEqualTo("서울특별시")
        assertThat(result.district).isEqualTo("강남구")
        assertThat(result.animalType).isEqualTo(AnimalType.DOG)
        assertThat(result.imageUrls).isEqualTo(row().imageUrls)
        assertThat(result.updatedAt).isEqualTo(row().updatedAt)
        assertThat(f.write.sql).hasSize(1)
        verifyNoInteractions(f.uploader, f.remover)
    }

    @Test
    fun given__changed_fields__when__updating__then__bind_only_report_columns_keep_images_and_return_preflush_audit() {
        val f = Fixture()
        val input = Draft(
            Kind.MISSING, row().occurredOn, null, "부산", null, "고양이", null, null, null, null, null,
            "새 내용", "부산광역시", null, AnimalType.CAT,
        )

        val result = f.reports.update(42, input, null, 7L)

        assertThat(f.write.sql).hasSize(2)
        assertThat(f.write.sql.last()).contains("UPDATE pawbridge_community.animal_reports").doesNotContain("image_urls=")
        assertThat(result.region).isEqualTo("부산")
        assertThat(result.district).isNull()
        assertThat(result.imageUrls).isEqualTo(row().imageUrls)
        assertThat(result.updatedAt).isEqualTo(row().updatedAt)
        assertThat(f.events).endsWith("write-commit", "nickname")
    }

    @ParameterizedTest
    @ValueSource(strings = ["photos", "missing", "foreign", "kind"])
    fun given__update_rejection__when__updating__then__retain_original_failure_precedence_without_writes(defect: String) {
        val f = Fixture()
        if (defect == "missing") f.write.rows = listOf()
        val kind = if (defect == "kind") Kind.SIGHTING else Kind.MISSING
        val author = if (defect == "foreign") 8L else 7L

        assertThatThrownBy {
            f.reports.update(
                42,
                draft(kind, null, null, null),
                if (defect == "photos") listOf(photo()) else null,
                author,
            )
        }.isInstanceOfSatisfying(ResponseStatusException::class.java) { e ->
            assertThat(e.statusCode.value())
                .isEqualTo(if (defect == "missing") 404 else if (defect == "foreign") 403 else 400)
        }
        assertThat(f.write.sql).noneMatch { sql -> sql.startsWith("UPDATE") }
        verifyNoInteractions(f.uploader, f.remover, f.users)
    }

    @Test
    fun given__visible_owner__when__deleting__then__soft_delete_only_and_repeated_or_foreign_reject() {
        val f = Fixture()

        f.reports.delete(42, 7L)
        assertThat(f.write.sql).hasSize(2)
        assertThat(f.write.sql.last()).contains("SET deleted_at=?,updated_at=?").doesNotContain("DELETE FROM")
        verifyNoInteractions(f.remover, f.uploader, f.users)

        f.write.rows = listOf()
        assertThatThrownBy { f.reports.delete(42, 7L) }
            .isInstanceOfSatisfying(ResponseStatusException::class.java) { e ->
                assertThat(e.statusCode.value()).isEqualTo(404)
            }
        f.write.rows = listOf(row())
        assertThatThrownBy { f.reports.delete(42, 8L) }
            .isInstanceOfSatisfying(ResponseStatusException::class.java) { e ->
                assertThat(e.statusCode.value()).isEqualTo(403)
            }
    }

    @Test
    fun given__filtered_partial_page_and_unavailable_nickname__when__listing__then__literal_search_stable_order_and_independent_fallback() {
        val f = Fixture()
        doAnswer {
            f.events.add("nickname")
            throw IllegalStateException("private-user")
        }.`when`(f.users).findNickname(7L)

        val result = f.reports.list(
            Filter(
                1, 12, Kind.MISSING, "  DOG%_  ", "서울특별시", "강남구", AnimalType.DOG,
                LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 20),
            ),
        )

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
    }

    @Test
    fun given__empty_later_page__when__listing__then__count_with_identical_filters_and_missing_detail_is_404() {
        val f = Fixture()
        f.read.rows = listOf()

        assertThat(f.reports.list(Filter(3, 12, null, null, null, null, null, null, null)).total).isEqualTo(25)
        assertThat(f.read.sql).hasSize(2)
        assertThat(f.read.sql.last()).startsWith("SELECT count(*)").contains("deleted_at IS NULL")
        assertThatThrownBy { f.reports.get(42) }
            .isInstanceOfSatisfying(ResponseStatusException::class.java) { e ->
                assertThat(e.statusCode.value()).isEqualTo(404)
            }
    }

    @Test
    fun given__jpa_offset_boundary__when__listing__then__allow_int_max_but_reject_overflow_inside_transaction_before_sql_with_500() {
        val exact = Fixture()
        exact.reports.list(Filter(Integer.MAX_VALUE, 1, null, null, null, null, null, null, null))
        assertThat(exact.read.arguments.first()).containsExactly(1, Integer.MAX_VALUE.toLong())
        val overflow = Fixture()
        val failure = catchThrowable {
            overflow.reports.list(Filter(1_073_741_824, 2, null, null, null, null, null, null, null))
        }
        assertThat(failure).isInstanceOf(InvalidDataAccessApiUsageException::class.java)
            .hasMessage("Page offset exceeds Integer.MAX_VALUE (2147483647)")
        assertThat(overflow.read.sql).isEmpty()
        assertThat(overflow.events).containsExactly("read-begin", "read-rollback")
        verifyNoInteractions(overflow.users, overflow.uploader, overflow.remover)
        val response = AnimalReportExceptionHandler().failure(failure as RuntimeException)
        assertThat(response.statusCode.value()).isEqualTo(500)
        assertThat(response.body!!.message).isEqualTo("서버 에러!")
    }

    @ParameterizedTest
    @ValueSource(strings = ["page", "size", "keyword", "province", "dates", "future"])
    fun given__invalid_search__when__listing__then__no_read_or_user_call(defect: String) {
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

        assertThatThrownBy { f.reports.list(filter) }.isInstanceOf(ResponseStatusException::class.java)
        assertThat(f.read.sql).isEmpty()
        verifyNoInteractions(f.users)
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
