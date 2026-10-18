package com.pawbridge.communityservice.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class MemberChatSocketControllerTest {
    @Test
    void givenSameMemberWithTwoConnections_whenSending_thenReceiptTargetsEachSendingSession() {
        var service = mock(MemberChatService.class);
        var messaging = mock(SimpMessagingTemplate.class);
        var now = Instant.parse("2026-10-10T01:00:00Z");
        var controller = new MemberChatSocketController(service, messaging, Clock.fixed(now, ZoneOffset.UTC));
        var input = new MemberChatModels.Send(8L, UUID.randomUUID(), "합성 메시지", null, null);
        var receipt = new MemberChatModels.Receipt(input.requestId(), UUID.randomUUID(), 1);
        when(service.send(7L, input)).thenReturn(receipt);
        var identity = new MemberChatTickets.Identity(7, now.plusSeconds(300).toEpochMilli());

        controller.send(input, identity, "first-connection");
        controller.send(input, identity, "second-connection");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> headers = ArgumentCaptor.forClass(Map.class);
        verify(messaging).convertAndSendToUser(eq("first-connection"), eq("/queue/chat"), eq(receipt), headers.capture());
        assertThat(SimpMessageHeaderAccessor.getSessionId(headers.getValue())).isEqualTo("first-connection");
        verify(messaging).convertAndSendToUser(eq("second-connection"), eq("/queue/chat"), eq(receipt), any(Map.class));
    }
}
