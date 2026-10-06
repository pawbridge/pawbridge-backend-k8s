package com.pawbridge.communityservice.contact;

import static com.pawbridge.communityservice.contact.PrivateNoteModels.*;
import com.pawbridge.communityservice.client.UserServiceClient;
import feign.FeignException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
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
    private final PrivateNoteRepository notes;
    private final UserServiceClient users;
    private final PrivateNoteStream streams;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public PrivateNoteService(PrivateNoteRepository notes, UserServiceClient users,
                              PrivateNoteStream streams, Clock clock, PlatformTransactionManager manager) {
        this.notes=notes; this.users=users; this.streams=streams; this.clock=clock;
        this.transactions=new TransactionTemplate(manager);
        this.transactions.setTimeout(15);
    }

    public Receipt send(long owner, SendNote input) {
        requireId(owner);
        String body=validate(input,owner);
        String hash=hash(input,body);
        return transactions.execute(status -> {
            notes.lockMembers(owner,input.recipientId());
            ContactMember sender=active(owner);
            active(input.recipientId());
            var previous=notes.request(owner,input.requestId());
            if(previous.isPresent()) {
                var request=previous.get();
                if(request.noteId()==null || request.hash()==null) throw error(HttpStatus.GONE,"이 전송은 이미 처리되었으며 더 이상 보관되어 있지 않습니다.");
                if(!hash.equals(request.hash())) throw error(HttpStatus.CONFLICT,"같은 전송 키로 다른 내용을 보낼 수 없습니다.");
                return new Receipt(request.noteId());
            }
            if(notes.blocked(owner,input.recipientId())) throw error(HttpStatus.FORBIDDEN,"차단된 상대에게 쪽지를 보낼 수 없습니다.");
            Instant now=clock.instant();
            if(notes.rate(owner,now.minusSeconds(60))>=10 || notes.rate(owner,now.atZone(SEOUL).toLocalDate().atStartOfDay(SEOUL).toInstant())>=100)
                throw error(HttpStatus.TOO_MANY_REQUESTS,"쪽지 발송 한도에 도달했습니다. 잠시 후 다시 이용해 주세요.");
            if(input.replyTo()!=null) {
                Note parent=find(owner,input.replyTo());
                if(!"INBOX".equals(parent.direction()) || !java.util.Objects.equals(parent.senderId(),input.recipientId()))
                    throw error(HttpStatus.BAD_REQUEST,"답장할 쪽지와 받는 사람이 일치하지 않습니다.");
            }
            if(input.contextType()!=null && !notes.contextAuthor(input.contextType(),input.contextId(),input.recipientId()))
                throw error(HttpStatus.BAD_REQUEST,"연락할 글의 작성자를 확인해 주세요.");
            UUID id=UUID.randomUUID();
            notes.insert(owner,input,body,id,hash,now,now.atZone(ZoneId.of("UTC")).plusYears(1).toInstant());
            Notification event=new Notification(id,"PRIVATE_NOTE",sender.userId(),sender.nickname(),now,false,"/notes/"+id);
            afterCommit(() -> streams.publish(input.recipientId(),event));
            return new Receipt(id);
        });
    }

    public NotePage list(long owner, String box, boolean favorites, int page) {
        requirePage(page);
        if(!List.of("INBOX","SENT").contains(box)) throw error(HttpStatus.BAD_REQUEST,"쪽지함을 확인해 주세요.");
        active(owner);
        Instant now=clock.instant();
        long count=notes.count(owner,box,favorites,now);
        return new NotePage(notes.list(owner,box,favorites,page,now).stream().map(this::view).toList(),count,(int)((count+9)/10),page,10);
    }
    public NoteView get(long owner,UUID id) { active(owner); return view(find(owner,id)); }
    public Notifications notifications(long owner,UUID cursor) {
        active(owner);
        Instant now=clock.instant();
        List<Note> found=notes.notifications(owner,cursor,now);
        List<Notification> content=found.stream().limit(20).map(this::notification).toList();
        return new Notifications(content,found.size()>20?content.get(content.size()-1).noteId():null,notes.unread(owner,now));
    }
    public void read(long owner,UUID id) { mutate(owner,id,() -> notes.read(owner,id,clock.instant())); }
    public void favorite(long owner,UUID id,boolean favorite) { mutate(owner,id,() -> notes.favorite(owner,id,favorite)); }
    public void delete(long owner,UUID id) { mutate(owner,id,() -> notes.delete(owner,id)); }
    private void mutate(long owner,UUID id,Runnable change) {
        transactions.executeWithoutResult(status -> {
            notes.lockMembers(owner,owner); active(owner); find(owner,id); notes.lockNote(id);
            // Recheck after the note lock: another owner's deletion may have removed the last row.
            find(owner,id); change.run(); afterCommit(() -> streams.resync(owner));
        });
    }
    public void block(long owner,long target) {
        requireId(owner); requireId(target);
        if(owner==target) throw error(HttpStatus.BAD_REQUEST,"자신을 차단할 수 없습니다.");
        transactions.executeWithoutResult(status -> {
            notes.lockMembers(owner,target); active(owner); active(target);
            notes.block(owner,target,clock.instant());
        });
    }
    public void unblock(long owner,long target) {
        requireId(owner); requireId(target);
        transactions.executeWithoutResult(status -> {
            notes.lockMembers(owner,target); active(owner); notes.unblock(owner,target);
        });
    }
    public BlockPage blocks(long owner,int page) {
        requirePage(page);active(owner);
        long count=notes.blockCount(owner);
        return new BlockPage(notes.blocks(owner,page).stream().map(b -> new BlockView(b.id(),display(b.id()).nickname(),b.createdAt())).toList(),count,(int)((count+9)/10),page);
    }
    public ContactMember recipient(long owner,long target) {
        active(owner);
        if(owner==target || notes.blocked(owner,target)) throw error(HttpStatus.FORBIDDEN,"이 상대에게 쪽지를 보낼 수 없습니다.");
        return active(target);
    }

    /** Not routed by Gateway. User's durable deletion state is independently verified. */
    public void withdraw(long member) {
        requireId(member);
        transactions.executeWithoutResult(status -> {
            notes.lockMembers(member,member);
            ContactMember current=lookup(member);
            if(current.active() || !current.deletionPending()) throw error(HttpStatus.CONFLICT,"회원 삭제 준비 상태가 아닙니다.");
            notes.withdraw(member);
            afterCommit(() -> { streams.close(member); streams.resyncAll(); });
        });
    }
    public void expire() { transactions.executeWithoutResult(status -> notes.expire(clock.instant())); }
    public void requireActive(long owner) { active(owner); }
    private Note find(long owner,UUID id) {
        return notes.find(owner,id,clock.instant()).orElseThrow(() -> error(HttpStatus.NOT_FOUND,"쪽지를 찾을 수 없습니다."));
    }
    private NoteView view(Note note) {
        Long other="INBOX".equals(note.direction())?note.senderId():note.recipientId();
        ContactMember member=display(other);
        String context=note.contextType()==null?null:("POST".equals(note.contextType())?"/community/":"/reports/")+note.contextId();
        return new NoteView(note.noteId(),note.body(),note.direction(),note.createdAt(),note.readAt(),note.favorite(),member.active()?member.userId():null,
                member.nickname(),"INBOX".equals(note.direction()) && member.active() && !notes.blocked(note.recipientId(),member.userId()),context);
    }
    private Notification notification(Note note) {
        ContactMember sender=display(note.senderId());
        return new Notification(note.noteId(),"PRIVATE_NOTE",sender.active()?sender.userId():null,sender.nickname(),note.createdAt(),note.readAt()!=null,"/notes/"+note.noteId());
    }
    private ContactMember display(Long id) {
        if(id==null) return new ContactMember(null,"탈퇴한 회원",false,false);
        ContactMember member=lookup(id);
        return member.active()?member:new ContactMember(null,"탈퇴한 회원",false,false);
    }
    private ContactMember active(long id) {
        requireId(id);
        ContactMember member=lookup(id);
        if(!member.active()) throw error(HttpStatus.FORBIDDEN,"이용할 수 없는 회원입니다.");
        return member;
    }
    private ContactMember lookup(long id) {
        try {
            ContactMember member=users.getContactMember(id);
            if(member==null || !Long.valueOf(id).equals(member.userId())) throw error(HttpStatus.SERVICE_UNAVAILABLE,"회원 정보를 확인할 수 없습니다.");
            return member;
        } catch(FeignException failure) {
            if(failure.status()==404) return new ContactMember(id,"탈퇴한 회원",false,false);
            throw error(HttpStatus.SERVICE_UNAVAILABLE,"회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.");
        }
    }
    private static String validate(SendNote input,long owner) {
        if(input==null || input.recipientId()==null || input.recipientId()<=0 || input.recipientId()==owner || input.requestId()==null || input.body()==null)
            throw error(HttpStatus.BAD_REQUEST,"받는 사람과 쪽지 내용을 확인해 주세요.");
        String body=input.body().strip();
        if(body.isBlank() || body.codePointCount(0,body.length())>5000 || body.indexOf('\0')>=0)
            throw error(HttpStatus.BAD_REQUEST,"쪽지는 1~5,000자로 작성해 주세요.");
        if((input.contextType()==null)!=(input.contextId()==null) || (input.contextType()!=null && (!List.of("POST","REPORT").contains(input.contextType()) || input.contextId()<=0)))
            throw error(HttpStatus.BAD_REQUEST,"연락할 글을 확인해 주세요.");
        return body;
    }
    private static String hash(SendNote note,String body) {
        try {
            String payload=note.recipientId()+"\n"+note.replyTo()+"\n"+note.contextType()+"\n"+note.contextId()+"\n"+body;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { action.run(); }
                catch (RuntimeException failure) {
                    // The note is already committed. UI delivery cannot turn success into failure.
                    org.slf4j.LoggerFactory.getLogger(PrivateNoteService.class)
                            .warn("Private note push failed ({}); REST restoration remains available", failure.getClass().getSimpleName());
                }
            }
        });
    }
    private static void requirePage(int page) { if(page<0 || page>10000) throw error(HttpStatus.BAD_REQUEST,"페이지 범위를 확인해 주세요."); }
    private static void requireId(long id) { if(id<=0) throw error(HttpStatus.UNAUTHORIZED,"로그인이 필요합니다."); }
    private static ResponseStatusException error(HttpStatus status,String message) { return new ResponseStatusException(status,message); }
}
