package com.pawbridge.userservice.contact;

import com.pawbridge.userservice.repository.UserRepository;
import com.pawbridge.userservice.exception.UserNotFoundException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("postgresql")
public class ContactMemberDeletion {
    private final JdbcTemplate jdbc;
    private final CommunityContactClient community;
    private final UserRepository users;
    private final TransactionTemplate tx;
    public ContactMemberDeletion(JdbcTemplate jdbc,CommunityContactClient community,UserRepository users,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.community=community;this.users=users;this.tx=new TransactionTemplate(manager);
        tx.setTimeout(15);
    }
    public void delete(long member) {
        // Commit first: Community checks this flag while holding its member locks.
        tx.executeWithoutResult(status -> {
            var rows=jdbc.query("SELECT user_id FROM pawbridge_user.users WHERE user_id=? FOR UPDATE",(r,i)->r.getLong(1),member);
            if(rows.isEmpty()) throw new UserNotFoundException();
            jdbc.update("INSERT INTO pawbridge_user.contact_deletions(user_id) VALUES (?) ON CONFLICT DO NOTHING",member);
        });
        try {community.removeMailboxes(member);}
        catch(RuntimeException failure) {
            // Do not reactivate a partially erased member. The same authorized deletion can retry.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"쪽지 정리가 완료되지 않았습니다. 회원 삭제를 다시 시도해 주세요.");
        }
        tx.executeWithoutResult(status -> {
            // Existing account-owned relations must not prevent completing the coordinated deletion.
            jdbc.update("DELETE FROM pawbridge_user.favorites WHERE user_id=?",member);
            jdbc.update("DELETE FROM pawbridge_user.refresh_tokens WHERE user_id=?",member);
            jdbc.update("DELETE FROM pawbridge_user.shelter_applications WHERE user_id=?",member);
            users.deleteById(member);
            users.flush();
        });
    }
}
