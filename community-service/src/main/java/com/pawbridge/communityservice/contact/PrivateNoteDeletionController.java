package com.pawbridge.communityservice.contact;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

/** Internal only. Purge additionally requires the User service's committed pending-deletion flag. */
@RestController
@Profile("postgresql")
@RequestMapping("/internal/private-notes/members")
public class PrivateNoteDeletionController {
    private final PrivateNoteService notes;
    public PrivateNoteDeletionController(PrivateNoteService notes) {this.notes=notes;}
    @DeleteMapping("/{member}") public void withdraw(@PathVariable long member) {notes.withdraw(member);}
}
