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
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.sql.ResultSet
import java.time.LocalDate

class JdbcAnimalDiscoveryMappingTest {
    @Test
    fun detail_preserves_every_source_mapper_field_and_local_timestamp_precision() {
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

        assertThat(JdbcAnimalDiscovery.detail(row(values), 0)).isEqualTo(
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
    fun nullable_values_stay_null_and_future_birth_year_keeps_source_age_arithmetic() {
        val values: MutableMap<String, Any?> = linkedMapOf(
            "id" to 72L,
            "species" to "ETC",
            "gender" to "UNKNOWN",
            "neuter_status" to "UNKNOWN",
            "status" to "UNKNOWN",
            "api_source" to "MANUAL",
            "shelter_id" to 8L,
        )

        val detail: Details = JdbcAnimalDiscovery.detail(row(values), 0)

        assertThat(detail.age).isNull()
        assertThat(detail.birthYear).isNull()
        assertThat(detail.updatedAt).isNull()
        assertThat(detail.imageUrl2).isNull()
        assertThat(detail.favoriteCount).isNull()
        val summary: Summary = JdbcAnimalDiscovery.summary(row(values), 0)
        assertThat(summary.age).isNull()
        assertThat(summary.createdAt).isNull()
        values["birth_year"] = LocalDate.now().year + 1
        assertThat(JdbcAnimalDiscovery.summary(row(values), 0).age).isEqualTo(-1)
    }

    private fun row(values: Map<String, Any?>): ResultSet {
        val row = mock(ResultSet::class.java)
        `when`(row.getLong(anyString())).thenAnswer { call -> values[call.getArgument<String>(0)] as Long? }
        `when`(row.getString(anyString())).thenAnswer { call -> values[call.getArgument<String>(0)] as String? }
        doAnswer { call -> values[call.getArgument<String>(0)] }
            .`when`(row).getObject(anyString(), any(Class::class.java))
        return row
    }
}
