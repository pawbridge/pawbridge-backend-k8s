package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.pawbridge.backend.community.internal.chat.application.MemberChat;
import com.pawbridge.backend.community.internal.chat.web.MemberChatSessions;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

class MemberChatSessionLifecycleTest {
    @Test void given__authenticated_and_pending_sockets__when__destroying_twice__then__close_each_once_without_member_or_redis_io()throws Exception{
        StringRedisTemplate redis=mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ObjectProvider<MemberChat> chat=mock(ObjectProvider.class);
        var sessions=new MemberChatSessions(redis,chat,Clock.systemUTC(),"synthetic-test");
        var first=mock(WebSocketSession.class);when(first.getId()).thenReturn("first");when(first.isOpen()).thenReturn(true);
        var second=mock(WebSocketSession.class);when(second.getId()).thenReturn("second");
        assertThat(sessions.open(first)).isTrue();assertThat(sessions.open(second)).isTrue();
        when(redis.execute(org.mockito.ArgumentMatchers.any(org.springframework.data.redis.core.script.RedisScript.class),
                org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString())).thenReturn(1L);
        sessions.authenticate("first",new com.pawbridge.backend.community.internal.chat.infrastructure.MemberChatTickets.Identity(7,Long.MAX_VALUE));
        clearInvocations(redis);
        doAnswer(call->{sessions.removed("first");return null;}).when(first).close(CloseStatus.POLICY_VIOLATION);
        sessions.destroy();sessions.destroy();
        // A delayed transport-disconnect callback must also see the already detached connection.
        sessions.removed("first");sessions.removed("second");
        verify(first).close(CloseStatus.POLICY_VIOLATION);verify(second).close(CloseStatus.POLICY_VIOLATION);
        verifyNoInteractions(redis,chat);
    }
}
