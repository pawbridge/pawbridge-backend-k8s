package com.pawbridge.backend.community.internal.chat.infrastructure;

import static com.pawbridge.backend.community.internal.chat.application.MemberChatModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.pawbridge.backend.community.internal.chat.application.MemberChatHints;
import com.pawbridge.backend.community.internal.chat.application.MemberChatRejectedException;
import com.pawbridge.backend.community.internal.note.application.NoteSendStore;
import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcMemberChatTest {
    private static final Instant NOW=Instant.parse("2030-01-01T00:00:00Z");
    private static final UUID REQUEST=UUID.fromString("9c000000-0000-0000-0000-000000000001");
    private static final UUID ROOM=UUID.fromString("9c000000-0000-0000-0000-000000000002");
    private static final Send SEND=new Send(8L,REQUEST,"hello",null,null);

    @ParameterizedTest @ValueSource(ints={0,1,2,3,4,5})
    void given__invalid_recipient_body_or_context__when__sending__then__reject_before_transaction_or_member_lookup(int example) {
        var f=new Fixture();
        Send input=switch(example) {
            case 0->null;
            case 1->new Send(null,REQUEST,"hello",null,null);
            case 2->new Send(7L,REQUEST,"hello",null,null);
            case 3->new Send(8L,REQUEST," \n ",null,null);
            case 4->new Send(8L,REQUEST,"hello","POST",null);
            default->new Send(8L,REQUEST,"\uD800",null,null);
        };
        assertThatThrownBy(()->f.chat.send(7,input)).isInstanceOfSatisfying(MemberChatRejectedException.class,e->assertThat(e.status()).isEqualTo(400));
        assertThat(f.events).isEmpty();verifyNoInteractions(f.repository,f.contact,f.users,f.hints);
    }

    @ParameterizedTest @ValueSource(strings={"POST","REPORT"})
    void given__active_members_and_owned_context__when__sending__then__preserve_order_cutoff_and_publish_only_after_commit(String context) {
        var f=new Fixture();var input=new Send(8L,REQUEST," hello ",context,19L);
        var normalized=new Send(8L,REQUEST,"hello",context,19L);
        when(f.contact.contextAuthor(context,19L,8L)).thenReturn(true);
        assertThat(f.chat.send(7,input)).isEqualTo(f.receipt);
        var order=inOrder(f.contact,f.users,f.repository,f.hints);
        order.verify(f.contact).lockMembers(7,8);order.verify(f.users).get(7);order.verify(f.users).get(8);
        order.verify(f.repository).request(7,REQUEST,NOW);order.verify(f.contact).blocked(7,8);
        order.verify(f.repository).rate(7,NOW.minusSeconds(60));
        order.verify(f.repository).rate(7,Instant.parse("2029-12-31T15:00:00Z"));
        order.verify(f.contact).contextAuthor(context,19L,8L);order.verify(f.repository).roomForSend(7,8);
        order.verify(f.repository).insert(7,normalized,ROOM,JdbcMemberChat.hash(normalized),NOW,Instant.parse("2031-01-01T00:00:00Z"));
        order.verify(f.hints).publish(new Signal(7,"SENT",ROOM,3));order.verify(f.hints).publish(new Signal(8,"MESSAGE",ROOM,3));
        assertThat(f.events).containsExactly("begin","commit","hint:SENT","hint:MESSAGE","cleanup");
    }

    @Test
    void given__same_request_hash_and_retained_message__when__replaying__then__return_original_receipt_without_insert_or_new_hint() {
        var f=new Fixture();when(f.repository.request(7,REQUEST,NOW)).thenReturn(Optional.of(new JdbcMemberChatRepository.SavedRequest(JdbcMemberChat.hash(SEND),f.receipt,true)));
        assertThat(f.chat.send(7,SEND)).isEqualTo(f.receipt);
        verify(f.contact).lockMembers(7,8);verify(f.users).get(7);verify(f.users).get(8);
        verify(f.repository).request(7,REQUEST,NOW);verifyNoMoreInteractions(f.repository);
        verify(f.contact,never()).blocked(anyLong(),anyLong());verifyNoInteractions(f.hints);
        assertThat(f.events).containsExactly("begin","commit","cleanup");
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void given__request_hash_conflict_or_gone_message__when__replaying__then__preserve_conflict_before_retention_check(boolean conflict) {
        var f=new Fixture();when(f.repository.request(7,REQUEST,NOW)).thenReturn(Optional.of(new JdbcMemberChatRepository.SavedRequest(conflict?"different":JdbcMemberChat.hash(SEND),f.receipt,false)));
        assertThatThrownBy(()->f.chat.send(7,SEND)).isInstanceOfSatisfying(MemberChatRejectedException.class,e->assertThat(e.status()).isEqualTo(conflict?409:410));
        verify(f.repository,never()).roomForSend(anyLong(),anyLong());verifyNoInteractions(f.hints);
        assertThat(f.events).containsExactly("begin","rollback","cleanup");
    }

    @ParameterizedTest @ValueSource(strings={"block","minute","day","context"})
    void given__blocked_rate_limited_or_unowned_context__when__sending__then__roll_back_without_insert_or_hint(String failure) {
        var f=new Fixture();Send input=SEND;
        switch(failure) {
            case "block"->when(f.contact.blocked(7,8)).thenReturn(true);
            case "minute"->when(f.repository.rate(7,NOW.minusSeconds(60))).thenReturn(60L);
            case "day"->when(f.repository.rate(7,Instant.parse("2029-12-31T15:00:00Z"))).thenReturn(1000L);
            default->input=new Send(8L,REQUEST,"hello","POST",19L);
        }
        Send request=input;int expected=failure.equals("block")?403:failure.equals("context")?400:429;
        assertThatThrownBy(()->f.chat.send(7,request)).isInstanceOfSatisfying(MemberChatRejectedException.class,e->assertThat(e.status()).isEqualTo(expected));
        verify(f.repository,never()).roomForSend(anyLong(),anyLong());verifyNoInteractions(f.hints);
        assertThat(f.events).containsExactly("begin","rollback","cleanup");
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void given__insert_or_commit_failure__when__sending__then__propagate_without_publishing_or_retry(boolean commit) {
        var f=new Fixture();RuntimeException failure=commit?new TransactionSystemException("synthetic commit"):new DataAccessResourceFailureException("synthetic insert");
        if(commit) f.manager.commitFailure=failure;
        else when(f.repository.insert(anyLong(),any(),any(),anyString(),any(),any())).thenThrow(failure);
        assertThatThrownBy(()->f.chat.send(7,SEND)).isSameAs(failure);
        verify(f.repository).insert(anyLong(),any(),any(),anyString(),any(),any());verifyNoInteractions(f.hints);
        assertThat(f.events).containsExactly("begin",commit?"commit":"rollback","cleanup");
    }

    @ParameterizedTest @ValueSource(strings={"login","inactive","unavailable"})
    void given__unusable_current_member__when__checking_active__then__preserve_login_inactive_and_typed_lookup_failures(String kind) {
        var f=new Fixture();long id=kind.equals("login")?0:7;
        if(kind.equals("inactive"))when(f.users.get(7)).thenReturn(new ContactMember(7L,"gone",false,true));
        if(kind.equals("unavailable"))when(f.users.get(7)).thenThrow(new ContactMemberUnavailableException(new IllegalStateException("private synthetic")));
        int expected=kind.equals("login")?401:kind.equals("inactive")?403:503;
        assertThatThrownBy(()->f.chat.active(id)).isInstanceOfSatisfying(MemberChatRejectedException.class,e->assertThat(e.status()).isEqualTo(expected));
        if(id==0)verifyNoInteractions(f.users);assertThat(f.events).isEmpty();verifyNoInteractions(f.repository,f.contact,f.hints);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void given__owned_membership__when__reading_or_hiding__then__write_only_owner_and_signal_after_commit(boolean hide) {
        var f=new Fixture();when(f.repository.membership(7,ROOM)).thenReturn(Optional.of(new JdbcMemberChatRepository.Membership(ROOM,8L,5,1,2,0)));
        if(hide)f.chat.hide(7,ROOM);else f.chat.read(7,ROOM,4);
        var order=inOrder(f.contact,f.users,f.repository,f.hints);order.verify(f.contact).lockMembers(7,7);order.verify(f.users).get(7);order.verify(f.repository).membership(7,ROOM);
        if(hide){order.verify(f.repository).hide(7,ROOM);order.verify(f.hints).publish(new Signal(7,"HIDDEN",ROOM,0));}
        else{order.verify(f.repository).read(7,ROOM,4);order.verify(f.hints).publish(new Signal(7,"READ",ROOM,4));order.verify(f.hints).publish(new Signal(8,"READ",ROOM,4));}
        assertThat(f.events.get(1)).isEqualTo("commit");assertThat(f.events.get(2)).startsWith("hint:");
    }

    @ParameterizedTest @ValueSource(longs={-1,6,99})
    void given__invalid_read_sequence_or_missing_membership__when__reading__then__roll_back_without_cross_member_write(long sequence) {
        var f=new Fixture();if(sequence!=99)when(f.repository.membership(7,ROOM)).thenReturn(Optional.of(new JdbcMemberChatRepository.Membership(ROOM,8L,5,1,2,0)));
        assertThatThrownBy(()->f.chat.read(7,ROOM,sequence)).isInstanceOfSatisfying(MemberChatRejectedException.class,e->assertThat(e.status()).isEqualTo(sequence==99?404:400));
        verify(f.repository,never()).read(anyLong(),any(),anyLong());verifyNoInteractions(f.hints);assertThat(f.events).containsExactly("begin","rollback","cleanup");
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void given__message_page_lookahead__when__reading_before_or_after__then__display_fifty_in_chronological_order_with_correct_cursor(boolean after) {
        var f=new Fixture();when(f.repository.membership(7,ROOM)).thenReturn(Optional.of(new JdbcMemberChatRepository.Membership(ROOM,8L,90,1,2,0)));
        List<Message> messages=LongStream.rangeClosed(1,51).map(i->after?i:52-i).mapToObj(i->new Message(ROOM,i,8L,"body",null,NOW)).toList();
        when(f.repository.messages(ROOM,after?null:90L,after?0L:null,NOW)).thenReturn(messages);
        MessagePage page=f.chat.messages(7,ROOM,after?null:90L,after?0L:null);
        assertThat(page.content()).hasSize(50);assertThat(page.content().getFirst().sequence()).isEqualTo(after?1:2);
        assertThat(page.content().getLast().sequence()).isEqualTo(after?50:51);assertThat(page.nextCursor()).isEqualTo(after?50L:2L);assertThat(page.counterpartReadThrough()).isEqualTo(2);
        assertThat(f.events).isEmpty();verifyNoInteractions(f.hints);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void given__installed_or_absent_chat_tables__when__retaining__then__expire_in_one_transaction_only_when_installed(boolean installed) {
        var f=new Fixture();when(f.repository.installed()).thenReturn(installed);f.chat.expire();
        if(installed){verify(f.repository).expire(NOW);assertThat(f.events).containsExactly("begin","commit","cleanup");}
        else{verify(f.repository,never()).expire(any());assertThat(f.events).isEmpty();}
        verifyNoInteractions(f.contact,f.users,f.hints);
    }

    private static final class Fixture {
        final List<String> events=new ArrayList<>();
        final JdbcMemberChatRepository repository=mock(JdbcMemberChatRepository.class);
        final NoteSendStore contact=mock(NoteSendStore.class);final ContactMemberQuery users=mock(ContactMemberQuery.class);
        final MemberChatHints hints=mock(MemberChatHints.class);final Manager manager=new Manager(events);
        final Receipt receipt=new Receipt(REQUEST,ROOM,3);final JdbcMemberChat chat;
        Fixture(){
            when(users.get(7)).thenReturn(new ContactMember(7L,"caller",true,false));when(users.get(8)).thenReturn(new ContactMember(8L,"peer",true,false));
            when(repository.request(7,REQUEST,NOW)).thenReturn(Optional.empty());when(repository.roomForSend(7,8)).thenReturn(ROOM);
            when(repository.insert(anyLong(),any(),any(),anyString(),any(),any())).thenReturn(receipt);
            doAnswer(call->{assertThat(events).contains("commit");events.add("hint:"+((Signal)call.getArgument(0)).kind());return null;}).when(hints).publish(any());
            chat=new JdbcMemberChat(repository,contact,users,hints,Clock.fixed(NOW,ZoneOffset.UTC),new TransactionTemplate(manager));
        }
    }
    private static final class Manager extends AbstractPlatformTransactionManager {
        final List<String> events;RuntimeException commitFailure;Manager(List<String> events){this.events=events;}
        protected Object doGetTransaction(){return new Object();}
        protected void doBegin(Object transaction,TransactionDefinition definition){events.add("begin");}
        protected void doCommit(DefaultTransactionStatus status){events.add("commit");if(commitFailure!=null)throw commitFailure;}
        protected void doRollback(DefaultTransactionStatus status){events.add("rollback");}
        protected void doCleanupAfterCompletion(Object transaction){events.add("cleanup");}
    }
}
