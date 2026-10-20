package com.pawbridge.backend.animal.internal.command.web;

import com.pawbridge.backend.animal.internal.command.infrastructure.persistence.PostgresqlRollbackCharsetViolation;
import com.pawbridge.backend.animal.internal.command.infrastructure.service.AnimalCommandForbiddenException;
import jakarta.persistence.EntityNotFoundException;
import java.sql.BatchUpdateException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;
import static org.assertj.core.api.Assertions.*;

class AnimalCommandExceptionHandlerTest {
    final AnimalCommandExceptionHandler handler=new AnimalCommandExceptionHandler();
    PSQLException error(String schema,String constraint,String state) {
        return new PSQLException(new ServerErrorMessage("SERROR\0C"+state+"\0Mconstraint violation\0s"+schema+"\0n"+constraint+"\0\0"));
    }
    @Test void original_rollback_charset_violation_is_400_through_database_wrapper() {
        var response=handler.database(new DataIntegrityViolationException("check",error("pawbridge_animal","ck_rollback_charset_animals","23514")));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().message()).isEqualTo(PostgresqlRollbackCharsetViolation.MESSAGE);
    }
    @Test void charset_violation_is_400_through_commit_and_batch_next_exception_wrappers() {
        var batch=new BatchUpdateException();batch.setNextException(error("pawbridge_animal","ck_rollback_charset_outbox_events","23514"));
        var response=handler.unexpected(new RuntimeException("commit",batch));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }
    @ParameterizedTest @ValueSource(strings={"foreign-schema","foreign-constraint","foreign-state"})
    void unrelated_database_error_remains_500(String kind) {
        var failure=error(kind.equals("foreign-schema")?"public":"pawbridge_animal",
                kind.equals("foreign-constraint")?"fk_animal_chat":"ck_rollback_charset_animals",kind.equals("foreign-state")?"23503":"23514");
        assertThat(handler.database(new DataIntegrityViolationException("db",failure)).getStatusCode().value()).isEqualTo(500);
        assertThat(handler.unexpected(new RuntimeException("commit",failure)).getStatusCode().value()).isEqualTo(500);
    }
    @Test void original_missing_entity_remains_500_and_new_authorization_denial_is_403() {
        assertThat(handler.unexpected(new EntityNotFoundException("missing")).getStatusCode().value()).isEqualTo(500);
        assertThat(handler.forbidden(new AnimalCommandForbiddenException()).getStatusCode().value()).isEqualTo(403);
    }
}
