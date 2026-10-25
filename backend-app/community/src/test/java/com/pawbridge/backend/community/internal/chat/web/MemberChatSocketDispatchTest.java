package com.pawbridge.backend.community.internal.chat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pawbridge.backend.community.internal.chat.application.MemberChat;
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Failure;
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Receipt;
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Send;
import com.pawbridge.backend.community.internal.chat.infrastructure.MemberChatTickets;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.support.SimpAnnotationMethodMessageHandler;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.MimeTypeUtils;

/** Dispatches /app/chat/send through Spring's real annotation handler, so argument resolution is covered. */
class MemberChatSocketDispatchTest {
    private static final UUID REQUEST = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String VALID_FRAME = "{\"recipientId\":8,\"requestId\":\"" + REQUEST + "\",\"body\":\"hi\"}";

    private final List<Message<?>> outbound = new ArrayList<>();
    private final MemberChat chat = mock(MemberChat.class);
    private final SimpAnnotationMethodMessageHandler handler = handler();

    @Test
    void givenTicketIdentityAndValidFrame__whenSending__thenServiceReceivesItAndReceiptGoesToTheSession() {
        var receipt = new Receipt(REQUEST, UUID.randomUUID(), 1);
        when(chat.send(anyLong(), any())).thenReturn(receipt);

        dispatch(VALID_FRAME, new MemberChatTickets.Identity(7, Long.MAX_VALUE));

        verify(chat).send(7, new Send(8L, REQUEST, "hi", null, null));
        assertThat(outbound).singleElement().satisfies(message -> {
            assertThat(SimpMessageHeaderAccessor.getDestination(message.getHeaders())).isEqualTo("/user/s1/queue/chat");
            assertThat(message.getPayload()).isEqualTo(receipt);
        });
    }

    @Test
    void givenEmptyPayload__whenSending__thenNothingIsSentAndInvalidFrameAnswers() {
        dispatch("", new MemberChatTickets.Identity(7, Long.MAX_VALUE));

        verify(chat, never()).send(anyLong(), any());
        assertThat(outbound).singleElement().satisfies(message ->
                assertThat(message.getPayload()).isEqualTo(new Failure(null, 400, "메시지 형식을 확인해 주세요.")));
    }

    @Test
    void givenNoTicketIdentity__whenSending__thenReturnSilently() {
        dispatch(VALID_FRAME, null);

        verify(chat, never()).send(anyLong(), any());
        assertThat(outbound).isEmpty();
    }

    private void dispatch(String frame, MemberChatTickets.Identity user) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setDestination("/app/chat/send");
        headers.setSessionId("s1");
        headers.setSessionAttributes(new HashMap<>());
        headers.setContentType(MimeTypeUtils.APPLICATION_JSON);
        if (user != null) {
            headers.setUser(user);
        }
        handler.handleMessage(MessageBuilder.createMessage(frame.getBytes(StandardCharsets.UTF_8), headers.getMessageHeaders()));
    }

    private SimpAnnotationMethodMessageHandler handler() {
        MessageChannel capture = (message, timeout) -> outbound.add(message);
        var template = new SimpMessagingTemplate(capture);
        var context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("controller",
                new MemberChatSocketController(chat, template, Clock.systemUTC()));
        context.refresh();
        var handler = new SimpAnnotationMethodMessageHandler(new ExecutorSubscribableChannel(), capture, template);
        handler.setDestinationPrefixes(List.of("/app"));
        handler.setMessageConverter(new MappingJackson2MessageConverter());
        handler.setApplicationContext(context);
        handler.afterPropertiesSet();
        return handler;
    }
}
