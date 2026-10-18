package com.pawbridge.communityservice.contact;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal only. Purge additionally requires the User service's committed pending-deletion flag. */
@RestController
@RequestMapping("/internal/private-notes/members")
public class PrivateNoteDeletionController {
    private final PrivateNoteService privateNoteService;

    public PrivateNoteDeletionController(PrivateNoteService privateNoteService) {
        this.privateNoteService = privateNoteService;
    }

    @DeleteMapping("/{member}")
    public void withdraw(@PathVariable("member") long userId) {
        privateNoteService.withdraw(userId);
    }
}
