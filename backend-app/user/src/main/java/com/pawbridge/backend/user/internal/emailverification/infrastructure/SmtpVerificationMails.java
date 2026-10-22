package com.pawbridge.backend.user.internal.emailverification.infrastructure;

import com.pawbridge.backend.user.internal.emailverification.application.EmailSendingException;
import com.pawbridge.backend.user.internal.emailverification.application.EmailTemplateLoadException;
import com.pawbridge.backend.user.internal.emailverification.application.VerificationMails;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/** Original EmailSenderService: HTML templates with a {{code}} placeholder and the PawBridge sender name. */
public final class SmtpVerificationMails implements VerificationMails {
    private final JavaMailSender mailSender;
    private final String fromEmail;

    public SmtpVerificationMails(JavaMailSender mailSender, String fromEmail) {
        this.mailSender = mailSender;
        this.fromEmail = fromEmail;
    }

    @Override
    public void sendVerification(String to, String code) {
        send(to, "[PawBridge] 이메일 인증 코드", "templates/email_verification.html", code);
    }

    @Override
    public void sendPasswordReset(String to, String code) {
        send(to, "[PawBridge] 비밀번호 재설정 인증 코드", "templates/password_reset.html", code);
    }

    private void send(String to, String subject, String templatePath, String code) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(new InternetAddress(fromEmail, "PawBridge"));
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(loadTemplate(templatePath, code), true);
            // Other mail failures (MailException) propagate unchanged, exactly like the original.
            mailSender.send(message);
        } catch (MessagingException | UnsupportedEncodingException failure) {
            throw new EmailSendingException(failure);
        }
    }

    private static String loadTemplate(String templatePath, String code) {
        try {
            String template = new String(new ClassPathResource(templatePath).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            return template.replace("{{code}}", code);
        } catch (IOException failure) {
            throw new EmailTemplateLoadException();
        }
    }
}
