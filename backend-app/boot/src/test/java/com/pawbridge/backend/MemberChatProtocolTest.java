package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.pawbridge.backend.boot.configuration.MemberChatSocketConfiguration;
import com.pawbridge.backend.community.internal.chat.application.MemberChat;
import com.pawbridge.backend.community.internal.chat.application.MemberChatRejectedException;
import com.pawbridge.backend.community.internal.chat.infrastructure.MemberChatTickets;
import com.pawbridge.backend.community.internal.chat.web.MemberChatSessions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;

class MemberChatProtocolTest {
    static class Channel extends ChannelRegistration {List<ChannelInterceptor> values(){return getInterceptors();}}
    private final MemberChatTickets tickets=mock(MemberChatTickets.class);
    private final MemberChatSessions sessions=mock(MemberChatSessions.class);
    private final MemberChat chat=mock(MemberChat.class);
    @SuppressWarnings("unchecked") private final ObjectProvider<MemberChatSessions> sessionProvider=mock(ObjectProvider.class);
    @SuppressWarnings("unchecked") private final ObjectProvider<MemberChat> chatProvider=mock(ObjectProvider.class);
    private ChannelInterceptor inbound(){
        when(sessionProvider.getObject()).thenReturn(sessions);when(chatProvider.getObject()).thenReturn(chat);
        var config=new MemberChatSocketConfiguration(tickets,sessionProvider,chatProvider,"https://fixture.invalid");
        var channel=new Channel();config.configureClientInboundChannel(channel);return channel.values().getFirst();
    }
    private static Message<byte[]> frame(StompCommand command,String destination){
        var headers=StompHeaderAccessor.create(command);headers.setSessionId("owned");headers.setDestination(destination);headers.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0],headers.getMessageHeaders());
    }
    @ParameterizedTest @ValueSource(strings={"SEND","SUBSCRIBE"})
    void given__anonymous_pending_socket__when__sending_or_subscribing_before_ticket_authentication__then__drop_and_close_without_business(String command){
        doThrow(new MemberChatRejectedException(401,"expired")).when(sessions).requireCurrent("owned");
        assertThat(inbound().preSend(frame(StompCommand.valueOf(command),command.equals("SEND")?"/app/chat/send":"/user/queue/chat"),null)).isNull();
        verify(sessions).close("owned");verifyNoInteractions(tickets,chat);
    }
    @Test void given__atomic_ticket_identity__when__connecting__then__check_active_and_bind_that_identity_once(){
        var identity=new MemberChatTickets.Identity(7,Long.MAX_VALUE);when(tickets.consume("synthetic")).thenReturn(identity);
        var headers=StompHeaderAccessor.create(StompCommand.CONNECT);headers.setSessionId("owned");headers.addNativeHeader("ticket","synthetic");headers.setLeaveMutable(true);
        var message=MessageBuilder.createMessage(new byte[0],headers.getMessageHeaders());
        assertThat(inbound().preSend(message,null)).isSameAs(message);
        var order=inOrder(tickets,chat,sessions);order.verify(tickets).consume("synthetic");order.verify(chat).active(7);order.verify(sessions).authenticate("owned",identity);
        assertThat(headers.getUser()).isSameAs(identity);
    }
    @Test void given__private_parser_failure__when__producing_protocol_error__then__keep_only_fixed_safe_text(){
        var protocol=new com.pawbridge.backend.community.internal.chat.web.MemberChatProtocolErrors();
        var error=protocol.handleClientMessageProcessingError(MessageBuilder.withPayload("private-body-token".getBytes(java.nio.charset.StandardCharsets.UTF_8)).build(),new IllegalArgumentException("private-body-token"));
        assertThat(new String(error.getPayload(),java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("private-body-token");
        assertThat(error.getHeaders().toString()).doesNotContain("private-body-token");
    }
    @Test void given__authenticated_socket__when__subscribing_to_another_destination__then__drop_and_close(){
        assertThat(inbound().preSend(frame(StompCommand.SUBSCRIBE,"/topic/other"),null)).isNull();verify(sessions).requireCurrent("owned");verify(sessions).close("owned");verifyNoInteractions(chat,tickets);
    }
}
