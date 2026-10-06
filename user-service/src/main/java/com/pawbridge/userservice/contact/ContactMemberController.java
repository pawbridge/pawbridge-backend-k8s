package com.pawbridge.userservice.contact;

import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** Minimal internal contract: never returns email, real name, password or provider identity. */
@RestController
@Profile("postgresql")
@RequestMapping("/api/v1/users/internal")
public class ContactMemberController {
    public record ContactMember(Long userId,String nickname,boolean active,boolean deletionPending) {}
    private final JdbcTemplate jdbc;
    public ContactMemberController(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    @GetMapping("/{id}/contact")
    public ContactMember get(@PathVariable long id) {
        List<ContactMember> found=jdbc.query("SELECT u.user_id,u.nickname,d.user_id IS NOT NULL AS pending FROM pawbridge_user.users u LEFT JOIN pawbridge_user.contact_deletions d ON d.user_id=u.user_id WHERE u.user_id=?",
                (r,i) -> new ContactMember(r.getLong(1),r.getBoolean(3)?"탈퇴한 회원":r.getString(2),!r.getBoolean(3),r.getBoolean(3)),id);
        return found.isEmpty()?new ContactMember(id,"탈퇴한 회원",false,false):found.get(0);
    }
}
