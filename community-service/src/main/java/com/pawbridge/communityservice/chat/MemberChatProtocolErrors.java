package com.pawbridge.communityservice.chat;

import java.nio.charset.StandardCharsets;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

/** Never echo an untrusted CONNECT/SEND frame, parser error or exception into the response. */
public class MemberChatProtocolErrors extends StompSubProtocolErrorHandler {
    @Override public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> request, Throwable failure) {
        return rejected();
    }

    @Override public Message<byte[]> handleErrorMessageToClient(Message<byte[]> error) {
        return rejected();
    }

    private Message<byte[]> rejected() {
        var headers = StompHeaderAccessor.create(StompCommand.ERROR);
        headers.setMessage("채팅 연결을 다시 시작하세요.");
        // Match Spring's mutable error-frame contract without including the submitted frame.
        headers.setLeaveMutable(true);
        return MessageBuilder.createMessage("채팅 요청을 처리하지 못했습니다.".getBytes(StandardCharsets.UTF_8),
                headers.getMessageHeaders());
    }
}
