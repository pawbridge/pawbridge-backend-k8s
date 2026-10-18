package com.pawbridge.userservice.contact;

import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Minimal internal contract: never returns email, real name, password or provider identity. */
@RestController
@Profile("postgresql")
@RequestMapping("/api/v1/users/internal")
public class ContactMemberController {
    private final ContactMemberQuery contactMemberQuery;

    public ContactMemberController(ContactMemberQuery contactMemberQuery) {
        this.contactMemberQuery = contactMemberQuery;
    }

    @GetMapping("/{id}/contact")
    public ContactMember get(@PathVariable("id") long userId) {
        return contactMemberQuery.get(userId);
    }

    @GetMapping("/contacts")
    public List<ContactMember> getAll(@RequestParam("ids") List<Long> userIds) {
        return contactMemberQuery.getAll(userIds);
    }
}
