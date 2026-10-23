package com.pawbridge.backend.user.internal.oauth.infrastructure;

import com.pawbridge.backend.user.internal.oauth.application.SocialLogin;
import com.pawbridge.backend.user.internal.oauth.application.SocialLoginRejectedException;
import com.pawbridge.backend.user.internal.nickname.infrastructure.JdbcUserNicknameStore;
import com.pawbridge.backend.user.internal.session.application.AuthSessions;
import com.pawbridge.backend.user.internal.session.infrastructure.JwtSessionTokens;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.support.TransactionOperations;

/** Provider protocol I/O precedes this adapter; account creation and refresh persistence stay separate. */
public final class JdbcSocialLogin implements SocialLogin {
    private static final String FIND_USER="SELECT user_id,email,name,role,care_reg_no,provider FROM pawbridge_user.users WHERE lower(email)=lower(?) AND lower(provider)=lower(?)";
    private static final String FIND_USER_BY_PROVIDER_ID="SELECT user_id,email,name,role,care_reg_no,provider FROM pawbridge_user.users WHERE provider = ? AND provider_id = ?";
    private static final String INSERT_USER="INSERT INTO pawbridge_user.users(email,name,nickname,password,role,care_reg_no,provider,provider_id,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)";
    private static final String LOCK_USER="SELECT user_id FROM pawbridge_user.users WHERE user_id = ? FOR UPDATE";
    private static final String FIND_REFRESH="SELECT refresh_token_id FROM pawbridge_user.refresh_tokens WHERE user_id = ?";
    private static final String UPDATE_REFRESH="UPDATE pawbridge_user.refresh_tokens SET token = ?, expires_at = ? WHERE refresh_token_id = ?";
    private static final String INSERT_REFRESH="INSERT INTO pawbridge_user.refresh_tokens(token,user_id,expires_at,created_at) VALUES (?,?,?,?)";
    private final JdbcOperations jdbc;
    private final TransactionOperations transactions;
    private final JwtSessionTokens tokens;
    private final Supplier<String> nicknames;
    private final Supplier<LocalDateTime> now;

    public JdbcSocialLogin(JdbcOperations jdbc,TransactionOperations transactions,JwtSessionTokens tokens) {
        this(jdbc,transactions,tokens,new SocialNicknameGenerator(new JdbcUserNicknameStore(jdbc))::generateUniqueNickname,LocalDateTime::now);
    }
    public JdbcSocialLogin(JdbcOperations jdbc,TransactionOperations transactions,JwtSessionTokens tokens,
            Supplier<String> nicknames,Supplier<LocalDateTime> now) {
        this.jdbc=jdbc;this.transactions=transactions;this.tokens=tokens;this.nicknames=nicknames;this.now=now;
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

    @Override public AuthSessions.Tokens complete(SocialUser user) {
        String access=tokens.access(new JwtSessionTokens.UserClaims(user.userId(),user.email(),user.name(),user.role(),user.careRegNo()));
        String refresh=tokens.refresh();
        LocalDateTime expires=now.get().plusSeconds(TimeUnit.MILLISECONDS.toSeconds(tokens.refreshExpiration()));
        transactions.executeWithoutResult(status->{
            List<Long> locked=jdbc.query(LOCK_USER,(row,n)->row.getLong("user_id"),user.userId());
            if(locked.size()!=1)throw new IncorrectResultSizeDataAccessException(1,locked.size());
            List<Long> found=jdbc.query(FIND_REFRESH,(row,n)->row.getLong("refresh_token_id"),user.userId());
            if(found.size()>1)throw new IncorrectResultSizeDataAccessException(1,found.size());
            if(found.isEmpty())insertRefresh(user.userId(),refresh,expires);
            else updateRefresh(found.getFirst(),refresh,expires);
        });
        return new AuthSessions.Tokens(access,refresh);
    }

    private void updateRefresh(long id,String refresh,LocalDateTime expires) {
        int changed=jdbc.update(connection->{
            PreparedStatement statement=connection.prepareStatement(UPDATE_REFRESH);
            try {
                statement.setString(1,refresh);statement.setTimestamp(2,Timestamp.valueOf(expires),utc());statement.setLong(3,id);return statement;
            } catch(SQLException | RuntimeException | Error failure) {closeFailed(statement,failure);throw failure;}
        });
        if(changed!=1)throw new OptimisticLockingFailureException("Refresh token disappeared during login");
    }

    private void insertRefresh(long userId,String refresh,LocalDateTime expires) {
        LocalDateTime created=now.get();
        jdbc.update(connection->{
            PreparedStatement statement=connection.prepareStatement(INSERT_REFRESH);
            try {
                statement.setString(1,refresh);statement.setLong(2,userId);statement.setTimestamp(3,Timestamp.valueOf(expires),utc());
                statement.setTimestamp(4,Timestamp.valueOf(created),utc());return statement;
            } catch(SQLException | RuntimeException | Error failure) {closeFailed(statement,failure);throw failure;}
        });
    }
    private static void closeFailed(PreparedStatement statement,Throwable failure) {
        try {statement.close();}catch(SQLException closing){failure.addSuppressed(closing);}
    }
    private static Calendar utc(){return Calendar.getInstance(TimeZone.getTimeZone("UTC"));}
}
