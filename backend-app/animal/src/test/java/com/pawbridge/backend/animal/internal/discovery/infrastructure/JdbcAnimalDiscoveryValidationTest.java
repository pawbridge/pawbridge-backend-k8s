package com.pawbridge.backend.animal.internal.discovery.infrastructure;

import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.*;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Adapted from PostgresqlAnimalQueryValidationTest at the pinned Animal source. */
class JdbcAnimalDiscoveryValidationTest {
    @Test
    void unsupported_sort_and_unbounded_pages_are_rejected_before_query_sql() throws Exception {
        DataSource source = mock(DataSource.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        try (var analyzer = new KoreanSearchAnalyzer()) {
            var service = new JdbcAnimalDiscovery(source, manager, analyzer);
            for (PageRequest page : new PageRequest[]{new PageRequest(0, 20, List.of(), false),
                    page(0, 101), page(500, 20), page(0, 20, "unknown"),
                    new PageRequest(0, 20, List.of(new Order("createdAt", true), new Order("id", true)), true),
                    page(0, 20, "relevance")}) {
                assertThatThrownBy(() -> service.search(Search.empty(), page)).isInstanceOf(IllegalArgumentException.class);
            }
        }
        verifyNoInteractions(source);
    }

    @Test
    void expensive_text_input_is_rejected_before_query_sql() throws Exception {
        DataSource source = mock(DataSource.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        try (var analyzer = new KoreanSearchAnalyzer()) {
            var service = new JdbcAnimalDiscovery(source, manager, analyzer);
            for (String keyword : new String[]{"가".repeat(201), "a b c d e f g h i"}) {
                var request = new Search(null, null, null, null, null, null, null, null, null, keyword, null, null, null, null);
                assertThatThrownBy(() -> service.search(request, page(0, 20))).isInstanceOf(IllegalArgumentException.class);
            }
        }
        verifyNoInteractions(source);
    }

    @Test
    void transaction_begin_failure_precedes_invalid_page_or_sort_without_query_sql() throws Exception {
        DataSource source = mock(DataSource.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        var beginFailure = new CannotCreateTransactionException("synthetic transaction begin failure");
        when(manager.getTransaction(any())).thenThrow(beginFailure);
        try (var analyzer = new KoreanSearchAnalyzer()) {
            var service = new JdbcAnimalDiscovery(source, manager, analyzer);
            for (PageRequest invalid : new PageRequest[]{page(0, 101), page(0, 20, "unknown")}) {
                assertThatThrownBy(() -> service.search(Search.empty(), invalid)).isSameAs(beginFailure);
            }
        }
        verifyNoInteractions(source);
    }

    private static PageRequest page(int number, int size, String... properties) {
        return new PageRequest(number, size, java.util.Arrays.stream(properties).map(p -> new Order(p, true)).toList(), true);
    }
}
