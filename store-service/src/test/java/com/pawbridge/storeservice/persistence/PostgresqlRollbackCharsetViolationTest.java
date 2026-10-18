package com.pawbridge.storeservice.persistence;

import com.pawbridge.storeservice.common.exception.GlobalExceptionHandler;
import java.sql.BatchUpdateException;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PostgresqlRollbackCharsetViolationTest {
    static PSQLException error(String state, String constraint, String schema) {
        return new PSQLException(new ServerErrorMessage("SERROR\0C" + state
                + "\0Mprivate_fixture_value\0Dprivate_fixture_row\0s" + schema
                + "\0n" + constraint + "\0"));
    }

    private MockMvc mvc(RuntimeException error) {
        return MockMvcBuilders.standaloneSetup(new FailingController(error))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void charset_constraint_returns_400_without_exposing_sql_or_row_data() throws Exception {
        PSQLException failure = error("23514", "ck_rollback_charset_example", "pawbridge_store");
        mvc(new DataIntegrityViolationException("fixture", failure)).perform(get("/probe"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString(PostgresqlRollbackCharsetViolation.MESSAGE)))
                .andExpect(content().string(not(containsString("private_fixture"))))
                .andExpect(content().string(not(containsString("ck_rollback_charset_"))));
        mvc(new TransactionSystemException("commit", failure)).perform(get("/probe"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void other_constraint_state_schema_and_message_text_do_not_become_user_errors() throws Exception {
        assertThat(PostgresqlRollbackCharsetViolation.matches(error("23505", "ck_rollback_charset_example", "pawbridge_store"))).isFalse();
        assertThat(PostgresqlRollbackCharsetViolation.matches(error("23514", "business_constraint", "pawbridge_store"))).isFalse();
        assertThat(PostgresqlRollbackCharsetViolation.matches(error("23514", "ck_rollback_charset_example", "other_schema"))).isFalse();
        assertThat(PostgresqlRollbackCharsetViolation.matches(new SQLException("ck_rollback_charset_example", "23514"))).isFalse();
        mvc(new DataIntegrityViolationException("fixture", error("23514", "business_constraint", "pawbridge_store")))
                .perform(get("/probe")).andExpect(status().isInternalServerError());
    }

    @Test
    void batched_sql_exception_retains_the_structured_constraint_identity() {
        BatchUpdateException batch = new BatchUpdateException();
        batch.setNextException(error("23514", "ck_rollback_charset_example", "pawbridge_store"));
        assertThat(PostgresqlRollbackCharsetViolation.matches(new DataIntegrityViolationException("batch", batch))).isTrue();
    }

    @RestController
    static class FailingController {
        private final RuntimeException failure;
        FailingController(RuntimeException failure) { this.failure = failure; }
        @GetMapping("/probe")
        public void probe() { throw failure; }
    }
}
