package com.pawbridge.communityservice.chat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
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

class MemberChatSocketSecurityTest {
    MemberChatTickets tickets = mock(MemberChatTickets.class);
    MemberChatSessions sessions = mock(MemberChatSessions.class);
    MemberChatService service = mock(MemberChatService.class);
    ChannelInterceptor interceptor;
    HashMap<String,Object> attributes;

    @BeforeEach void prepare() {
        ObjectProvider<MemberChatSessions> sessionProvider = mock(ObjectProvider.class);
        ObjectProvider<MemberChatService> serviceProvider = mock(ObjectProvider.class);
        when(sessionProvider.getObject()).thenReturn(sessions); when(serviceProvider.getObject()).thenReturn(service);
        var config = new MemberChatSocketConfiguration(tickets, sessionProvider, serviceProvider, "https://example.test");
        var channel = new InboundRegistration(); config.configureClientInboundChannel(channel);
        interceptor = channel.interceptors().get(0); attributes = new HashMap<>();
    }
    Message<byte[]> frame(StompCommand command, String destination, String subscription) {
        var headers = StompHeaderAccessor.create(command);
        headers.setSessionId("synthetic-session"); headers.setSessionAttributes(attributes);
        if (destination != null) headers.setDestination(destination);
        if (subscription != null) headers.setSubscriptionId(subscription);
        headers.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }

    @Test void givenConnectWithForgedMember_whenTicketValid_thenUseOnlyConsumedIdentity() {
        var message = frame(StompCommand.CONNECT, null, null);
        var headers = StompHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        headers.setNativeHeader("ticket", "opaque-ticket"); headers.setNativeHeader("memberId", "999");
        var identity = new MemberChatTickets.Identity(7, Long.MAX_VALUE);
        when(tickets.consume("opaque-ticket")).thenReturn(identity);
        interceptor.preSend(message, null);
        assertThat(headers.getUser()).isEqualTo(identity);
        verify(service).active(7); verify(sessions).authenticate("synthetic-session", identity);
    }

    @ParameterizedTest @ValueSource(strings={"/queue/chat", "/user/8/queue/chat", "/topic/chat", "/user/queue/other"})
    void givenForeignOrBroadcastSubscription_whenSubscribe_thenReject(String destination) {
        assertThat(interceptor.preSend(frame(StompCommand.SUBSCRIBE, destination, "1"), null)).isNull();
        verify(sessions).close("synthetic-session");
    }

    @Test void givenOwnQueue_whenSubscribeAgain_thenCloseAndDropWithoutThrowingPrivateFrame() {
        interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/user/queue/chat", "1"), null);
        assertThat(interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/user/queue/chat", "2"), null)).isNull();
        verify(sessions).close("synthetic-session");
    }

    @Test void givenOwnSubscription_whenExplicitUnsubscribe_thenAllowAnotherOwnSubscription() {
        interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/user/queue/chat", "1"), null);
        interceptor.preSend(frame(StompCommand.UNSUBSCRIBE, null, "1"), null);
        assertThat(interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/user/queue/chat", "2"), null)).isNotNull();
    }

    @Test void givenMalformedSubscription_whenNoId_thenRejectWithoutAllocatingSubscription() {
        assertThat(interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/user/queue/chat", null), null)).isNull();
        assertThat(attributes).isEmpty();
    }

    @Test void givenWrongSendDestination_whenSend_thenRejectEvenAuthenticatedConnection() {
        assertThat(interceptor.preSend(frame(StompCommand.SEND, "/queue/chat", null), null)).isNull();
        verify(sessions).requireCurrent("synthetic-session");
        verify(sessions).close("synthetic-session");
    }

    @Test void givenFrameOrParserFailureContainingSecrets_whenProtocolError_thenFixedSafeResponse() {
        var error = new MemberChatProtocolErrors();
        var request = MessageBuilder.withPayload("private-body-token".getBytes(StandardCharsets.UTF_8)).build();
        var response = error.handleClientMessageProcessingError(request, new IllegalArgumentException("private-body-token"));
        var headers = StompHeaderAccessor.getAccessor(response, StompHeaderAccessor.class);
        assertThat(headers).isNotNull();
        assertThat(headers.isMutable()).isTrue();
        assertThat(headers.getCommand()).isEqualTo(StompCommand.ERROR);
        assertThat(new String(response.getPayload(), StandardCharsets.UTF_8)).doesNotContain("private-body-token");
        assertThat(response.getHeaders().toString()).doesNotContain("private-body-token");
        assertThat(new String(error.handleErrorMessageToClient(request).getPayload(), StandardCharsets.UTF_8))
                .doesNotContain("private-body-token");
    }

    static class InboundRegistration extends ChannelRegistration {
        List<ChannelInterceptor> interceptors() { return getInterceptors(); }
    }
}
