package com.pawbridge.backend.animal.internal.travel.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PostgresqlTravelCatalogTest {
    private final JdbcOperations jdbc=mock(JdbcOperations.class);
    private final PostgresqlTravelCatalog catalog=new PostgresqlTravelCatalog(jdbc,new ObjectMapper());
    private static final String PROVIDER="KOREA_TOURISM_ORGANIZATION";

    @Test void selected_postgresql_list_and_count_keep_identical_type_filter_bound_region_and_long_offset() {
        catalog.places("11",Integer.MAX_VALUE);
        var listSql=ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(listSql.capture(),any(RowMapper.class),eq(PROVIDER),eq("11"),eq(21474836470L));
        when(jdbc.queryForObject(anyString(),eq(Long.class),eq(PROVIDER),eq("11"))).thenReturn(0L);
        assertThat(catalog.countPlaces("11")).isZero();
        var countSql=ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(countSql.capture(),eq(Long.class),eq(PROVIDER),eq("11"));
        for(String sql:List.of(listSql.getValue(),countSql.getValue())) {
            assertThat(sql).contains("FROM pawbridge_animal.pet_travel_targets t","t.shown=TRUE","t.area_code IS NOT NULL",
                    "CASE WHEN t.basic_data IS NOT NULL","(t.basic_data->>'contenttypeid')","(p.common_data->>'contenttypeid')",
                    "IN ('12','14','28')","t.area_code=?");
            assertThat(sql).doesNotContain("21474836470","UPDATE ","INSERT ","DELETE ","FOR UPDATE");
        }
        assertThat(listSql.getValue()).endsWith("COALESCE((t.basic_data->>'title'),p.title),t.content_id LIMIT 10 OFFSET ?");
    }

    @ParameterizedTest
    @CsvSource({"LEGACY,false,false,,PREPARING","LEGACY,true,false,,READY","LEGACY,true,true,,STALE",
            "LEGACY,false,false,error,FAILED","LEGACY,true,false,error,STALE",
            "BULK,true,false,future,READY","BULK,true,false,past,STALE"})
    void stored_rows_preserve_discovery_override_photo_rights_and_pet_snapshot_status(String source,boolean fetched,
            boolean pending,String mode,String expected) throws Exception {
        ResultSet row=mock(ResultSet.class);
        when(row.getString("common_data")).thenReturn("{\"contentid\":\"123\",\"title\":\"old\",\"firstimage\":\"old-photo\",\"cpyrhtDivCd\":\"Type1\"}");
        when(row.getString("basic_data")).thenReturn("{\"contentid\":\"123\",\"title\":\"current\",\"contenttypeid\":\"12\"}");
        when(row.getString("image_url")).thenReturn("current-photo");when(row.getString("copyright_type")).thenReturn("Type2");
        when(row.getString("pet_source")).thenReturn(source);when(row.getBoolean("pending")).thenReturn(pending);
        when(row.getString("detail_error")).thenReturn("error".equals(mode)?"DETAIL_FAILED":null);
        when(row.getTimestamp("pet_fetched_at")).thenReturn(fetched?Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")):null);
        when(row.getTimestamp("pet_valid_until")).thenReturn("future".equals(mode)?Timestamp.from(Instant.parse("2100-01-01T00:00:00Z"))
                :"past".equals(mode)?Timestamp.from(Instant.EPOCH):null);
        when(jdbc.query(anyString(),any(RowMapper.class),eq(PROVIDER),eq("123"))).thenAnswer(invocation->{
            String sql=invocation.getArgument(0);
            if(sql.contains("pet_travel_images"))return List.of();
            assertThat(sql).doesNotContain("IN ('12','14','28')");
            RowMapper<?> mapper=invocation.getArgument(1);return List.of(mapper.mapRow(row,0));
        });
        var place=catalog.detail("123").orElseThrow();
        assertThat(place.common()).containsEntry("title","current").containsEntry("firstimage","current-photo").containsEntry("cpyrhtDivCd","Type2");
        assertThat(place.detailStatus()).isEqualTo(expected);assertThat(place.images()).isEmpty();
    }

    @Test void numeric_region_order_and_empty_detail_do_not_probe_database_metadata_or_load_images() {
        catalog.regions();
        verify(jdbc).query(eq("SELECT * FROM pawbridge_animal.pet_travel_regions ORDER BY CAST(code AS INTEGER)"),any(RowMapper.class));
        assertThat(catalog.detail("123")).isEmpty();
        verify(jdbc,never()).query(startsWith("SELECT serial_number"),any(RowMapper.class),any(),any());
    }
}
