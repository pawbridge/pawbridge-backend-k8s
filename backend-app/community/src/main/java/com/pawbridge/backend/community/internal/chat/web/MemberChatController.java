package com.pawbridge.backend.community.internal.chat.web;

import static com.pawbridge.backend.community.internal.chat.application.MemberChatModels.*;
import com.pawbridge.backend.community.internal.chat.application.MemberChat;
import com.pawbridge.backend.community.internal.chat.application.MemberChatRejectedException;
import com.pawbridge.backend.community.internal.chat.infrastructure.MemberChatTickets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/chats")
public final class MemberChatController {
    private static final String ROOM_PATH="/{room:[0-9a-fA-F-]{36}}";
    private final MemberChat service;
    private final MemberChatTickets tickets;
    public MemberChatController(MemberChat service,MemberChatTickets tickets){this.service=service;this.tickets=tickets;}
    @GetMapping(ROOM_PATH)
    public Response<Room> room(@AuthenticationPrincipal Long owner,@PathVariable("room") UUID room){return ok(service.room(owner,room));}
    @GetMapping("/with/{member}")
    public Response<ExistingRoom> existingRoom(@AuthenticationPrincipal Long owner,@PathVariable("member") long member){return ok(service.existingRoom(owner,member));}
    @GetMapping
    public Response<RoomPage> rooms(@AuthenticationPrincipal Long owner,@RequestParam(name="cursor",required=false) UUID cursor){return ok(service.rooms(owner,cursor));}
    @GetMapping("/notifications")
    public Response<Notifications> notifications(@AuthenticationPrincipal Long owner,@RequestParam(name="cursor",required=false) UUID cursor){return ok(service.notifications(owner,cursor));}
    @GetMapping(ROOM_PATH+"/messages")
    public Response<MessagePage> messages(@AuthenticationPrincipal Long owner,@PathVariable("room") UUID room,
            @RequestParam(name="before",required=false) Long before,@RequestParam(name="after",required=false) Long after){return ok(service.messages(owner,room,before,after));}
    @PutMapping(ROOM_PATH+"/read")
    public void read(@AuthenticationPrincipal Long owner,@PathVariable("room") UUID room,@RequestBody Read request){service.read(owner,room,request.sequence());}
    @DeleteMapping(ROOM_PATH+"/visibility")
    public void hide(@AuthenticationPrincipal Long owner,@PathVariable("room") UUID room){service.hide(owner,room);}
    @PostMapping("/connection-ticket")
    public Response<MemberChatTickets.Ticket> ticket(Authentication authentication){
        if(authentication==null || !(authentication.getPrincipal() instanceof Long owner)
                || !(authentication.getDetails() instanceof Instant expires))
            throw new MemberChatRejectedException(401,"로그인이 만료되었습니다.");
        service.active(owner);
        return ok(tickets.issue(owner,expires.toEpochMilli()));
    }
    private static <T> Response<T> ok(T data){return new Response<>(200,"요청이 성공적으로 처리되었습니다.",data);}
    public record Response<T>(int code,String message,T data){}
}
