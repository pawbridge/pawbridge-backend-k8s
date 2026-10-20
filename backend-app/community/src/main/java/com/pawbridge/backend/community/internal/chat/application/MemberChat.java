package com.pawbridge.backend.community.internal.chat.application;

import static com.pawbridge.backend.community.internal.chat.application.MemberChatModels.*;
import com.pawbridge.backend.user.api.ContactMember;
import java.util.UUID;

public interface MemberChat {
    Receipt send(long sender, Send input);
    ExistingRoom existingRoom(long owner, long counterpartId);
    RoomPage rooms(long owner, UUID cursor);
    Room room(long owner, UUID room);
    Notifications notifications(long owner, UUID cursor);
    MessagePage messages(long owner, UUID room, Long before, Long after);
    void read(long owner, UUID room, long through);
    void hide(long owner, UUID room);
    ContactMember active(long memberId);
    void expire();
}
