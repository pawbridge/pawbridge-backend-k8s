package com.pawbridge.backend.user.internal.directory.infrastructure;

import com.pawbridge.backend.user.internal.directory.application.UserDirectoryQuery;
import com.pawbridge.backend.user.internal.profile.application.UserProfile;
import java.beans.Introspector;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcUserDirectoryQuery implements UserDirectoryQuery {
    private static final String TABLE = " FROM pawbridge_user.users";
    private static final String SELECT = "SELECT user_id, email, name, nickname, provider, role, created_at";
    private static final Map<String,String> SORT_COLUMNS = Map.of(
            "userId","user_id", "email","email", "name","name", "nickname","nickname",
            "provider","provider", "role","role", "createdAt","created_at");
    private static final Set<String> STRING_SORTS = Set.of("email","name","nickname","provider");
    private final JdbcOperations jdbc;
    private final TransactionOperations readOnlyTransaction;

    public JdbcUserDirectoryQuery(JdbcOperations jdbc, TransactionOperations readOnlyTransaction) {
        this.jdbc=jdbc; this.readOnlyTransaction=readOnlyTransaction;
    }

    @Override public Slice page(String keyword, String role, Request request) {
        return readOnlyTransaction.execute(status -> {
            if (request.offset() > Integer.MAX_VALUE)
                throw new org.springframework.dao.InvalidDataAccessApiUsageException("Page offset exceeds Integer.MAX_VALUE (2147483647)");
            String order=request.orders().stream().map(JdbcUserDirectoryQuery::sort).collect(Collectors.joining(", "));
            var conditions=new ArrayList<String>(); var arguments=new ArrayList<Object>();
            if (keyword!=null && !keyword.isEmpty()) {
                // Preserve the declared JPQL wildcard semantics; neither strip nor escape user text.
                conditions.add("(lower(email) LIKE lower(?) ESCAPE '' OR lower(name) LIKE lower(?) ESCAPE '' OR lower(nickname) LIKE lower(?) ESCAPE '')");
                String pattern="%"+keyword+"%";
                arguments.add(pattern); arguments.add(pattern); arguments.add(pattern);
            }
            if (role!=null) { conditions.add("role = ?"); arguments.add(role); }
            String where=conditions.isEmpty()?"":" WHERE "+String.join(" AND ",conditions);
            var pageArguments=new ArrayList<>(arguments); pageArguments.add(request.size()); pageArguments.add(request.offset());
            List<UserProfile> content=jdbc.query(SELECT+TABLE+where+(order.isEmpty()?"":" ORDER BY "+order)+" LIMIT ? OFFSET ?",
                    JdbcUserDirectoryQuery::profile,pageArguments.toArray());
            // Match PageableExecutionUtils: empty later pages and full pages still execute the count.
            long total=content.size()<request.size() && (request.number()==0 || !content.isEmpty())
                    ? request.offset()+content.size()
                    : jdbc.queryForObject("SELECT count(*)"+TABLE+where,Long.class,arguments.toArray());
            return new Slice(content,total);
        });
    }

    private static String sort(Order order) {
        String property=Introspector.decapitalize(order.property()); String column=SORT_COLUMNS.get(property);
        // Deliberately limited to the existing seven-column reader grant; do not select/order secrets.
        if (column==null) throw new IllegalArgumentException("Unsupported user directory sort property");
        if (order.ignoreCase() && STRING_SORTS.contains(property)) column="lower("+column+")";
        return column+(order.ascending()?" ASC":" DESC");
    }

    private static UserProfile profile(ResultSet row,int index)throws SQLException {
        Calendar utc=Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        Timestamp created=row.getTimestamp("created_at",utc);
        return new UserProfile(row.getLong("user_id"),row.getString("email"),row.getString("name"),
                row.getString("nickname"),row.getString("provider"),row.getString("role"),
                created==null?null:created.toLocalDateTime());
    }
}
