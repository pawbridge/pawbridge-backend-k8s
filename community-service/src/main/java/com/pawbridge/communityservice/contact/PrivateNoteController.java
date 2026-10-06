package com.pawbridge.communityservice.contact;

import static com.pawbridge.communityservice.contact.PrivateNoteModels.*;
import com.pawbridge.communityservice.util.ResponseDTO;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@Profile("postgresql")
@RequestMapping("/api/v1/notes")
public class PrivateNoteController {
    private final PrivateNoteService notes;
    private final PrivateNoteStream streams;
    public PrivateNoteController(PrivateNoteService notes,PrivateNoteStream streams) {this.notes=notes;this.streams=streams;}
    @PostMapping public ResponseDTO<Receipt> send(@RequestHeader("X-User-Id") long owner,@RequestBody SendNote input) {return ResponseDTO.okWithData(notes.send(owner,input));}
    @GetMapping public ResponseDTO<NotePage> list(@RequestHeader("X-User-Id") long owner,@RequestParam(defaultValue="INBOX") String box,@RequestParam(defaultValue="false") boolean favorites,@RequestParam(defaultValue="0") int page) {return ResponseDTO.okWithData(notes.list(owner,box,favorites,page));}
    @GetMapping("/notifications") public ResponseDTO<Notifications> notifications(@RequestHeader("X-User-Id") long owner,@RequestParam(required=false) UUID cursor) {return ResponseDTO.okWithData(notes.notifications(owner,cursor));}
    @GetMapping(value="/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@RequestHeader("X-User-Id") long owner,@RequestHeader("X-Auth-Expires-At") long expiry) {
        notes.requireActive(owner);
        return ResponseEntity.ok().header("Cache-Control","no-store").header("X-Accel-Buffering","no")
                .body(streams.open(owner,Instant.ofEpochMilli(expiry)));
    }
    @GetMapping("/recipients/{member}") public ResponseDTO<ContactMember> recipient(@RequestHeader("X-User-Id") long owner,@PathVariable long member) {return ResponseDTO.okWithData(notes.recipient(owner,member));}
    @GetMapping("/blocks") public ResponseDTO<BlockPage> blocks(@RequestHeader("X-User-Id") long owner,@RequestParam(defaultValue="0") int page) {return ResponseDTO.okWithData(notes.blocks(owner,page));}
    @PutMapping("/blocks/{member}") public void block(@RequestHeader("X-User-Id") long owner,@PathVariable long member) {notes.block(owner,member);}
    @DeleteMapping("/blocks/{member}") public void unblock(@RequestHeader("X-User-Id") long owner,@PathVariable long member) {notes.unblock(owner,member);}
    @GetMapping("/{id}") public ResponseDTO<NoteView> get(@RequestHeader("X-User-Id") long owner,@PathVariable UUID id) {return ResponseDTO.okWithData(notes.get(owner,id));}
    @PutMapping("/{id}/read") public void read(@RequestHeader("X-User-Id") long owner,@PathVariable UUID id) {notes.read(owner,id);}
    @PutMapping("/{id}/favorite") public void favorite(@RequestHeader("X-User-Id") long owner,@PathVariable UUID id,@RequestBody Favorite input) {notes.favorite(owner,id,input.favorite());}
    @DeleteMapping("/{id}") public void delete(@RequestHeader("X-User-Id") long owner,@PathVariable UUID id) {notes.delete(owner,id);}
}
