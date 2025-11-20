package com.pawbridge.emailservice.service;

import com.pawbridge.emailservice.exception.EmailSendingException;
import com.pawbridge.emailservice.exception.EmailTemplateLoadException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class EmailSenderService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromEmail;

    /**
     * 인증 이메일 발송
     */
    public void sendVerificationEmail(String to, String code) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(new InternetAddress(fromEmail, "PawBridge"));
            helper.setTo(to);
            helper.setSubject("[PawBridge] 이메일 인증 코드");

            String emailContent = loadEmailTemplate(code);
            helper.setText(emailContent, true);

            mailSender.send(message);

        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new EmailSendingException("이메일 발송에 실패했습니다.", e);
        }
    }

    /**
     * 이메일 템플릿 로드
     */
    private String loadEmailTemplate(String code) {
        try {
            Resource resource = new ClassPathResource("templates/email_verification.html");
            String template = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return template.replace("{{code}}", code);
        } catch (IOException e) {
            throw new EmailTemplateLoadException();
        }
    }
}
