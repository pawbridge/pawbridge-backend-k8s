package com.pawbridge.communityservice.contact;

import com.pawbridge.communityservice.contact.PrivateNoteModels.BlockPage;
import com.pawbridge.communityservice.contact.PrivateNoteModels.ContactMember;
import com.pawbridge.communityservice.contact.PrivateNoteModels.Favorite;
import com.pawbridge.communityservice.contact.PrivateNoteModels.NotePage;
import com.pawbridge.communityservice.contact.PrivateNoteModels.NoteView;
import com.pawbridge.communityservice.contact.PrivateNoteModels.Notifications;
import com.pawbridge.communityservice.contact.PrivateNoteModels.Receipt;
import com.pawbridge.communityservice.contact.PrivateNoteModels.SendNote;
import com.pawbridge.communityservice.util.ResponseDTO;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@Profile("postgresql")
@RequestMapping("/api/v1/notes")
public class PrivateNoteController {

    private final PrivateNoteService privateNoteService;
    private final PrivateNoteStream privateNoteStream;

    public PrivateNoteController(
            PrivateNoteService privateNoteService,
            PrivateNoteStream privateNoteStream) {
        this.privateNoteService = privateNoteService;
        this.privateNoteStream = privateNoteStream;
    }

    @PostMapping
    public ResponseDTO<Receipt> send(
            @RequestHeader("X-User-Id") long currentUserId,
            @RequestBody SendNote sendNoteRequest) {
        return ResponseDTO.okWithData(privateNoteService.send(currentUserId, sendNoteRequest));
    }

    @GetMapping
    public ResponseDTO<NotePage> list(
            @RequestHeader("X-User-Id") long currentUserId,
            @RequestParam(name = "box", defaultValue = "INBOX") String mailbox,
            @RequestParam(defaultValue = "false") boolean favorites,
            @RequestParam(defaultValue = "0") int page) {
        return ResponseDTO.okWithData(privateNoteService.list(currentUserId, mailbox, favorites, page));
    }

    @GetMapping("/notifications")
    public ResponseDTO<Notifications> notifications(
            @RequestHeader("X-User-Id") long currentUserId,
            @RequestParam(required = false) UUID cursor) {
        return ResponseDTO.okWithData(privateNoteService.notifications(currentUserId, cursor));
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(
            @RequestHeader("X-User-Id") long currentUserId,
            @RequestHeader("X-Auth-Expires-At") long tokenExpiresAtEpochMillis) {
        privateNoteService.requireActive(currentUserId);
        Instant tokenExpiresAt = Instant.ofEpochMilli(tokenExpiresAtEpochMillis);

        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .header("X-Accel-Buffering", "no")
                .body(privateNoteStream.open(currentUserId, tokenExpiresAt));
    }

    @GetMapping("/recipients/{member}")
    public ResponseDTO<ContactMember> recipient(
            @RequestHeader("X-User-Id") long currentUserId,
            @PathVariable("member") long recipientId) {
        return ResponseDTO.okWithData(privateNoteService.recipient(currentUserId, recipientId));
    }

    @GetMapping("/blocks")
    public ResponseDTO<BlockPage> blocks(
            @RequestHeader("X-User-Id") long currentUserId,
            @RequestParam(defaultValue = "0") int page) {
        return ResponseDTO.okWithData(privateNoteService.blocks(currentUserId, page));
    }

    @PutMapping("/blocks/{member}")
    public void block(
            @RequestHeader("X-User-Id") long currentUserId,
            @PathVariable("member") long blockedUserId) {
        privateNoteService.block(currentUserId, blockedUserId);
    }

    @DeleteMapping("/blocks/{member}")
    public void unblock(
            @RequestHeader("X-User-Id") long currentUserId,
            @PathVariable("member") long blockedUserId) {
        privateNoteService.unblock(currentUserId, blockedUserId);
    }

    @GetMapping("/{id}")
    public ResponseDTO<NoteView> get(
            @RequestHeader("X-User-Id") long currentUserId,
            @PathVariable("id") UUID noteId) {
        return ResponseDTO.okWithData(privateNoteService.get(currentUserId, noteId));
    }

    @PutMapping("/{id}/read")
    public void read(
            @RequestHeader("X-User-Id") long currentUserId,
            @PathVariable("id") UUID noteId) {
        privateNoteService.read(currentUserId, noteId);
    }

    @PutMapping("/{id}/favorite")
    public void favorite(
            @RequestHeader("X-User-Id") long currentUserId,
            @PathVariable("id") UUID noteId,
            @RequestBody Favorite favoriteRequest) {
        privateNoteService.favorite(currentUserId, noteId, favoriteRequest.favorite());
    }

    @DeleteMapping("/{id}")
    public void delete(
            @RequestHeader("X-User-Id") long currentUserId,
            @PathVariable("id") UUID noteId) {
        privateNoteService.delete(currentUserId, noteId);
    }
}
