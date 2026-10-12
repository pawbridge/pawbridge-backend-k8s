package com.pawbridge.communityservice.chat;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;

class MemberChatSignalsTest {
    final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    final SimpUserRegistry registry = mock(SimpUserRegistry.class);
    final ObjectMapper mapper = new ObjectMapper();
    final MemberChatSignals signals = new MemberChatSignals(mock(StringRedisTemplate.class), messaging,
            mock(MemberChatSessions.class), mapper, registry, "synthetic-test");

    @Test void givenTwoOwnConnectionsAndFirstSendFailure_whenMessageSignalArrives_thenDeliverToOtherConnection() throws Exception {
        var first = mock(SimpSession.class); when(first.getId()).thenReturn("first-session");
        var second = mock(SimpSession.class); when(second.getId()).thenReturn("second-session");
        var member = mock(SimpUser.class);
        when(member.getSessions()).thenReturn(new LinkedHashSet<>(List.of(first, second)));
        when(registry.getUser("7")).thenReturn(member);
        var signal = new MemberChatModels.Signal(7, "MESSAGE", UUID.randomUUID(), 3);
        doThrow(new IllegalStateException("synthetic send failure")).when(messaging)
                .convertAndSendToUser(eq("first-session"), eq("/queue/chat"), eq(signal), anyMap());

        signals.onMessage(new DefaultMessage(new byte[0], mapper.writeValueAsBytes(signal)), null);

        verify(messaging).convertAndSendToUser(eq("first-session"), eq("/queue/chat"), eq(signal), anyMap());
        verify(messaging).convertAndSendToUser(eq("second-session"), eq("/queue/chat"), eq(signal),
                org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(headers -> "second-session".equals(SimpMessageHeaderAccessor.getSessionId(headers))));
    }

    @Test void givenMemberWithoutLocalConnections_whenMessageSignalArrives_thenDoNotForwardToOtherMembers() throws Exception {
        var signal = new MemberChatModels.Signal(7, "MESSAGE", UUID.randomUUID(), 3);

        signals.onMessage(new DefaultMessage(new byte[0], mapper.writeValueAsBytes(signal)), null);

        verify(registry).getUser("7");
        verifyNoMoreInteractions(registry);
        verifyNoInteractions(messaging);
    }
}
