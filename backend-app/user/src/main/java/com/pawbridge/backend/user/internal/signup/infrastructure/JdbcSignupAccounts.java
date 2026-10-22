package com.pawbridge.backend.user.internal.signup.infrastructure;

import com.pawbridge.backend.user.internal.signup.application.SignedUp;
import com.pawbridge.backend.user.internal.signup.application.SignupAccounts;
import com.pawbridge.backend.user.internal.signup.application.SignupRole;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.support.GeneratedKeyHolder;

public final class JdbcSignupAccounts implements SignupAccounts {
    private static final Logger LOG = LoggerFactory.getLogger(JdbcSignupAccounts.class);
    private static final String EXISTS_LOCAL_EMAIL = """
            SELECT (count(*) > 0)
            FROM pawbridge_user.users
            WHERE lower(email) = lower(?) AND lower(provider) = lower(?)
            """;
    private static final String INSERT_USER = "INSERT INTO pawbridge_user.users(email,name,nickname,password,role,"
            + "care_reg_no,provider,provider_id,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)";

    private final JdbcOperations jdbc;
    private final Supplier<String> nicknames;
    private final Supplier<LocalDateTime> now;

    public JdbcSignupAccounts(JdbcOperations jdbc, Supplier<String> nicknames) {
        this(jdbc, nicknames, LocalDateTime::now);
    }

    public JdbcSignupAccounts(JdbcOperations jdbc, Supplier<String> nicknames, Supplier<LocalDateTime> now) {
        this.jdbc = jdbc;
        this.nicknames = nicknames;
        this.now = now;
    }

    @Override
    public boolean existsLocalEmail(String email) {
        Boolean exists = jdbc.queryForObject(EXISTS_LOCAL_EMAIL, Boolean.class, email, "LOCAL");
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public SignedUp createLocalUser(String email, String name, String encodedPassword, SignupRole role,
            String careRegNo) {
        String nickname = nicknames.get();
        try {
            return insert(email, name, encodedPassword, nickname, role, careRegNo);
        } catch (DataIntegrityViolationException duplicate) {
            if (isRollbackCharsetViolation(duplicate)) {
                throw duplicate;
            }
            // The original retries once with a new nickname inside the same transaction.
            LOG.warn("Signup nickname conflict, regenerating");
            return insert(email, name, encodedPassword, nicknames.get(), role, careRegNo);
        }
    }

    private SignedUp insert(String email, String name, String encodedPassword, String nickname, SignupRole role,
            String careRegNo) {
        LocalDateTime timestamp = now.get();
        var generated = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(INSERT_USER, new String[] {"user_id"});
            try {
                statement.setString(1, email);
                statement.setString(2, name);
                statement.setString(3, nickname);
                statement.setString(4, encodedPassword);
                statement.setString(5, role.name());
                statement.setString(6, careRegNo);
                statement.setString(7, "LOCAL");
                statement.setString(8, null);
                Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
                statement.setTimestamp(9, Timestamp.valueOf(timestamp), utc);
                statement.setTimestamp(10, Timestamp.valueOf(timestamp), utc);
                return statement;
            } catch (SQLException | RuntimeException | Error failure) {
                try { statement.close(); } catch (SQLException closing) { failure.addSuppressed(closing); }
                throw failure;
            }
        }, generated);
        Number id = generated.getKey();
        if (id == null) {
            throw new IllegalStateException("Signup account ID was not returned");
        }
        return new SignedUp(id.longValue(), email, name, nickname);
    }

    static boolean isRollbackCharsetViolation(Throwable failure) {
        return RollbackCharsetViolation.matches(failure);
    }
}
