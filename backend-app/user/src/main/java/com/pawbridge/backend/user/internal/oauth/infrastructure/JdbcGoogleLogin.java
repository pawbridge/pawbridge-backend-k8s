package com.pawbridge.backend.user.internal.oauth.infrastructure;

import com.pawbridge.backend.user.internal.oauth.application.GoogleLogin;
import com.pawbridge.backend.user.internal.oauth.application.GoogleLoginRejectedException;
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

/** Google protocol I/O precedes this adapter; account creation and refresh persistence stay separate. */
public final class JdbcGoogleLogin implements GoogleLogin {
    private static final String FIND_USER="SELECT user_id,email,name,role,care_reg_no,provider FROM pawbridge_user.users WHERE lower(email)=lower(?) AND lower(provider)=lower(?)";
    private static final String INSERT_USER="INSERT INTO pawbridge_user.users(email,name,nickname,password,role,care_reg_no,provider,provider_id,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)";
    private static final String FIND_REFRESH="SELECT refresh_token_id FROM pawbridge_user.refresh_tokens WHERE user_id = ?";
    private static final String UPDATE_REFRESH="UPDATE pawbridge_user.refresh_tokens SET token = ?, expires_at = ? WHERE refresh_token_id = ?";
    private static final String INSERT_REFRESH="INSERT INTO pawbridge_user.refresh_tokens(token,user_id,expires_at,created_at) VALUES (?,?,?,?)";
    private final JdbcOperations jdbc;
    private final TransactionOperations transactions;
    private final JwtSessionTokens tokens;
    private final Supplier<String> nicknames;
    private final Supplier<LocalDateTime> now;

    public JdbcGoogleLogin(JdbcOperations jdbc,TransactionOperations transactions,JwtSessionTokens tokens) {
        this(jdbc,transactions,tokens,new GoogleNicknameGenerator(new JdbcUserNicknameStore(jdbc))::generateUniqueNickname,LocalDateTime::now);
    }
    public JdbcGoogleLogin(JdbcOperations jdbc,TransactionOperations transactions,JwtSessionTokens tokens,
            Supplier<String> nicknames,Supplier<LocalDateTime> now) {
        this.jdbc=jdbc;this.transactions=transactions;this.tokens=tokens;this.nicknames=nicknames;this.now=now;
    }

    @Override public GoogleUser load(String registrationId,java.util.Map<String,Object> attributes) {
        if(!"google".equalsIgnoreCase(registrationId))
            throw new GoogleLoginRejectedException("지원하지 않는 로그인 방식입니다: "+registrationId);
        String email=(String)attributes.get("email");
        if(email==null || email.isEmpty())throw new GoogleLoginRejectedException("이메일 정보를 가져올 수 없습니다");
        String name=(String)attributes.get("name");
        String providerId=(String)attributes.get("sub");
        return transactions.execute(status->{
            List<GoogleUser> existing=findUser(email,"GOOGLE");
            if(!existing.isEmpty())return existing.getFirst();
            if(!findUser(email,"LOCAL").isEmpty())
                throw new GoogleLoginRejectedException("이미 해당 이메일로 가입된 계정이 있습니다. 일반 로그인을 이용해주세요.");
            String nickname=nicknames.get();
            try {return insertUser(email,name,providerId,nickname);}
            catch(DataIntegrityViolationException duplicate) {
                // Preserve the source's one further attempt; do not invent savepoints or a new transaction.
                // Actual PostgreSQL abort/JPA flush behavior is a separate, currently unverified boundary.
                return insertUser(email,name,providerId,nicknames.get());
            }
        });
    }

    private List<GoogleUser> findUser(String email,String provider) {
        List<GoogleUser> found=jdbc.query(FIND_USER,(row,n)->new GoogleUser(row.getLong("user_id"),row.getString("email"),
                row.getString("name"),row.getString("role"),row.getString("care_reg_no"),row.getString("provider")),email,provider);
        if(found.size()>1)throw new IncorrectResultSizeDataAccessException(1,found.size());
        return found;
    }

    private GoogleUser insertUser(String email,String name,String providerId,String nickname) {
        LocalDateTime timestamp=now.get();var generated=new GeneratedKeyHolder();
        jdbc.update(connection->{
            PreparedStatement statement=connection.prepareStatement(INSERT_USER,new String[]{"user_id"});
            try {
                statement.setString(1,email);statement.setString(2,name);statement.setString(3,nickname);
                statement.setString(4,"OAuth2");statement.setString(5,"ROLE_USER");statement.setString(6,null);
                statement.setString(7,"GOOGLE");statement.setString(8,providerId);
                statement.setTimestamp(9,Timestamp.valueOf(timestamp),utc());statement.setTimestamp(10,Timestamp.valueOf(timestamp),utc());
                return statement;
            } catch(SQLException | RuntimeException | Error failure) { closeFailed(statement,failure);throw failure; }
        },generated);
        Number id=generated.getKey();
        if(id==null)throw new IllegalStateException("Social account ID was not returned");
        return new GoogleUser(id.longValue(),email,name,"ROLE_USER",null,"GOOGLE");
    }

    @Override public AuthSessions.Tokens complete(GoogleUser user) {
        String access=tokens.access(new JwtSessionTokens.UserClaims(user.userId(),user.email(),user.name(),user.role(),user.careRegNo()));
        String refresh=tokens.refresh();
        LocalDateTime expires=now.get().plusSeconds(TimeUnit.MILLISECONDS.toSeconds(tokens.refreshExpiration()));
        List<Long> found=jdbc.query(FIND_REFRESH,(row,n)->row.getLong("refresh_token_id"),user.userId());
        if(found.size()>1)throw new IncorrectResultSizeDataAccessException(1,found.size());
        transactions.executeWithoutResult(status->{
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
