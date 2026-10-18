package com.pawbridge.userservice.contact;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class ContactMemberQueryTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ContactMemberQuery query = new ContactMemberQuery(jdbc);

    @Test
    void givenInvalidMemberBatch_whenQueried_thenRejectBeforeDatabase() {
        List<List<Long>> invalidBatches = Arrays.asList(
                null, List.of(), List.of(0L), List.of(-1L), Arrays.asList(1L, null),
                LongStream.rangeClosed(1, 22).boxed().toList());

        for (List<Long> invalid : invalidBatches) {
            assertThatThrownBy(() -> query.getAll(invalid))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(failure -> org.assertj.core.api.Assertions
                            .assertThat(((ResponseStatusException) failure).getStatusCode().value()).isEqualTo(400));
        }
        verifyNoInteractions(jdbc);
    }
}
