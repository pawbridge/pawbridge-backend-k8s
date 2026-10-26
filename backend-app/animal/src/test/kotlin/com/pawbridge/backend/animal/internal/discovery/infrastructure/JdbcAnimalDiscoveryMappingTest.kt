package com.pawbridge.backend.animal.internal.discovery.infrastructure

import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.AnimalStatus
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.ApiSource
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Details
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Gender
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.NeuterStatus
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Species
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Summary
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.sql.ResultSet
import java.time.LocalDate

class JdbcAnimalDiscoveryMappingTest {
    @Test
    fun `모든 원본 매퍼 필드가 채워진 행일 때__상세를 매핑하면__필드를 모두 보존하고 로컬 타임스탬프 정밀도를 유지한다`() {
        // given
        val start = LocalDate.of(2026, 9, 1)
        val end = start.plusDays(10)
        val intake = start.minusDays(1)
        val created = start.atTime(13, 14, 15, 123456000)
        val updated = created.plusHours(2)
        val synced = created.minusMinutes(3)
        val values: MutableMap<String, Any?> = linkedMapOf(
            "id" to 72L,
            "apms_notice_no" to "서울-강남-2026-1",
            "species" to "DOG",
            "breed" to "말티즈",
            "gender" to "FEMALE",
            "neuter_status" to "YES",
            "birth_year" to 2022,
            "weight" to "3.5(Kg)",
            "color" to "흰색",
            "special_mark" to "귀가 접힌",
            "status" to "ADOPTED",
            "notice_start_date" to start,
            "notice_end_date" to end,
            "happen_place" to "역삼동",
            "happen_date" to intake,
            "image_url" to "https://example.invalid/one.jpg",
            "image_url2" to "https://example.invalid/two.jpg",
            "description" to "보호소 설명",
            "favorite_count" to 0,
            "shelter_id" to 8L,
            "shelter_name" to "강남 보호소",
            "apms_desertion_no" to "D-72",
            "apms_process_state" to "종료(입양)",
            "apms_updated_at" to synced,
            "api_source" to "APMS_ANIMAL",
            "created_at" to created,
            "updated_at" to updated,
        )

        // when
        val detail = JdbcAnimalDiscovery.detail(row(values), 0)

        // then
        assertThat(detail).isEqualTo(
            Details(
                72L, "서울-강남-2026-1", Species.DOG,
                "말티즈", Gender.FEMALE, NeuterStatus.YES, 2022, LocalDate.now().year - 2022, "3.5(Kg)", "흰색",
                "귀가 접힌", AnimalStatus.ADOPTED, start, end, "역삼동", intake, "https://example.invalid/one.jpg",
                "https://example.invalid/two.jpg", "보호소 설명", 0, 8L, "강남 보호소", "D-72", "종료(입양)", synced,
                ApiSource.APMS_ANIMAL, created, updated,
            ),
        )
    }

    @Test
    fun `널 값이 있고 출생연도가 미래인 행일 때__상세와 요약을 매핑하면__널은 널로 두고 원본의 나이 산술을 유지한다`() {
        // given
        val values: MutableMap<String, Any?> = linkedMapOf(
            "id" to 72L,
            "species" to "ETC",
            "gender" to "UNKNOWN",
            "neuter_status" to "UNKNOWN",
            "status" to "UNKNOWN",
            "api_source" to "MANUAL",
            "shelter_id" to 8L,
        )

        // when
        val detail: Details = JdbcAnimalDiscovery.detail(row(values), 0)
        val summary: Summary = JdbcAnimalDiscovery.summary(row(values), 0)

        // then
        assertThat(detail.age).isNull()
        assertThat(detail.birthYear).isNull()
        assertThat(detail.updatedAt).isNull()
        assertThat(detail.imageUrl2).isNull()
        assertThat(detail.favoriteCount).isNull()
        assertThat(summary.age).isNull()
        assertThat(summary.createdAt).isNull()
        values["birth_year"] = LocalDate.now().year + 1
        assertThat(JdbcAnimalDiscovery.summary(row(values), 0).age).isEqualTo(-1)
    }

    /**
     * A hand-written in-memory ResultSet over one row (a data fake, not a mock): `get*` with a column name reads the
     * map at call time, and every other method returns the type's zero value.
     */
    private fun row(values: Map<String, Any?>): ResultSet {
        return Proxy.newProxyInstance(
            ResultSet::class.java.classLoader,
            arrayOf(ResultSet::class.java),
        ) { proxy, method, args ->
            val name = method.name
            if (method.declaringClass == Any::class.java) {
                return@newProxyInstance when (name) {
                    "equals" -> proxy === args[0]
                    "hashCode" -> System.identityHashCode(proxy)
                    else -> "InMemoryResultSet"
                }
            }
            if (name.startsWith("get") && args != null && args[0] is String) {
                val value = values[args[0] as String]
                return@newProxyInstance when (name) {
                    "getLong" -> (value as Long?) ?: 0L
                    else -> value
                }
            }
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
        } as ResultSet
    }
}
