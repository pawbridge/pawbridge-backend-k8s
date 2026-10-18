package com.pawbridge.communityservice.chat;

import static com.pawbridge.communityservice.chat.MemberChatModels.*;

import java.security.Principal;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.server.ResponseStatusException;

@Controller
@ConditionalOnProperty(name = "pawbridge.chat.enabled", havingValue = "true")
public class MemberChatSocketController {
    private final MemberChatService service;
    private final SimpMessagingTemplate messaging;
    private final Clock clock;

    public MemberChatSocketController(MemberChatService service, SimpMessagingTemplate messaging, Clock clock) {
        this.service = service;
        this.messaging = messaging;
        this.clock = clock;
    }

    @MessageMapping("/chat/send")
    public void send(@Payload Send input, Principal principal, @Header("simpSessionId") String sessionId) {
        if (!(principal instanceof MemberChatTickets.Identity identity)) return;
        Object response;
        try {
            if (identity.expiresAt() <= clock.millis()) {
                throw MemberChatService.error(org.springframework.http.HttpStatus.UNAUTHORIZED, "로그인이 만료되었습니다.");
            }
            response = service.send(identity.memberId(), input);
        } catch (ResponseStatusException rejected) {
            response = new Failure(input == null ? null : input.requestId(), rejected.getStatusCode().value(), rejected.getReason());
        } catch (RuntimeException unavailable) {
            response = new Failure(input == null ? null : input.requestId(), 503, "전송 결과를 확인하지 못했습니다. 같은 메시지로 다시 시도하세요.");
        }
        // The application receipt belongs to the sending connection, not every tab of this member.
        var headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(sessionId);
        headers.setLeaveMutable(true);
        messaging.convertAndSendToUser(sessionId, "/queue/chat", response, headers.getMessageHeaders());
    }

    @MessageExceptionHandler(Exception.class)
    @SendToUser(value = "/queue/chat", broadcast = false)
    public Failure invalidFrame() {
        // Message conversion errors may contain the submitted body; suppress the exception entirely.
        return new Failure(null, 400, "메시지 형식을 확인해 주세요.");
    }
}
