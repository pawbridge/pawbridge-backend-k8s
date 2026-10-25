package com.pawbridge.backend.animal.internal.memberread.infrastructure

import com.pawbridge.backend.animal.api.AnimalMemberReadUnavailableException
import com.pawbridge.backend.animal.api.AnimalMemberReads.Animal
import com.pawbridge.backend.animal.api.AnimalMemberReads.Page
import com.pawbridge.backend.animal.api.AnimalMemberReads.PageRequest
import com.pawbridge.backend.animal.api.AnimalShelterReads
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionOperations
import java.sql.ResultSet
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import java.util.stream.Stream
import javax.sql.DataSource

class JdbcAnimalMemberReadsTest {
    private val jdbc = mockk<NamedParameterJdbcOperations>()
    private val shelters = mockk<AnimalShelterReads>()
    private val reads = JdbcAnimalMemberReads(jdbc, TransactionOperations.withoutTransaction(), shelters)

    private fun row(id: Long): Animal {
        return Animal(id, null, "DOG", null, null, null, null, null, "ADOPTED", null, null, null, null, null, null)
    }

    @Test
    fun `원본이 지원하는 컬렉션 정렬일 때__등록 페이지를 조회하면__SQL 제한 뒤에 루트를 중복 제거하고 그 다음에 개수를 추정한다`() {
        // given
        every { jdbc.query(any<String>(), any<MapSqlParameterSource>(), any<RowMapper<Animal>>()) } returns listOf(row(8), row(8))

        // when
        val result = reads.byShelter(7L, PageRequest(0, 2, "shelter.animals.id,asc"))

        // then
        assertThat(result).isEqualTo(Page(listOf(row(8)), 0, 2, 1, 1, true))
        verify(exactly = 1) {
            jdbc.query(
                match<String> { sql ->
                    sql.contains("LEFT JOIN pawbridge_animal.animals sort_0 ON sort_0.shelter_id=s.id") &&
                        sql.endsWith(" ORDER BY sort_0.id ASC LIMIT :limit OFFSET :offset") &&
                        !sql.contains("DISTINCT") && !sql.contains("GROUP BY") &&
                        !sql.contains("sort_0.api_source") && !sql.contains("sort_0.status")
                },
                any<MapSqlParameterSource>(),
                any<RowMapper<Animal>>(),
            )
        }
        verify(exactly = 0) { jdbc.queryForObject(any<String>(), any<MapSqlParameterSource>(), Long::class.javaObjectType) }
    }

    @ParameterizedTest
    @MethodSource("collectionSortPaths")
    fun `원본 연관 경로 정렬일 때__정렬하면__문법·종단 엔티티·중첩 조인을 보존한다`(sort: String, order: String) {
        // given
        every { jdbc.query(any<String>(), any<MapSqlParameterSource>(), any<RowMapper<Animal>>()) } returns listOf(row(8))

        // when
        val page = reads.byShelter(7L, PageRequest(0, 2, sort))

        // then
        assertThat(page!!.content).containsExactly(row(8))
        verify(exactly = 1) {
            jdbc.query(
                match<String> { sql ->
                    sql.contains("LEFT JOIN pawbridge_animal.animals sort_0 ON sort_0.shelter_id=s.id") &&
                        sql.endsWith(" ORDER BY " + order + " LIMIT :limit OFFSET :offset") &&
                        !sql.contains("DISTINCT") && !sql.contains("NULLS") &&
                        !sql.contains("sort_0.api_source=") && !sql.contains("sort_0.status=")
                },
                any<MapSqlParameterSource>(),
                any<RowMapper<Animal>>(),
            )
        }
    }

    @Test
    fun `공유하는 컬렉션 접두어로 여러 속성을 정렬할 때__보호소별로 조회하면__조인을 재사용하고 원래 순서를 지키며 추가 필터를 붙이지 않는다`() {
        // given
        every { jdbc.query(any<String>(), any<MapSqlParameterSource>(), any<RowMapper<Animal>>()) } returns listOf(row(9), row(9), row(8))

        // when
        val page = reads.byShelter(7L, PageRequest(2, 3, "shelter.animals.breed,shelter.animals.shelter.name,asc"))

        // then
        assertThat(page).isEqualTo(Page(listOf(row(9), row(8)), 2, 3, 8, 3, true))
        verify(exactly = 1) {
            jdbc.query(
                match<String> { sql ->
                    sql.contains("LEFT JOIN pawbridge_animal.animals sort_0 ON sort_0.shelter_id=s.id") &&
                        sql.contains("LEFT JOIN pawbridge_animal.shelters sort_1 ON sort_1.id=sort_0.shelter_id") &&
                        sql.endsWith(" ORDER BY sort_0.breed ASC,sort_1.name ASC LIMIT :limit OFFSET :offset") &&
                        !sql.contains("sort_2") && !sql.contains("DISTINCT") &&
                        !sql.contains("sort_0.api_source") && !sql.contains("sort_0.status")
                },
                match<MapSqlParameterSource> { params -> params.getValue("offset") == 6L },
                any<RowMapper<Animal>>(),
            )
        }
        verify(exactly = 0) { jdbc.queryForObject(any<String>(), any<MapSqlParameterSource>(), Long::class.javaObjectType) }
    }

    @Test
    fun `컬렉션 전체 페이지와 비어 있는 뒤쪽 페이지일 때__개수를 세면__정렬 조인 없이 루트 수동 동물만 센다`() {
        // given
        every { jdbc.queryForObject(any<String>(), any<MapSqlParameterSource>(), Long::class.javaObjectType) } returns 99L
        every { jdbc.query(any<String>(), any<MapSqlParameterSource>(), any<RowMapper<Animal>>()) } returnsMany listOf(listOf(row(9), row(8)), listOf())

        // when
        val full = reads.byShelter(7L, PageRequest(1, 2, "shelter.animals.id,asc"))
        val empty = reads.byShelter(7L, PageRequest(50, 2, "shelter.animals.id,asc"))

        // then
        assertThat(full).isEqualTo(Page(listOf(row(9), row(8)), 1, 2, 99, 50, false))
        assertThat(empty).isEqualTo(Page(listOf(), 50, 2, 99, 50, true))
        verify(exactly = 2) {
            jdbc.queryForObject(
                "SELECT count(*) FROM pawbridge_animal.animals a WHERE a.shelter_id=:shelterId AND a.api_source='MANUAL'",
                any<MapSqlParameterSource>(),
                Long::class.javaObjectType,
            )
        }
    }

    @Test
    fun `ID가 널이거나 비었을 때__일괄 조회하면__질의 없이 수정 가능한 빈 목록을 돌려준다`() {
        // given

        // when
        val fromNull = reads.batch(null)
        (fromNull as MutableList<Animal>).add(row(1))
        val fromEmpty = reads.batch(listOf())

        // then
        assertThat(fromEmpty).isEmpty()
        confirmVerified(jdbc, shelters)
    }

    @Test
    fun `중복·없는·널 ID일 때__일괄 조회하면__원본 상태나 정렬 정책 없이 IN 질의 하나를 쓴다`() {
        // given
        val ids = ArrayList<Long?>()
        ids.add(8L)
        ids.add(8L)
        ids.add(404L)
        ids.add(null)

        every { jdbc.query(any<String>(), any<MapSqlParameterSource>(), any<RowMapper<Animal>>()) } returns listOf(row(8))

        // when
        val result = reads.batch(ids)

        // then
        assertThat(result).containsExactly(row(8))
        val sql = slot<String>()
        val params = slot<MapSqlParameterSource>()
        verify(exactly = 1) { jdbc.query(capture(sql), capture(params), any<RowMapper<Animal>>()) }
        assertThat(sql.captured).contains("LEFT JOIN pawbridge_animal.shelters", " WHERE a.id IN (:ids)")
            .doesNotContain("ORDER BY", "a.api_source=", "a.status=", "DISTINCT", "LIMIT")
        assertThat(params.captured.getValue("ids")).isEqualTo(ids)
        confirmVerified(jdbc)
    }

    @Test
    fun `널 행과 채워진 행일 때__매핑하면__15개 필드와 매퍼 나이를 모두 유지한다`() {
        // given
        val rs = mockk<ResultSet>()
        val values = HashMap<String, Any?>()
        val end = LocalDate.of(2026, 12, 31)
        val created = LocalDateTime.of(2026, 10, 20, 2, 3, 4, 123456000)

        every { rs.getString(any<String>()) } answers { values[firstArg()] as String? }
        every { rs.getObject(any<String>(), any<Class<Any>>()) } answers {
            val value = values[firstArg()]
            if (secondArg<Class<*>>().isInstance(value)) value else null
        }

        // when
        val empty = JdbcAnimalMemberReads.animal(rs, 0)
        values["id"] = 9L
        values["apms_notice_no"] = "공고"
        values["species"] = "CAT"
        values["breed"] = "품종"
        values["gender"] = "UNKNOWN"
        values["birth_year"] = 2020
        values["special_mark"] = "특징"
        values["status"] = "ADOPTION_PENDING"
        values["notice_end_date"] = end
        values["image_url"] = "image"
        values["favorite_count"] = 3
        values["shelter_id"] = 7L
        values["shelter_name"] = "센터"
        values["created_at"] = created
        val populated = JdbcAnimalMemberReads.animal(rs, 0)

        // then
        assertThat(empty).isEqualTo(
            Animal(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null),
        )
        assertThat(populated).isEqualTo(
            Animal(
                9L, "공고", "CAT", "품종", "UNKNOWN", 2020,
                LocalDate.now().year - 2020, "특징", "ADOPTION_PENDING", end, "image", 3, 7L, "센터", created,
            ),
        )
    }

    @Test
    fun `부분적인 뒤쪽 페이지일 때__보호소별로 조회하면__정확한 수동 조건을 쓰고 상태 필터 없이 개수를 추정한다`() {
        // given
        every { jdbc.query(any<String>(), any<MapSqlParameterSource>(), any<RowMapper<Animal>>()) } returns listOf(row(8))

        // when
        val result = reads.byShelter(7L, PageRequest(2, 20, "createdAt,desc"))

        // then
        assertThat(result).isEqualTo(Page(listOf(row(8)), 2, 20, 41, 3, true))
        verify(exactly = 1) {
            jdbc.query(
                match<String> { s ->
                    s.contains("a.shelter_id=:shelterId AND a.api_source='MANUAL'") &&
                        s.endsWith(" ORDER BY a.created_at DESC LIMIT :limit OFFSET :offset") && !s.contains("a.status=")
                },
                match<MapSqlParameterSource> { p ->
                    p.getValue("shelterId") == 7L && p.getValue("offset") == 40L && p.getValue("limit") == 20
                },
                any<RowMapper<Animal>>(),
            )
        }
        verify(exactly = 0) { jdbc.queryForObject(any<String>(), any<MapSqlParameterSource>(), Long::class.javaObjectType) }
    }

    @Test
    fun `가득 찬 뒤쪽 페이지와 빈 뒤쪽 페이지일 때__보호소별로 조회하면__같은 수동 개수를 쓰고 PageImpl 메타데이터를 보존한다`() {
        // given
        every { jdbc.query(any<String>(), any<MapSqlParameterSource>(), any<RowMapper<Animal>>()) } returnsMany listOf(listOf(row(8), row(9)), listOf())
        every { jdbc.queryForObject(any<String>(), any<MapSqlParameterSource>(), Long::class.javaObjectType) } returns 99L

        // when
        val full = reads.byShelter(7L, PageRequest(1, 2, "id,asc"))!!
        val empty = reads.byShelter(7L, PageRequest(50, 2, "id,asc"))!!

        // then
        assertThat(full.totalElements).isEqualTo(99)
        assertThat(full.totalPages).isEqualTo(50)
        assertThat(full.last).isFalse()
        assertThat(empty.content).isEmpty()
        assertThat(empty.totalElements).isEqualTo(99)
        assertThat(empty.last).isTrue()
        verify(exactly = 2) {
            jdbc.queryForObject(
                "SELECT count(*) FROM pawbridge_animal.animals a WHERE a.shelter_id=:shelterId AND a.api_source='MANUAL'",
                any<MapSqlParameterSource>(),
                Long::class.javaObjectType,
            )
        }
    }

    @Test
    fun `첫 페이지가 비었고 개수가 오래된 마지막 페이지일 때__보호소별로 조회하면__원본의 추정과 총계 보정을 유지한다`() {
        // given
        every { jdbc.query(any<String>(), any<MapSqlParameterSource>(), any<RowMapper<Animal>>()) } returnsMany listOf(listOf(), listOf(row(8), row(9)))
        every { jdbc.queryForObject(any<String>(), any<MapSqlParameterSource>(), Long::class.javaObjectType) } returns 1L

        // when
        val first = reads.byShelter(7L, PageRequest(0, 2, "id,asc"))

        // then
        assertThat(first).isEqualTo(Page(listOf(), 0, 2, 0, 0, true))
        verify(exactly = 0) { jdbc.queryForObject(any<String>(), any<MapSqlParameterSource>(), Long::class.javaObjectType) }

        // when
        val stale = reads.byShelter(7L, PageRequest(2, 2, "id,asc"))

        // then
        assertThat(stale!!.totalElements).isEqualTo(6)
    }

    @Test
    fun `단일 와이어 정렬일 때__정렬을 만들면__스칼라 25개와 직접 보호소 필드를 동순위·널 정책 없이 유지한다`() {
        // given
        val fields = arrayOf(
            arrayOf("id", "id"), arrayOf("apmsDesertionNo", "apms_desertion_no"), arrayOf("apmsNoticeNo", "apms_notice_no"),
            arrayOf("species", "species"), arrayOf("breed", "breed"), arrayOf("birthYear", "birth_year"),
            arrayOf("weight", "weight"), arrayOf("color", "color"),
            arrayOf("gender", "gender"), arrayOf("neuterStatus", "neuter_status"), arrayOf("specialMark", "special_mark"),
            arrayOf("apmsProcessState", "apms_process_state"),
            arrayOf("noticeStartDate", "notice_start_date"), arrayOf("noticeEndDate", "notice_end_date"),
            arrayOf("apmsUpdatedAt", "apms_updated_at"),
            arrayOf("happenDate", "happen_date"), arrayOf("happenPlace", "happen_place"), arrayOf("imageUrl", "image_url"),
            arrayOf("imageUrl2", "image_url2"),
            arrayOf("status", "status"), arrayOf("apiSource", "api_source"), arrayOf("favoriteCount", "favorite_count"),
            arrayOf("description", "description"),
            arrayOf("createdAt", "created_at"), arrayOf("updatedAt", "updated_at"),
        )

        for (field in fields) {
            for (direction in listOf("asc", "desc")) {
                // when
                val ordering = JdbcAnimalMemberReads.ordering(field[0] + "," + direction)

                // then
                assertThat(ordering).isEqualTo(" ORDER BY a." + field[1] + " " + direction.uppercase(Locale.ROOT))
            }
        }

        // when
        val mixedCase = JdbcAnimalMemberReads.ordering("BirthYear,asc")
        val shelterNameMixedCase = JdbcAnimalMemberReads.ordering("Shelter.Name,asc")
        val shelterNameDesc = JdbcAnimalMemberReads.ordering("shelter.name,desc")
        val shelterNameCamel = JdbcAnimalMemberReads.ordering("shelterName,asc")
        val ignoreCase = JdbcAnimalMemberReads.ordering("breed,id,desc,ignorecase")
        val ignoreCaseOnString = JdbcAnimalMemberReads.ordering("species,asc,ignorecase")
        val defaultOrdering = JdbcAnimalMemberReads.ordering(null)
        val blank = JdbcAnimalMemberReads.ordering(" ")

        // then
        assertThat(mixedCase).isEqualTo(" ORDER BY a.birth_year ASC")
        assertThat(shelterNameMixedCase).isEqualTo(" ORDER BY s.name ASC")
        assertThat(shelterNameDesc).isEqualTo(" ORDER BY s.name DESC")
        assertThat(shelterNameCamel).isEqualTo(" ORDER BY s.name ASC")
        assertThat(ignoreCase).isEqualTo(" ORDER BY lower(a.breed) DESC,a.id DESC")
        assertThat(ignoreCaseOnString).isEqualTo(" ORDER BY a.species ASC")
        assertThat(defaultOrdering).isEqualTo(" ORDER BY a.created_at DESC")
        assertThat(blank).isEmpty()
    }

    @Test
    fun `평탄화된 여러 정렬이나 주입된 경로일 때__보호소별로 조회하면__SQL 전에 실패하고 쌍 파싱을 만들어내지 않는다`() {
        // given
        val sorts = listOf("breed,desc,createdAt,asc", "id;drop table animals,asc")

        for (sort in sorts) {
            // when
            val thrown = assertThatThrownBy { reads.byShelter(7L, PageRequest(0, 20, sort)) }

            // then
            thrown.isInstanceOf(AnimalMemberReadUnavailableException::class.java)
        }
        confirmVerified(jdbc)
    }

    @Test
    fun `JPA 최대 오프셋일 때__마지막 여부를 계산하면__원본의 int 오버플로를 보존한다`() {
        // given
        every { jdbc.query(any<String>(), any<MapSqlParameterSource>(), any<RowMapper<Animal>>()) } returns listOf()
        every { jdbc.queryForObject(any<String>(), any<MapSqlParameterSource>(), Long::class.javaObjectType) } returns 99L

        // when
        val result = reads.byShelter(7L, PageRequest(Int.MAX_VALUE, 1, "id,asc"))!!

        // then
        assertThat(result.number).isEqualTo(Int.MAX_VALUE)
        assertThat(result.totalElements).isEqualTo(99)
        // PageImpl.hasNext uses int page+1, including the existing overflow at MAX_VALUE.
        assertThat(result.last).isFalse()
    }

    @Test
    fun `질의가 실패할 때__일괄 조회나 개수를 세면__빈 결과 대신 이용 불가를 전파한다`() {
        // given
        val cause = IllegalStateException("selected read failed")

        every { jdbc.query(any<String>(), any<MapSqlParameterSource>(), any<RowMapper<Animal>>()) } throws cause andThen listOf()
        every { jdbc.queryForObject(any<String>(), any<MapSqlParameterSource>(), Long::class.javaObjectType) } throws cause

        // when
        val batch = assertThatThrownBy { reads.batch(listOf(8L)) }
        val count = assertThatThrownBy { reads.byShelter(7L, PageRequest(1, 20, "id,asc")) }

        // then
        batch.isInstanceOf(AnimalMemberReadUnavailableException::class.java)
            .hasCause(cause)
        count.isInstanceOf(AnimalMemberReadUnavailableException::class.java)
            .hasCause(cause)
    }

    @Test
    fun `등록번호 조회일 때__위임하면__고정된 보호소 투영과 실패를 재사용한다`() {
        // given
        val value = AnimalShelterReads.Shelter(7L, "r", "센터")

        every { shelters.findByCareRegNo(any()) } returns value

        // when
        val found = reads.findByCareRegNo(" r ")

        // then
        assertThat(found).isSameAs(value)
        verify(exactly = 1) { shelters.findByCareRegNo(" r ") }
        confirmVerified(jdbc)
    }

    @Test
    fun `공개 생성자일 때__읽기 범위를 시작하면__검증 전에 기본 읽기 전용 트랜잭션을 요청한다`() {
        // given
        val manager = mockk<PlatformTransactionManager>()
        val source = mockk<DataSource>()

        every { manager.getTransaction(any()) } returns SimpleTransactionStatus()
        justRun { manager.rollback(any()) }

        val publicReads = JdbcAnimalMemberReads(source, manager, shelters)

        // when
        val thrown = assertThatThrownBy { publicReads.byShelter(7L, PageRequest(Int.MAX_VALUE, 20, "id,asc")) }

        // then
        thrown.isInstanceOf(AnimalMemberReadUnavailableException::class.java)
        val definition = slot<TransactionDefinition>()
        verify(exactly = 1) { manager.getTransaction(capture(definition)) }
        assertThat(definition.captured.isReadOnly).isTrue()
        assertThat(definition.captured.propagationBehavior).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED)
        assertThat(definition.captured.isolationLevel).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT)
        assertThat(definition.captured.timeout).isEqualTo(TransactionDefinition.TIMEOUT_DEFAULT)
        verify(exactly = 1) { manager.rollback(any()) }
        confirmVerified(source)
    }

    companion object {
        @JvmStatic
        fun collectionSortPaths(): Stream<Arguments> = Stream.of(
            Arguments.of("shelter.animals.id,asc", "sort_0.id ASC"),
            Arguments.of("shelter_animals_id,asc", "sort_0.id ASC"),
            Arguments.of("shelterAnimalsId,asc", "sort_0.id ASC"),
            Arguments.of("Shelter_Animals_Id,asc", "sort_0.id ASC"),
            Arguments.of("shelter.animals,asc", "sort_0.id ASC"),
            Arguments.of("shelter.animals.shelter.name,asc", "sort_1.name ASC"),
            Arguments.of("shelterAnimalsShelterName,asc", "sort_1.name ASC"),
            Arguments.of("shelter.animals.shelter,asc", "sort_1.id ASC"),
            Arguments.of("shelter.animals.shelter.animals.breed,asc", "sort_2.breed ASC"),
            Arguments.of("shelterAnimalsShelterAnimalsSpecies,asc", "sort_2.species ASC"),
            Arguments.of("shelter.animals.weight,desc,ignorecase", "lower(sort_0.weight) DESC"),
            Arguments.of("shelter.animals.status,desc,ignorecase", "sort_0.status DESC"),
        )
    }
}
