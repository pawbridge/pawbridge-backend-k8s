package com.pawbridge.backend.community.internal.chat.infrastructure;

import static com.pawbridge.backend.community.internal.chat.application.MemberChatModels.*;

import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import com.pawbridge.backend.community.internal.chat.application.MemberChat;
import com.pawbridge.backend.community.internal.chat.application.MemberChatHints;
import com.pawbridge.backend.community.internal.chat.application.MemberChatRejectedException;
import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.community.internal.note.application.NoteSendStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class JdbcMemberChat implements MemberChat {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final JdbcMemberChatRepository repository;
    private final NoteSendStore contact;
    private final ContactMemberQuery users;
    private final MemberChatHints signals;
    private final Clock clock;
    private final org.springframework.transaction.support.TransactionOperations transactions;

    public JdbcMemberChat(JdbcMemberChatRepository repository, NoteSendStore contact,
                             ContactMemberQuery users, MemberChatHints signals, Clock clock,
                             org.springframework.transaction.support.TransactionOperations transactions) {
        this.repository = repository;
        this.contact = contact;
        this.users = users;
        this.signals = signals;
        this.clock = clock;
        this.transactions = transactions;
    }

    public Receipt send(long sender, Send input) {
        Send request = validate(sender, input);
        String hash = hash(request);
        return transactions.execute(status -> {
            // Shared with private-note block and withdrawal; always ascending member IDs.
            contact.lockMembers(sender, request.recipientId());
            active(sender);
            active(request.recipientId());
            Instant now = clock.instant();
            var previous = repository.request(sender, request.requestId(), now);
            if (previous.isPresent()) {
                if (!hash.equals(previous.get().hash())) throw error(HttpStatus.CONFLICT, "전송 키의 내용이 다릅니다.");
                if (!previous.get().retained()) throw error(HttpStatus.GONE, "보관 기간이 지난 전송입니다.");
                return previous.get().receipt();
            }
            if (contact.blocked(sender, request.recipientId())) {
                throw error(HttpStatus.FORBIDDEN, "차단된 상대에게 메시지를 보낼 수 없습니다.");
            }
            if (repository.rate(sender, now.minusSeconds(60)) >= 60 ||
                repository.rate(sender, now.atZone(SEOUL).toLocalDate().atStartOfDay(SEOUL).toInstant()) >= 1000) {
                throw error(HttpStatus.TOO_MANY_REQUESTS, "메시지 전송 한도에 도달했습니다. 잠시 후 다시 이용하세요.");
            }
            if (request.contextType() != null &&
                !contact.contextAuthor(request.contextType(), request.contextId(), request.recipientId())) {
                throw error(HttpStatus.BAD_REQUEST, "관련 글의 작성자를 확인해 주세요.");
            }
            UUID room = repository.roomForSend(sender, request.recipientId());
            Receipt receipt = repository.insert(sender, request, room, hash, now,
                    now.atZone(ZoneOffset.UTC).plusYears(1).toInstant());
            afterCommit(() -> {
                signals.publish(new Signal(sender, "SENT", room, receipt.sequence()));
                signals.publish(new Signal(request.recipientId(), "MESSAGE", room, receipt.sequence()));
            });
            return receipt;
        });
    }

    public ExistingRoom existingRoom(long owner, long counterpartId) {
        active(owner);
        if (counterpartId <= 0 || counterpartId == owner) {
            throw error(HttpStatus.BAD_REQUEST, "대화 상대를 확인해 주세요.");
        }
        // Looking up a peer never creates a room or changes its hidden/read state.
        return new ExistingRoom(repository.existingRoom(owner, counterpartId).orElse(null));
    }

    public RoomPage rooms(long owner, UUID cursor) {
        active(owner);
        List<JdbcMemberChatRepository.Summary> found = repository.rooms(owner, cursor, false, clock.instant());
        List<Room> page = summaries(owner, found, true);
        return new RoomPage(page, found.size() > 10 ? page.get(9).roomId() : null);
    }

    public Room room(long owner, UUID room) {
        active(owner);
        var member = membership(owner, room);
        var counterpart = counterpart(member.counterpartId());
        var last = repository.latest(room, clock.instant()).orElse(null);
        return new Room(room, counterpart.active() ? counterpart.userId() : null, counterpart.nickname(),
                member.latestSequence(), member.readThrough(), member.counterpartReadThrough(),
                repository.unreadRoom(owner, room, clock.instant()),
                counterpart.active() && !contact.blocked(owner, counterpart.userId()),
                last == null ? null : last.createdAt(), last == null ? null : last.body());
    }

    public Notifications notifications(long owner, UUID cursor) {
        active(owner);
        List<JdbcMemberChatRepository.Summary> found = repository.rooms(owner, cursor, true, clock.instant());
        // Notifications deliberately omit message previews.
        List<Room> page = summaries(owner, found, false);
        return new Notifications(page, found.size() > 10 ? page.get(9).roomId() : null,
                repository.unreadRooms(owner, clock.instant()));
    }

    private List<Room> summaries(long owner, List<JdbcMemberChatRepository.Summary> found, boolean preview) {
        List<Room> result = new ArrayList<>();
        for (var summary : found.subList(0, Math.min(found.size(), 10))) {
            var member = summary.membership();
            ContactMember counterpart = counterpart(member.counterpartId());
            result.add(new Room(member.roomId(), counterpart.active() ? counterpart.userId() : null,
                    counterpart.nickname(), member.latestSequence(), member.readThrough(),
                    member.counterpartReadThrough(), summary.unread(),
                    counterpart.active() && !contact.blocked(owner, counterpart.userId()),
                    summary.updatedAt(), preview ? summary.preview() : null));
        }
        return List.copyOf(result);
    }

    public MessagePage messages(long owner, UUID room, Long before, Long after) {
        active(owner);
        if ((before != null && before <= 0) || (after != null && after < 0) || (before != null && after != null)) {
            throw error(HttpStatus.BAD_REQUEST, "메시지 조회 범위를 확인해 주세요.");
        }
        var membership = membership(owner, room);
        List<Message> found = repository.messages(room, before, after, clock.instant());
        List<Message> page = new ArrayList<>(found.subList(0, Math.min(found.size(), 50)));
        if (after == null) Collections.reverse(page);
        Long cursor = found.size() > 50 ? (after == null ? page.get(0).sequence() : page.get(49).sequence()) : null;
        return new MessagePage(List.copyOf(page), cursor, membership.counterpartReadThrough());
    }

    public void read(long owner, UUID room, long through) {
        transactions.executeWithoutResult(status -> {
            contact.lockMembers(owner, owner);
            active(owner);
            var member = membership(owner, room);
            if (through < 0 || through > member.latestSequence()) {
                throw error(HttpStatus.BAD_REQUEST, "확인한 메시지 범위를 확인해 주세요.");
            }
            repository.read(owner, room, through);
            afterCommit(() -> {
                signals.publish(new Signal(owner, "READ", room, through));
                if (member.counterpartId() != null) {
                    signals.publish(new Signal(member.counterpartId(), "READ", room, through));
                }
            });
        });
    }

    public void hide(long owner, UUID room) {
        transactions.executeWithoutResult(status -> {
            contact.lockMembers(owner, owner);
            active(owner);
            membership(owner, room);
            repository.hide(owner, room);
            afterCommit(() -> signals.publish(new Signal(owner, "HIDDEN", room, 0)));
        });
    }

    public ContactMember active(long id) {
        if (id <= 0) throw error(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        ContactMember member = lookup(id);
        if (!member.active()) throw error(HttpStatus.FORBIDDEN, "이용할 수 없는 회원입니다.");
        return member;
    }

    ContactMember counterpart(Long id) {
        ContactMember member = id == null ? null : lookup(id);
        return member == null || !member.active()
                ? new ContactMember(null, "탈퇴한 회원", false, false) : member;
    }

    private ContactMember lookup(long id) {
        try {
            return users.get(id);
        } catch (ContactMemberUnavailableException unavailable) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "회원 정보를 확인할 수 없습니다.");
        }
    }

    private JdbcMemberChatRepository.Membership membership(long owner, UUID room) {
        return repository.membership(owner, room).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "대화를 찾을 수 없습니다."));
    }

    @Override
    public void expire() {
        if (repository.installed()) transactions.executeWithoutResult(status -> repository.expire(clock.instant()));
    }

    static Send validate(long sender, Send input) {
        if (input == null || input.recipientId() == null || input.recipientId() <= 0 ||
            input.recipientId() == sender || input.requestId() == null || input.body() == null) {
            throw error(HttpStatus.BAD_REQUEST, "받는 사람과 메시지를 확인해 주세요.");
        }
        String body = input.body().strip();
        if (body.isEmpty() || body.codePointCount(0, body.length()) > 2000 ||
            body.indexOf('\0') >= 0 || body.codePoints().anyMatch(c -> c >= 0xD800 && c <= 0xDFFF)) {
            throw error(HttpStatus.BAD_REQUEST, "메시지는 1~2,000자로 입력해 주세요.");
        }
        if ((input.contextType() == null) != (input.contextId() == null) ||
            (input.contextType() != null && (!List.of("POST", "REPORT").contains(input.contextType()) || input.contextId() <= 0))) {
            throw error(HttpStatus.BAD_REQUEST, "관련 글 정보를 확인해 주세요.");
        }
        return new Send(input.recipientId(), input.requestId(), body, input.contextType(), input.contextId());
    }

    static String hash(Send input) {
        try {
            String canonical = input.recipientId() + "\n" + input.contextType() + "\n" + input.contextId() + "\n" + input.body();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }

    static MemberChatRejectedException error(HttpStatus status, String reason) {
        return new MemberChatRejectedException(status.value(), reason);
    }
}
