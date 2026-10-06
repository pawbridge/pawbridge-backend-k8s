package com.pawbridge.communityservice.contact;

import com.pawbridge.communityservice.client.UserServiceClient;
import com.pawbridge.communityservice.contact.PrivateNoteModels.BlockPage;
import com.pawbridge.communityservice.contact.PrivateNoteModels.BlockView;
import com.pawbridge.communityservice.contact.PrivateNoteModels.ContactMember;
import com.pawbridge.communityservice.contact.PrivateNoteModels.Note;
import com.pawbridge.communityservice.contact.PrivateNoteModels.NotePage;
import com.pawbridge.communityservice.contact.PrivateNoteModels.NoteView;
import com.pawbridge.communityservice.contact.PrivateNoteModels.Notification;
import com.pawbridge.communityservice.contact.PrivateNoteModels.Notifications;
import com.pawbridge.communityservice.contact.PrivateNoteModels.Receipt;
import com.pawbridge.communityservice.contact.PrivateNoteModels.SendNote;
import feign.FeignException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("postgresql")
public class PrivateNoteService {
    private static final Logger log = LoggerFactory.getLogger(PrivateNoteService.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int NOTE_PAGE_SIZE = 10;
    private static final int NOTIFICATION_PAGE_SIZE = 20;

    private final PrivateNoteRepository privateNoteRepository;
    private final UserServiceClient userServiceClient;
    private final PrivateNoteStream privateNoteStream;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public PrivateNoteService(
            PrivateNoteRepository privateNoteRepository,
            UserServiceClient userServiceClient,
            PrivateNoteStream privateNoteStream,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.privateNoteRepository = privateNoteRepository;
        this.userServiceClient = userServiceClient;
        this.privateNoteStream = privateNoteStream;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setTimeout(15);
    }

    public Receipt send(long currentUserId, SendNote request) {
        requireId(currentUserId);
        String body = validate(request, currentUserId);
        String requestHash = hash(request, body);
        return transactions.execute(status -> {
            privateNoteRepository.lockMembers(currentUserId, request.recipientId());
            ContactMember sender = active(currentUserId);
            active(request.recipientId());

            var previousRequest = privateNoteRepository.request(currentUserId, request.requestId());
            if (previousRequest.isPresent()) {
                var previous = previousRequest.get();
                if (previous.noteId() == null || previous.hash() == null) {
                    throw error(HttpStatus.GONE, "이 전송은 이미 처리되었으며 더 이상 보관되어 있지 않습니다.");
                }
                if (!requestHash.equals(previous.hash())) {
                    throw error(HttpStatus.CONFLICT, "같은 전송 키로 다른 내용을 보낼 수 없습니다.");
                }
                return new Receipt(previous.noteId());
            }
            if (privateNoteRepository.blocked(currentUserId, request.recipientId())) {
                throw error(HttpStatus.FORBIDDEN, "차단된 상대에게 쪽지를 보낼 수 없습니다.");
            }

            Instant now = clock.instant();
            Instant startOfKoreanDay = now.atZone(SEOUL).toLocalDate().atStartOfDay(SEOUL).toInstant();
            if (privateNoteRepository.rate(currentUserId, now.minusSeconds(60)) >= 10
                    || privateNoteRepository.rate(currentUserId, startOfKoreanDay) >= 100) {
                throw error(HttpStatus.TOO_MANY_REQUESTS, "쪽지 발송 한도에 도달했습니다. 잠시 후 다시 이용해 주세요.");
            }
            validateReply(currentUserId, request);
            validateContext(request);

            UUID noteId = UUID.randomUUID();
            Instant expiresAt = now.atZone(ZoneOffset.UTC).plusYears(1).toInstant();
            privateNoteRepository.insert(currentUserId, request, body, noteId, requestHash, now, expiresAt);
            Notification notification = new Notification(
                    noteId, "PRIVATE_NOTE", sender.userId(), sender.nickname(), now, false, "/notes/" + noteId);
            afterCommit(() -> privateNoteStream.publish(request.recipientId(), notification));
            return new Receipt(noteId);
        });
    }

    public NotePage list(long currentUserId, String box, boolean favorites, int page) {
        requirePage(page);
        if (!List.of("INBOX", "SENT").contains(box)) {
            throw error(HttpStatus.BAD_REQUEST, "쪽지함을 확인해 주세요.");
        }
        active(currentUserId);
        Instant now = clock.instant();
        long count = privateNoteRepository.count(currentUserId, box, favorites, now);
        List<Note> notes = privateNoteRepository.list(currentUserId, box, favorites, page, now);
        Map<Long, ContactMember> counterparts = lookupMembers(notes.stream().map(this::counterpartId).toList());
        List<NoteView> content = notes.stream()
                .map(note -> view(note, display(counterpartId(note), counterparts)))
                .toList();
        return new NotePage(content, count, totalPages(count), page, NOTE_PAGE_SIZE);
    }

    public NoteView get(long currentUserId, UUID noteId) {
        active(currentUserId);
        Note note = find(currentUserId, noteId);
        return view(note, display(counterpartId(note)));
    }

    public Notifications notifications(long currentUserId, UUID cursor) {
        active(currentUserId);
        Instant now = clock.instant();
        List<Note> found = privateNoteRepository.notifications(currentUserId, cursor, now);
        List<Note> page = found.stream().limit(NOTIFICATION_PAGE_SIZE).toList();
        Map<Long, ContactMember> senders = lookupMembers(page.stream().map(Note::senderId).toList());
        List<Notification> content = page.stream()
                .map(note -> notification(note, display(note.senderId(), senders)))
                .toList();
        UUID nextCursor = found.size() > NOTIFICATION_PAGE_SIZE
                ? content.get(content.size() - 1).noteId() : null;
        return new Notifications(content, nextCursor, privateNoteRepository.unread(currentUserId, now));
    }

    public void read(long currentUserId, UUID noteId) {
        mutate(currentUserId, noteId, () -> privateNoteRepository.read(currentUserId, noteId, clock.instant()));
    }

    public void favorite(long currentUserId, UUID noteId, boolean favorite) {
        mutate(currentUserId, noteId, () -> privateNoteRepository.favorite(currentUserId, noteId, favorite));
    }

    public void delete(long currentUserId, UUID noteId) {
        mutate(currentUserId, noteId, () -> privateNoteRepository.delete(currentUserId, noteId));
    }

    private void mutate(long currentUserId, UUID noteId, Runnable change) {
        transactions.executeWithoutResult(status -> {
            privateNoteRepository.lockMembers(currentUserId, currentUserId);
            active(currentUserId);
            find(currentUserId, noteId);
            privateNoteRepository.lockNote(noteId);
            // Recheck ownership after locking: another deletion may have removed the original.
            find(currentUserId, noteId);
            change.run();
            afterCommit(() -> privateNoteStream.resync(currentUserId));
        });
    }

    public void block(long currentUserId, long blockedUserId) {
        requireId(currentUserId);
        requireId(blockedUserId);
        if (currentUserId == blockedUserId) {
            throw error(HttpStatus.BAD_REQUEST, "자신을 차단할 수 없습니다.");
        }
        transactions.executeWithoutResult(status -> {
            privateNoteRepository.lockMembers(currentUserId, blockedUserId);
            active(currentUserId);
            active(blockedUserId);
            privateNoteRepository.block(currentUserId, blockedUserId, clock.instant());
        });
    }

    public void unblock(long currentUserId, long blockedUserId) {
        requireId(currentUserId);
        requireId(blockedUserId);
        transactions.executeWithoutResult(status -> {
            privateNoteRepository.lockMembers(currentUserId, blockedUserId);
            active(currentUserId);
            privateNoteRepository.unblock(currentUserId, blockedUserId);
        });
    }

    public BlockPage blocks(long currentUserId, int page) {
        requirePage(page);
        active(currentUserId);
        long count = privateNoteRepository.blockCount(currentUserId);
        List<PrivateNoteRepository.Block> blocks = privateNoteRepository.blocks(currentUserId, page);
        Map<Long, ContactMember> members = lookupMembers(blocks.stream().map(PrivateNoteRepository.Block::id).toList());
        List<BlockView> content = blocks.stream()
                .map(block -> new BlockView(block.id(), display(block.id(), members).nickname(), block.createdAt()))
                .toList();
        return new BlockPage(content, count, totalPages(count), page);
    }

    public ContactMember recipient(long currentUserId, long recipientId) {
        active(currentUserId);
        if (currentUserId == recipientId || privateNoteRepository.blocked(currentUserId, recipientId)) {
            throw error(HttpStatus.FORBIDDEN, "이 상대에게 쪽지를 보낼 수 없습니다.");
        }
        return active(recipientId);
    }

    /** Not routed by Gateway. Independently verifies User's committed deletion state. */
    public void withdraw(long userId) {
        requireId(userId);
        transactions.executeWithoutResult(status -> {
            privateNoteRepository.lockMembers(userId, userId);
            ContactMember member = lookup(userId);
            if (member.active() || !member.deletionPending()) {
                throw error(HttpStatus.CONFLICT, "회원 삭제 준비 상태가 아닙니다.");
            }
            privateNoteRepository.withdraw(userId);
            afterCommit(() -> {
                privateNoteStream.close(userId);
                privateNoteStream.resyncAll();
            });
        });
    }

    public void expire() {
        transactions.executeWithoutResult(status -> privateNoteRepository.expire(clock.instant()));
    }

    public void requireActive(long currentUserId) {
        active(currentUserId);
    }

    private void validateReply(long currentUserId, SendNote request) {
        if (request.replyTo() == null) {
            return;
        }
        Note parent = find(currentUserId, request.replyTo());
        if (!"INBOX".equals(parent.direction()) || !Objects.equals(parent.senderId(), request.recipientId())) {
            throw error(HttpStatus.BAD_REQUEST, "답장할 쪽지와 받는 사람이 일치하지 않습니다.");
        }
    }

    private void validateContext(SendNote request) {
        if (request.contextType() != null
                && !privateNoteRepository.contextAuthor(request.contextType(), request.contextId(), request.recipientId())) {
            throw error(HttpStatus.BAD_REQUEST, "연락할 글의 작성자를 확인해 주세요.");
        }
    }

    private Note find(long currentUserId, UUID noteId) {
        return privateNoteRepository.find(currentUserId, noteId, clock.instant())
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "쪽지를 찾을 수 없습니다."));
    }

    private Long counterpartId(Note note) {
        return "INBOX".equals(note.direction()) ? note.senderId() : note.recipientId();
    }

    private NoteView view(Note note, ContactMember counterpart) {
        String contextHref = note.contextType() == null ? null
                : ("POST".equals(note.contextType()) ? "/community/" : "/reports/") + note.contextId();
        boolean canReply = "INBOX".equals(note.direction()) && counterpart.active()
                && !privateNoteRepository.blocked(note.recipientId(), counterpart.userId());
        return new NoteView(
                note.noteId(), note.body(), note.direction(), note.createdAt(), note.readAt(), note.favorite(),
                counterpart.active() ? counterpart.userId() : null, counterpart.nickname(), canReply, contextHref);
    }

    private Notification notification(Note note, ContactMember sender) {
        return new Notification(
                note.noteId(), "PRIVATE_NOTE", sender.active() ? sender.userId() : null, sender.nickname(),
                note.createdAt(), note.readAt() != null, "/notes/" + note.noteId());
    }

    private ContactMember display(Long userId) {
        return userId == null ? withdrawnMember() : display(lookup(userId));
    }

    private ContactMember display(Long userId, Map<Long, ContactMember> membersById) {
        return userId == null ? withdrawnMember() : display(membersById.get(userId));
    }

    private ContactMember display(ContactMember member) {
        return member.active() ? member : withdrawnMember();
    }

    private ContactMember withdrawnMember() {
        return new ContactMember(null, "탈퇴한 회원", false, false);
    }

    private ContactMember active(long userId) {
        requireId(userId);
        ContactMember member = lookup(userId);
        if (!member.active()) {
            throw error(HttpStatus.FORBIDDEN, "이용할 수 없는 회원입니다.");
        }
        return member;
    }

    private ContactMember lookup(long userId) {
        try {
            ContactMember member = userServiceClient.getContactMember(userId);
            if (member == null || !Long.valueOf(userId).equals(member.userId())) {
                throw memberLookupUnavailable();
            }
            return member;
        } catch (FeignException failure) {
            if (failure.status() == 404) {
                return new ContactMember(userId, "탈퇴한 회원", false, false);
            }
            throw memberLookupUnavailable();
        }
    }

    private Map<Long, ContactMember> lookupMembers(List<Long> memberIds) {
        List<Long> distinctIds = memberIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinctIds.isEmpty()) {
            return Map.of();
        }
        List<ContactMember> members;
        try {
            members = userServiceClient.getContactMembers(distinctIds);
        } catch (FeignException failure) {
            throw memberLookupUnavailable();
        }
        if (members == null || members.size() != distinctIds.size()) {
            throw memberLookupUnavailable();
        }
        Map<Long, ContactMember> membersById = new HashMap<>();
        for (ContactMember member : members) {
            if (member == null || !distinctIds.contains(member.userId())
                    || membersById.putIfAbsent(member.userId(), member) != null) {
                throw memberLookupUnavailable();
            }
        }
        return membersById;
    }

    private static ResponseStatusException memberLookupUnavailable() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.");
    }

    private static String validate(SendNote request, long currentUserId) {
        if (request == null || request.recipientId() == null || request.recipientId() <= 0
                || request.recipientId() == currentUserId || request.requestId() == null || request.body() == null) {
            throw error(HttpStatus.BAD_REQUEST, "받는 사람과 쪽지 내용을 확인해 주세요.");
        }
        String body = request.body().strip();
        if (body.isBlank() || body.codePointCount(0, body.length()) > 5000 || body.indexOf('\0') >= 0) {
            throw error(HttpStatus.BAD_REQUEST, "쪽지는 1~5,000자로 작성해 주세요.");
        }
        boolean incompleteContext = (request.contextType() == null) != (request.contextId() == null);
        boolean invalidContext = request.contextType() != null && request.contextId() != null
                && (!List.of("POST", "REPORT").contains(request.contextType()) || request.contextId() <= 0);
        if (incompleteContext || invalidContext) {
            throw error(HttpStatus.BAD_REQUEST, "연락할 글을 확인해 주세요.");
        }
        return body;
    }

    private static String hash(SendNote request, String body) {
        String payload = request.recipientId() + "\n" + request.replyTo() + "\n"
                + request.contextType() + "\n" + request.contextId() + "\n" + body;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    action.run();
                } catch (RuntimeException failure) {
                    // Persistence already succeeded; UI push failure must not change the send result.
                    log.warn("Private note push failed ({}); REST restoration remains available",
                            failure.getClass().getSimpleName());
                }
            }
        });
    }

    private static int totalPages(long count) {
        return (int) ((count + NOTE_PAGE_SIZE - 1) / NOTE_PAGE_SIZE);
    }

    private static void requirePage(int page) {
        if (page < 0 || page > 10000) {
            throw error(HttpStatus.BAD_REQUEST, "페이지 범위를 확인해 주세요.");
        }
    }

    private static void requireId(long userId) {
        if (userId <= 0) {
            throw error(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        }
    }

    private static ResponseStatusException error(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }
}
