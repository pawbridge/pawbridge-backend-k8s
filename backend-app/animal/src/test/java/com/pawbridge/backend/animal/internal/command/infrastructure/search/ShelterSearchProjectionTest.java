package com.pawbridge.backend.animal.internal.command.infrastructure.search;

import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShelterSearchProjectionTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final KoreanSearchAnalyzer analyzer=mock(KoreanSearchAnalyzer.class);
    final Map<String,Object> row=Map.of("id",7L,"search_revision",3L,"name","서울 센터","address","강남");
    @Test void givenShelterTerms__whenProjecting__thenUseOriginalWeightRevisionAndClearOnlyItsDirtyFlag() throws Exception {
        when(analyzer.shelterTerms("서울 센터","강남")).thenReturn(new KoreanSearchAnalyzer.ShelterTerms("서울 센터","강남"));
        SearchDocumentWriter.writeShelter(jdbc,analyzer,row);
        verify(jdbc).update(contains("INSERT INTO pawbridge_animal.shelter_search_documents"),eq(7L),eq(3L),eq(KoreanSearchAnalyzer.VERSION),eq("서울 센터"),eq("강남"));
        verify(jdbc).update("UPDATE pawbridge_animal.shelters SET search_dirty=false WHERE id=?",7L);
    }
    @Test void givenAnalyzerFailure__whenProjecting__thenWriteNothingAndPropagateForWriterRollback() throws Exception {
        when(analyzer.shelterTerms(any(),any())).thenThrow(new IOException("analyzer"));
        assertThatThrownBy(()->SearchDocumentWriter.writeShelter(jdbc,analyzer,row)).isInstanceOf(java.io.UncheckedIOException.class);
        verifyNoInteractions(jdbc);
    }
}
