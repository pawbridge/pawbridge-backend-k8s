package com.pawbridge.userservice.contact;

import com.pawbridge.userservice.exception.UserNotFoundException;
import com.pawbridge.userservice.repository.UserRepository;
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
    private final CommunityContactClient communityContactClient;
    private final UserRepository userRepository;
    private final TransactionTemplate transactions;

    public ContactMemberDeletion(
            JdbcTemplate jdbc,
            CommunityContactClient communityContactClient,
            UserRepository userRepository,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.communityContactClient = communityContactClient;
        this.userRepository = userRepository;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setTimeout(15);
    }

    public void delete(long userId) {
        markDeletionPending(userId);
        try {
            // No local transaction spans this HTTP call. Community sees the committed pending flag.
            communityContactClient.removeMailboxes(userId);
        } catch (RuntimeException failure) {
            // Keep a partially erased member pending; an authorized retry resumes the same deletion.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "쪽지 정리가 완료되지 않았습니다. 회원 삭제를 다시 시도해 주세요.");
        }
        eraseAccount(userId);
    }

    private void markDeletionPending(long userId) {
        transactions.executeWithoutResult(status -> {
            var users = jdbc.query(
                    "SELECT user_id FROM pawbridge_user.users WHERE user_id=? FOR UPDATE",
                    (row, rowNumber) -> row.getLong(1), userId);
            if (users.isEmpty()) {
                throw new UserNotFoundException();
            }
            jdbc.update("INSERT INTO pawbridge_user.contact_deletions(user_id) VALUES (?) ON CONFLICT DO NOTHING", userId);
        });
    }

    private void eraseAccount(long userId) {
        transactions.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM pawbridge_user.favorites WHERE user_id=?", userId);
            jdbc.update("DELETE FROM pawbridge_user.refresh_tokens WHERE user_id=?", userId);
            jdbc.update("DELETE FROM pawbridge_user.shelter_applications WHERE user_id=?", userId);
            userRepository.deleteById(userId);
            userRepository.flush();
        });
    }
}
