package com.pawbridge.backend.user.internal.emailverification.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class SmtpVerificationMailsTest {
    @Test
    void given__templates__when__loading__then__both_original_templates_carry_the_code_placeholder() throws Exception {
        for (String name : List.of("templates/email_verification.html", "templates/password_reset.html")) {
            try (var in = getClass().getClassLoader().getResourceAsStream(name)) {
                assertNotNull(in, name);
                assertTrue(new String(in.readAllBytes(), StandardCharsets.UTF_8).contains("{{code}}"), name);
            }
        }
    }

    @Test
    void given__signup_and_reset__when__sending__then__original_subjects_sender_and_recipient() throws Exception {
        var sent = new ArrayList<MimeMessage>();
        var sender = new JavaMailSenderImpl() {
            @Override public void send(MimeMessage message) { sent.add(message); }
        };
        var mails = new SmtpVerificationMails(sender, "noreply@example.test");

        mails.sendVerification("to@example.test", "123456");
        mails.sendPasswordReset("to@example.test", "654321");

        assertEquals(2, sent.size());
        assertEquals("[PawBridge] 이메일 인증 코드", sent.get(0).getSubject());
        assertEquals("[PawBridge] 비밀번호 재설정 인증 코드", sent.get(1).getSubject());
        for (MimeMessage message : sent) {
            Address from = message.getFrom()[0];
            assertEquals("noreply@example.test", ((InternetAddress) from).getAddress());
            assertEquals("PawBridge", ((InternetAddress) from).getPersonal());
            assertEquals("to@example.test", message.getRecipients(Message.RecipientType.TO)[0].toString());
        }
    }

    @Test
    void given__transport_mail_exception__when__sending__then__it_propagates_unchanged_like_the_original() {
        var sender = new JavaMailSenderImpl() {
            @Override public void send(MimeMessage message) { throw new MailSendException("smtp down"); }
        };
        var mails = new SmtpVerificationMails(sender, "noreply@example.test");

        assertThrows(MailSendException.class, () -> mails.sendVerification("to@example.test", "123456"));
    }
}
