package com.pawbridge.backend.animal.internal.command.infrastructure.search;

import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SearchDocumentWriterTest {
    final DataSource source=mock(DataSource.class);
    @AfterEach void clearTransactionState() { TransactionSynchronizationManager.clear(); if(TransactionSynchronizationManager.hasResource(source)) TransactionSynchronizationManager.unbindResource(source); }

    @Test void requires_an_actual_transaction_before_borrowing_a_connection() throws Exception {
        try(var analyzer=new KoreanSearchAnalyzer()) {
            assertThatThrownBy(()->new SearchDocumentWriter(source,analyzer).animal(42L)).isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(source);
        }
    }

    @Test void transaction_on_another_data_source_cannot_write_projection() throws Exception {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try(var analyzer=new KoreanSearchAnalyzer()) {
            assertThatThrownBy(()->new SearchDocumentWriter(source,analyzer).animal(42L)).isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(source);
        }
    }

    @Test void read_only_transaction_cannot_write_projection() throws Exception {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        TransactionSynchronizationManager.bindResource(source,new Object());
        try(var analyzer=new KoreanSearchAnalyzer()) {
            assertThatThrownBy(()->new SearchDocumentWriter(source,analyzer).animal(42L)).isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(source);
        }
    }

    @Test void writes_existing_analyzer_terms_to_animal_schema_before_clearing_dirty_flag() throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        try(var analyzer=new KoreanSearchAnalyzer()) {
            SearchDocumentWriter.writeAnimal(jdbc,analyzer,Map.of("id",42L,"search_revision",9L,
                    "breed","믹스견","color","갈색","special_mark","귀가 접힌 강아지","description","순해요","happen_place","서울"));
        }
        var order=inOrder(jdbc);
        order.verify(jdbc).update(startsWith("INSERT INTO pawbridge_animal.animal_search_documents"),
                eq(42L),eq(9L),eq(KoreanSearchAnalyzer.VERSION),anyString(),eq("믹스견"),eq("갈색"),anyString(),anyString(),eq("믹스견"),anyString());
        order.verify(jdbc).update("UPDATE pawbridge_animal.animals SET search_dirty=false WHERE id=?",42L);
    }
}
