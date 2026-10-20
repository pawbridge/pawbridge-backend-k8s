package com.pawbridge.backend.animal.internal.discovery.infrastructure;

import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.*;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JdbcAnimalDiscoveryMappingTest {
    @Test void detail_preserves_every_source_mapper_field_and_local_timestamp_precision() throws Exception {
        LocalDate start = LocalDate.of(2026, 9, 1), end = start.plusDays(10), intake = start.minusDays(1);
        LocalDateTime created = start.atTime(13, 14, 15, 123456000), updated = created.plusHours(2), synced = created.minusMinutes(3);
        Map<String, Object> values = new LinkedHashMap<>(Map.ofEntries(
                Map.entry("id", 72L), Map.entry("apms_notice_no", "서울-강남-2026-1"), Map.entry("species", "DOG"),
                Map.entry("breed", "말티즈"), Map.entry("gender", "FEMALE"), Map.entry("neuter_status", "YES"),
                Map.entry("birth_year", 2022), Map.entry("weight", "3.5(Kg)"), Map.entry("color", "흰색"),
                Map.entry("special_mark", "귀가 접힌"), Map.entry("status", "ADOPTED"), Map.entry("notice_start_date", start),
                Map.entry("notice_end_date", end), Map.entry("happen_place", "역삼동"), Map.entry("happen_date", intake),
                Map.entry("image_url", "https://example.invalid/one.jpg"), Map.entry("image_url2", "https://example.invalid/two.jpg"),
                Map.entry("description", "보호소 설명"), Map.entry("favorite_count", 0), Map.entry("shelter_id", 8L),
                Map.entry("shelter_name", "강남 보호소"), Map.entry("apms_desertion_no", "D-72"),
                Map.entry("apms_process_state", "종료(입양)"), Map.entry("apms_updated_at", synced), Map.entry("api_source", "APMS_ANIMAL"),
                Map.entry("created_at", created), Map.entry("updated_at", updated)));
        assertThat(JdbcAnimalDiscovery.detail(row(values), 0)).isEqualTo(new Details(72L, "서울-강남-2026-1", Species.DOG,
                "말티즈", Gender.FEMALE, NeuterStatus.YES, 2022, LocalDate.now().getYear() - 2022, "3.5(Kg)", "흰색",
                "귀가 접힌", AnimalStatus.ADOPTED, start, end, "역삼동", intake, "https://example.invalid/one.jpg",
                "https://example.invalid/two.jpg", "보호소 설명", 0, 8L, "강남 보호소", "D-72", "종료(입양)", synced,
                ApiSource.APMS_ANIMAL, created, updated));
    }

    @Test void nullable_values_stay_null_and_future_birth_year_keeps_source_age_arithmetic() throws Exception {
        Map<String, Object> values = new LinkedHashMap<>(Map.of("id", 72L, "species", "ETC", "gender", "UNKNOWN",
                "neuter_status", "UNKNOWN", "status", "UNKNOWN", "api_source", "MANUAL", "shelter_id", 8L));
        Details detail = JdbcAnimalDiscovery.detail(row(values), 0);
        assertThat(detail.age()).isNull();
        assertThat(detail.birthYear()).isNull();
        assertThat(detail.updatedAt()).isNull();
        assertThat(detail.imageUrl2()).isNull();
        assertThat(detail.favoriteCount()).isNull();
        Summary summary = JdbcAnimalDiscovery.summary(row(values), 0);
        assertThat(summary.age()).isNull();
        assertThat(summary.createdAt()).isNull();
        values.put("birth_year", LocalDate.now().getYear() + 1);
        assertThat(JdbcAnimalDiscovery.summary(row(values), 0).age()).isEqualTo(-1);
    }

    private static ResultSet row(Map<String, Object> values) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getLong(anyString())).thenAnswer(call -> (Long) values.get(call.getArgument(0)));
        when(row.getString(anyString())).thenAnswer(call -> (String) values.get(call.getArgument(0)));
        doAnswer(call -> values.get(call.getArgument(0))).when(row).getObject(anyString(), any(Class.class));
        return row;
    }
}
