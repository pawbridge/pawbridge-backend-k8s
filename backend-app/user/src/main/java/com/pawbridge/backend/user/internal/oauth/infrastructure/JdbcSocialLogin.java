package com.pawbridge.backend.user.internal.oauth.infrastructure;

import com.pawbridge.backend.user.internal.oauth.application.SocialLogin;
import com.pawbridge.backend.user.internal.oauth.application.SocialLoginRejectedException;
import com.pawbridge.backend.user.internal.nickname.infrastructure.JdbcUserNicknameStore;
import com.pawbridge.backend.user.internal.session.application.IssuedRefreshToken;
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.support.TransactionOperations;

/** Provider protocol I/O precedes this adapter; account creation and session creation stay separate. */
public final class JdbcSocialLogin implements SocialLogin {
    private static final String FIND_USER="SELECT user_id,email,name,role,care_reg_no,provider FROM pawbridge_user.users WHERE lower(email)=lower(?) AND lower(provider)=lower(?)";
    private static final String FIND_USER_BY_PROVIDER_ID="SELECT user_id,email,name,role,care_reg_no,provider FROM pawbridge_user.users WHERE provider = ? AND provider_id = ?";
    private static final String INSERT_USER="INSERT INTO pawbridge_user.users(email,name,nickname,password,role,care_reg_no,provider,provider_id,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)";
    private final JdbcOperations jdbc;
    private final TransactionOperations transactions;
    private final JdbcAuthSessionStore sessions;
    private final Supplier<String> nicknames;
    private final Supplier<LocalDateTime> now;

    public JdbcSocialLogin(JdbcOperations jdbc, TransactionOperations transactions, JdbcAuthSessionStore sessions) {
        this(jdbc, transactions, sessions,
                new SocialNicknameGenerator(new JdbcUserNicknameStore(jdbc))::generateUniqueNickname,
                LocalDateTime::now);
    }

    public JdbcSocialLogin(JdbcOperations jdbc, TransactionOperations transactions, JdbcAuthSessionStore sessions,
            Supplier<String> nicknames, Supplier<LocalDateTime> now) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.sessions = sessions;
        this.nicknames = nicknames;
        this.now = now;
    }

    @Override public SocialUser load(String registrationId,java.util.Map<String,Object> attributes) {
        SocialProfile profile=SocialProfile.from(registrationId,attributes);
        try {
            return loadAccount(profile);
        } catch(DataIntegrityViolationException duplicate) {
            // The failed attempt must finish rollback and cleanup before the single permitted retry.
            return loadAccount(profile);
        }
    }

    private SocialUser loadAccount(SocialProfile profile) {
        return transactions.execute(status->{
            List<SocialUser> existing=findExisting(profile);
            if(!existing.isEmpty()) {
                return existing.getFirst();
            }
            if(!findUser(profile.email(),"LOCAL").isEmpty()) {
                throw new SocialLoginRejectedException("이미 해당 이메일로 가입된 계정이 있습니다. 일반 로그인을 이용해주세요.");
            }
            if(profile.identifiedByProviderId() && !findUser(profile.email(),profile.provider()).isEmpty()) {
                // Same provider and email but another provider ID: never relink an account by email alone.
                throw new SocialLoginRejectedException("이미 다른 소셜 계정에 연결된 이메일입니다. 관리자에게 문의해주세요.");
            }
            String nickname=nicknames.get();
            return insertUser(profile,nickname);
        });
    }

    /**
     * Google keeps its original email lookup. Naver uses its per-application user ID because the
     * contact email can change and is not proof of account ownership.
     */
    private List<SocialUser> findExisting(SocialProfile profile) {
        if(!profile.identifiedByProviderId()) {
            return findUser(profile.email(),profile.provider());
        }
        List<SocialUser> found=jdbc.query(FIND_USER_BY_PROVIDER_ID,JdbcSocialLogin::user,profile.provider(),profile.providerId());
        if(found.size()>1) {
            throw new IncorrectResultSizeDataAccessException(1,found.size());
        }
        return found;
    }

    private List<SocialUser> findUser(String email,String provider) {
        List<SocialUser> found=jdbc.query(FIND_USER,JdbcSocialLogin::user,email,provider);
        if(found.size()>1)throw new IncorrectResultSizeDataAccessException(1,found.size());
        return found;
    }

    private static SocialUser user(java.sql.ResultSet row,int number) throws SQLException {
        return new SocialUser(row.getLong("user_id"),row.getString("email"),row.getString("name"),
                row.getString("role"),row.getString("care_reg_no"),row.getString("provider"));
    }

    private SocialUser insertUser(SocialProfile profile,String nickname) {
        String email=profile.email();
        String name=profile.name();
        LocalDateTime timestamp=now.get();var generated=new GeneratedKeyHolder();
        jdbc.update(connection->{
            PreparedStatement statement=connection.prepareStatement(INSERT_USER,new String[]{"user_id"});
            try {
                statement.setString(1,email);statement.setString(2,name);statement.setString(3,nickname);
                statement.setString(4,"OAuth2");statement.setString(5,"ROLE_USER");statement.setString(6,null);
                statement.setString(7,profile.provider());statement.setString(8,profile.providerId());
                statement.setTimestamp(9,Timestamp.valueOf(timestamp),utc());statement.setTimestamp(10,Timestamp.valueOf(timestamp),utc());
                return statement;
            } catch(SQLException | RuntimeException | Error failure) { closeFailed(statement,failure);throw failure; }
        },generated);
        Number id=generated.getKey();
        if(id==null)throw new IllegalStateException("Social account ID was not returned");
        return new SocialUser(id.longValue(),email,name,"ROLE_USER",null,profile.provider());
    }

    /** The session store locks the user row, so a concurrent account deletion cannot leave an orphan session. */
    @Override
    public IssuedRefreshToken complete(SocialUser user) {
        return transactions.execute(status -> sessions.create(user.userId()));
    }

    private static void closeFailed(PreparedStatement statement,Throwable failure) {
        try {statement.close();}catch(SQLException closing){failure.addSuppressed(closing);}
    }
    private static Calendar utc(){return Calendar.getInstance(TimeZone.getTimeZone("UTC"));}
}
