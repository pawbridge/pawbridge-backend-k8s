package com.pawbridge.backend.community.internal.note.application;

import static com.pawbridge.backend.community.internal.note.application.NoteSendRejectedException.Reason.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

import com.pawbridge.backend.community.internal.note.application.NoteNotifications.Notification;
import com.pawbridge.backend.community.internal.note.application.SendPrivateNote.Receipt;
import com.pawbridge.backend.community.internal.note.application.SendPrivateNote.SendNote;
import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class SendPrivateNoteTest {
    private static final UUID NOTE = UUID.fromString("f2900000-0000-0000-0000-000000000001");
    private static final UUID REQUEST = UUID.fromString("f2900000-0000-0000-0000-000000000002");
    private static final UUID PARENT = UUID.fromString("f2900000-0000-0000-0000-000000000003");
    private static final Instant NOW = Instant.parse("2028-02-29T15:00:00.123456789Z");
    private static final String HASH = "6a4db528d8a9a2accbb88082a032deeb2c652357e5039f3e1975cf0f2361aff2";

    @ParameterizedTest @MethodSource("invalidInputs")
    void given__invalid_id_request_body_or_context__when__sending__then__reject_before_transaction_user_clock_or_uuid(
            long current, SendNote request, NoteSendRejectedException.Reason reason) {
        var f = new Fixture();
        assertEquals(reason, assertThrows(NoteSendRejectedException.class, () -> f.app.send(current, request)).reason());
        assertTrue(f.events.isEmpty()); assertEquals(0, f.generated); verifyNoInteractions(f.members, f.clock);
    }
    static Stream<Arguments> invalidInputs() {
        return Stream.of(
                Arguments.of(0L, null, LOGIN), Arguments.of(-1L, request("hello\n😀"), LOGIN),
                Arguments.of(7L, null, INVALID_REQUEST),
                Arguments.of(7L, new SendNote(null,"body",REQUEST,null,null,null), INVALID_REQUEST),
                Arguments.of(7L, new SendNote(0L,"body",REQUEST,null,null,null), INVALID_REQUEST),
                Arguments.of(7L, new SendNote(7L,"body",REQUEST,null,null,null), INVALID_REQUEST),
                Arguments.of(7L, new SendNote(8L,"body",null,null,null,null), INVALID_REQUEST),
                Arguments.of(7L, new SendNote(8L,null,REQUEST,null,null,null), INVALID_REQUEST),
                Arguments.of(7L, request(" \n\t "), INVALID_BODY),
                Arguments.of(7L, request("😀".repeat(5001)), INVALID_BODY),
                Arguments.of(7L, request("a\0b"), INVALID_BODY),
                Arguments.of(7L, new SendNote(8L,"body",REQUEST,null,null,3L), INVALID_CONTEXT),
                Arguments.of(7L, new SendNote(8L,"body",REQUEST,null,"POST",null), INVALID_CONTEXT),
                Arguments.of(7L, new SendNote(8L,"body",REQUEST,null,"OTHER",3L), INVALID_CONTEXT),
                Arguments.of(7L, new SendNote(8L,"body",REQUEST,null,"REPORT",0L), INVALID_CONTEXT),
                Arguments.of(7L, new SendNote(8L," ",null,null,"OTHER",0L), INVALID_REQUEST));
    }

    @ParameterizedTest @MethodSource("validBodies")
    void given__valid_body_and_active_members__when__sending__then__preserve_raw_text_hash_policy_order_seoul_day_and_utc_year(
            String raw, String stripped, String hash) {
        var f = new Fixture(); f.minuteCount=9; f.dayCount=99;
        assertEquals(new Receipt(NOTE), f.app.send(7L, request(raw)));
        assertEquals(List.of("begin","lock:7:8","member:7","member:8","previous","blocked","clock:1",
                "rate:1","rate:2","uuid","insert","commit"), f.events);
        assertEquals(List.of(NOW.minusSeconds(60),Instant.parse("2028-02-29T15:00:00Z")), f.rateTimes);
        assertEquals(stripped, f.inserted.body()); assertEquals(hash, f.inserted.requestHash());
        assertEquals(NOTE, f.inserted.noteId()); assertEquals(REQUEST, f.inserted.requestId());
        assertEquals(7L, f.inserted.senderId()); assertEquals(8L, f.inserted.recipientId());
        assertNull(f.inserted.replyTo()); assertNull(f.inserted.contextType()); assertNull(f.inserted.contextId());
        assertEquals(NOW, f.inserted.createdAt());
        assertEquals(Instant.parse("2029-02-28T15:00:00.123456789Z"), f.inserted.expiresAt());
        assertEquals(new Notification(NOTE,"PRIVATE_NOTE",7L," raw \n",NOW,false,"/notes/"+NOTE), f.notification);
        assertEquals(1, f.generated); verify(f.clock).instant(); verify(f.members).get(7L); verify(f.members).get(8L);
        verifyNoMoreInteractions(f.members, f.clock); assertFalse(f.active);
    }
    static Stream<Arguments> validBodies() {
        return Stream.of(Arguments.of(" \thello\n😀 \n","hello\n😀",HASH),
                Arguments.of("\t"+"😀".repeat(5000)+"\n","😀".repeat(5000),
                        "9ce4ddef667f7702efb05967b985a0283dca7e3f8812fd9e0c3d3ae80d4254b1"));
    }

    @ParameterizedTest @ValueSource(strings={"hello\n😀"," \thello\n😀\n "})
    void given__the_same_stripped_request_was_already_stored__when__replaying__then__check_members_and_return_original_id_before_block_time_or_new_work(String body) {
        var f = new Fixture(); f.previous = Optional.of(new NoteSendStore.PreviousRequest(PARENT,HASH)); f.blocked = true;
        assertEquals(new Receipt(PARENT), f.app.send(7L, request(body)));
        assertEquals(List.of("begin","lock:7:8","member:7","member:8","previous","commit"), f.events);
        assertEquals(List.of(7L, REQUEST), f.previousArguments); assertNull(f.inserted); assertEquals(0, f.generated);
        verifyNoInteractions(f.clock); verify(f.members).get(7L); verify(f.members).get(8L); verifyNoMoreInteractions(f.members);
    }

    @ParameterizedTest @MethodSource("previousRequests")
    void given__gone_or_conflicting_request__when__sending__then__prefer_gone_before_hash_conflict_and_stop_before_time(
            NoteSendStore.PreviousRequest previous, NoteSendRejectedException.Reason reason) {
        var f = new Fixture(); f.previous = Optional.of(previous);
        assertEquals(reason, assertThrows(NoteSendRejectedException.class, () -> f.app.send(7L, request("hello\n😀"))).reason());
        assertEquals(List.of("begin","lock:7:8","member:7","member:8","previous","rollback"), f.events);
        assertNull(f.inserted); assertEquals(0, f.generated); verifyNoInteractions(f.clock);
    }
    static Stream<Arguments> previousRequests() {
        return Stream.of(Arguments.of(new NoteSendStore.PreviousRequest(null,"different"),GONE),
                Arguments.of(new NoteSendStore.PreviousRequest(NOTE,null),GONE),
                Arguments.of(new NoteSendStore.PreviousRequest(NOTE,"different"),CONFLICT));
    }

    @ParameterizedTest @ValueSource(longs={7L,8L})
    void given__current_or_recipient_is_inactive__when__sending__then__reject_in_user_order_before_looking_up_a_previous_request(long inactive) {
        var f = new Fixture(); f.inactive.add(inactive); f.previous = Optional.of(new NoteSendStore.PreviousRequest(null,null));
        assertEquals(INACTIVE, assertThrows(NoteSendRejectedException.class, () -> f.app.send(7L, request("body"))).reason());
        var expected = new ArrayList<>(List.of("begin","lock:7:8","member:7")); if(inactive==8L)expected.add("member:8");expected.add("rollback");
        assertEquals(expected, f.events); assertEquals(0, f.generated); verifyNoInteractions(f.clock);
    }

    @Test
    void given__recipient_user_lookup_is_unavailable__when__sending__then__propagate_the_same_typed_failure_before_request_or_clock() {
        var f = new Fixture(); var failure = new ContactMemberUnavailableException(new IllegalStateException("private-user-detail"));
        f.unavailable = failure;
        assertSame(failure, assertThrows(ContactMemberUnavailableException.class, () -> f.app.send(7L, request("body"))));
        assertEquals(List.of("begin","lock:7:8","member:7","member:8","rollback"), f.events);
        assertEquals(0, f.generated); verifyNoInteractions(f.clock);
    }

    @Test
    void given__new_request_is_blocked__when__sending__then__stop_before_sampling_time_or_querying_limits() {
        var f = new Fixture(); f.blocked=true;
        assertEquals(BLOCKED, assertThrows(NoteSendRejectedException.class, () -> f.app.send(7L,request("body"))).reason());
        assertEquals(List.of("begin","lock:7:8","member:7","member:8","previous","blocked","rollback"),f.events);
        assertEquals(0,f.generated); verifyNoInteractions(f.clock);
    }

    @ParameterizedTest @ValueSource(strings={"minute","day"})
    void given__minute_or_day_limit_is_reached__when__sending__then__short_circuit_inclusive_queries_before_reply_context_or_uuid(String limit) {
        var f=new Fixture();f.minuteCount=limit.equals("minute")?10:9;f.dayCount=100;
        var request=new SendNote(8L,"body",REQUEST,PARENT,"POST",3L);
        assertEquals(RATE_LIMIT,assertThrows(NoteSendRejectedException.class,()->f.app.send(7L,request)).reason());
        assertEquals(limit.equals("minute")?List.of(NOW.minusSeconds(60)):List.of(NOW.minusSeconds(60),Instant.parse("2028-02-29T15:00:00Z")),f.rateTimes);
        assertFalse(f.events.contains("reply"));assertFalse(f.events.contains("context:POST"));assertNull(f.inserted);assertEquals(0,f.generated);
        verify(f.clock).instant();verifyNoMoreInteractions(f.clock);
    }

    @ParameterizedTest @MethodSource("rejectedReplies")
    void given__missing_or_mismatching_reply__when__sending__then__sample_reply_time_and_reject_before_context_or_uuid(
            Optional<NoteSendStore.Reply> parent,NoteSendRejectedException.Reason reason) {
        var f=new Fixture();f.parent=parent;
        assertEquals(reason,assertThrows(NoteSendRejectedException.class,()->f.app.send(7L,new SendNote(8L,"body",REQUEST,PARENT,"POST",3L))).reason());
        assertEquals(List.of(7L,PARENT,NOW.plusSeconds(9)),f.replyArguments);assertFalse(f.events.contains("context:POST"));
        assertEquals(0,f.generated);assertNull(f.inserted);verify(f.clock,times(2)).instant();verifyNoMoreInteractions(f.clock);
    }
    static Stream<Arguments> rejectedReplies(){return Stream.of(
            Arguments.of(Optional.empty(),NOT_FOUND),Arguments.of(Optional.of(new NoteSendStore.Reply("SENT",8L)),REPLY_MISMATCH),
            Arguments.of(Optional.of(new NoteSendStore.Reply("INBOX",null)),REPLY_MISMATCH),
            Arguments.of(Optional.of(new NoteSendStore.Reply("INBOX",9L)),REPLY_MISMATCH));}

    @ParameterizedTest @ValueSource(strings={"POST","REPORT"})
    void given__context_does_not_belong_to_recipient__when__sending__then__reject_after_rate_before_uuid_or_insert(String context) {
        var f=new Fixture();f.contextAllowed=false;
        assertEquals(CONTEXT_AUTHOR,assertThrows(NoteSendRejectedException.class,()->f.app.send(7L,new SendNote(8L,"body",REQUEST,null,context,3L))).reason());
        assertEquals(List.of(context,3L,8L),f.contextArguments);assertEquals(0,f.generated);assertNull(f.inserted);
        verify(f.clock).instant();verifyNoMoreInteractions(f.clock);
    }

    @ParameterizedTest @ValueSource(strings={"POST","REPORT"})
    void given__valid_reply_and_context__when__sending__then__check_reply_before_context_and_keep_the_first_instant_for_all_new_rows(String context) {
        var f=new Fixture();assertEquals(new Receipt(NOTE),f.app.send(7L,new SendNote(8L,"body",REQUEST,PARENT,context,3L)));
        assertEquals(List.of("begin","lock:7:8","member:7","member:8","previous","blocked","clock:1","rate:1","rate:2","clock:2","reply","context:"+context,"uuid","insert","commit"),f.events);
        assertEquals(List.of(7L,PARENT,NOW.plusSeconds(9)),f.replyArguments);assertEquals(List.of(context,3L,8L),f.contextArguments);
        assertEquals(NOW,f.inserted.createdAt());assertEquals(NOW,f.notification.createdAt());assertEquals(PARENT,f.inserted.replyTo());
        assertEquals(context,f.inserted.contextType());assertEquals(3L,f.inserted.contextId());assertEquals(1,f.generated);
        assertEquals(context.equals("POST")?"1e732b23d6b7844b5d49c78cc32edef6b4bb5da067eafa9ac804c50407a788f0"
                :"ab8cb3f87a4d3677737a8722956b21ff559831d2ad069013299c36da06200a49",f.inserted.requestHash());
        verify(f.clock,times(2)).instant();verifyNoMoreInteractions(f.clock);
    }

    private static SendNote request(String body){return new SendNote(8L,body,REQUEST,null,null,null);}
    /** Policy callback fake, not database or transaction manager evidence. */
    private static final class Fixture {
        final List<String> events=new ArrayList<>();final ContactMemberQuery members=mock(ContactMemberQuery.class);final Clock clock=mock(Clock.class);
        final Set<Long>inactive=new HashSet<>();final List<Instant>rateTimes=new ArrayList<>();
        Optional<NoteSendStore.PreviousRequest>previous=Optional.empty();Optional<NoteSendStore.Reply>parent=Optional.of(new NoteSendStore.Reply("INBOX",8L));
        List<Object>previousArguments,replyArguments,contextArguments;boolean active,blocked,contextAllowed=true;long minuteCount,dayCount;int generated,samples;
        NoteSendStore.NewNote inserted;Notification notification;ContactMemberUnavailableException unavailable;
        final SendPrivateNote app;
        Fixture(){
            when(members.get(anyLong())).thenAnswer(call->{assertTrue(active);long id=call.getArgument(0);events.add("member:"+id);if(id==8L&&unavailable!=null)throw unavailable;return new ContactMember(id,id==7L?" raw \n":"target",!inactive.contains(id),inactive.contains(id));});
            when(clock.instant()).thenAnswer(call->{assertTrue(active);events.add("clock:"+(++samples));return samples==1?NOW:NOW.plusSeconds(9);});
            NoteSendStore store=new NoteSendStore(){
                @Override public Receipt inTransaction(java.util.function.Supplier<Receipt>action){assertFalse(active);active=true;events.add("begin");try{Receipt result=action.get();events.add("commit");return result;}catch(RuntimeException failure){events.add("rollback");throw failure;}finally{active=false;}}
                @Override public void lockMembers(long first,long second){assertTrue(active);events.add("lock:"+first+":"+second);}
                @Override public Optional<PreviousRequest>request(long sender,UUID key){assertTrue(active);events.add("previous");previousArguments=List.of(sender,key);return previous;}
                @Override public boolean blocked(long first,long second){assertTrue(active);assertEquals(7L,first);assertEquals(8L,second);events.add("blocked");return blocked;}
                @Override public long rate(long sender,Instant since){assertTrue(active);assertEquals(7L,sender);rateTimes.add(since);events.add("rate:"+rateTimes.size());return rateTimes.size()==1?minuteCount:dayCount;}
                @Override public Optional<Reply>reply(long owner,UUID id,Instant now){assertTrue(active);events.add("reply");replyArguments=List.of(owner,id,now);return parent;}
                @Override public boolean contextAuthor(String type,long id,long recipient){assertTrue(active);events.add("context:"+type);contextArguments=List.of(type,id,recipient);return contextAllowed;}
                @Override public void insert(NewNote note,Notification hint){assertTrue(active);events.add("insert");inserted=note;notification=hint;}
            };
            app=new SendPrivateNote(members,store,clock,()->{assertTrue(active);events.add("uuid");generated++;return NOTE;});
        }
    }
}
