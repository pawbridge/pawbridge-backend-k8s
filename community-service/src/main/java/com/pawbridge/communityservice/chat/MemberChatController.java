package com.pawbridge.communityservice.chat;

import static com.pawbridge.communityservice.chat.MemberChatModels.*;

import com.pawbridge.communityservice.util.ResponseDTO;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("postgresql")
@ConditionalOnProperty(name = "pawbridge.chat.enabled", havingValue = "true")
@RequestMapping("/api/v1/chats")
public class MemberChatController {
    // Keep reserved endpoints such as /socket out of REST room lookup.
    private static final String ROOM_PATH = "/{room:[0-9a-fA-F-]{36}}";
    private final MemberChatService service;
    private final MemberChatTickets tickets;

    public MemberChatController(MemberChatService service, MemberChatTickets tickets) {
        this.service = service;
        this.tickets = tickets;
    }

    @GetMapping(ROOM_PATH)
    public ResponseDTO<Room> room(@RequestHeader("X-User-Id") long owner, @PathVariable UUID room) {
        return ResponseDTO.okWithData(service.room(owner, room));
    }

    @GetMapping
    public ResponseDTO<RoomPage> rooms(@RequestHeader("X-User-Id") long owner,
                                       @RequestParam(required = false) UUID cursor) {
        return ResponseDTO.okWithData(service.rooms(owner, cursor));
    }

    @GetMapping("/notifications")
    public ResponseDTO<Notifications> notifications(@RequestHeader("X-User-Id") long owner,
                                                    @RequestParam(required = false) UUID cursor) {
        return ResponseDTO.okWithData(service.notifications(owner, cursor));
    }

    @GetMapping(ROOM_PATH + "/messages")
    public ResponseDTO<MessagePage> messages(@RequestHeader("X-User-Id") long owner,
                                             @PathVariable UUID room,
                                             @RequestParam(required = false) Long before,
                                             @RequestParam(required = false) Long after) {
        return ResponseDTO.okWithData(service.messages(owner, room, before, after));
    }

    @PutMapping(ROOM_PATH + "/read")
    public void read(@RequestHeader("X-User-Id") long owner, @PathVariable UUID room, @RequestBody Read request) {
        service.read(owner, room, request.sequence());
    }

    @DeleteMapping(ROOM_PATH + "/visibility")
    public void hide(@RequestHeader("X-User-Id") long owner, @PathVariable UUID room) {
        service.hide(owner, room);
    }

    @PostMapping("/connection-ticket")
    public ResponseDTO<MemberChatTickets.Ticket> ticket(@RequestHeader("X-User-Id") long owner,
                                                       @RequestHeader("X-Auth-Expires-At") long expiresAt) {
        service.active(owner);
        return ResponseDTO.okWithData(tickets.issue(owner, expiresAt));
    }
}
