package com.pawbridge.userservice.email.service;

import com.pawbridge.userservice.exception.EmailSendingException;
import com.pawbridge.userservice.exception.EmailTemplateLoadException;
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
     * 회원가입 인증 이메일 발송
     */
    public void sendVerificationEmail(String to, String code) {
        sendEmail(to, "[PawBridge] 이메일 인증 코드", "templates/email_verification.html", code);
    }

    /**
     * 비밀번호 재설정 인증 이메일 발송
     */
    public void sendPasswordResetEmail(String to, String code) {
        sendEmail(to, "[PawBridge] 비밀번호 재설정 인증 코드", "templates/password_reset.html", code);
    }

    /**
     * 이메일 발송 공통 로직
     */
    private void sendEmail(String to, String subject, String templatePath, String code) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(new InternetAddress(fromEmail, "PawBridge"));
            helper.setTo(to);
            helper.setSubject(subject);

            String emailContent = loadEmailTemplate(templatePath, code);
            helper.setText(emailContent, true);

            mailSender.send(message);

        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new EmailSendingException("이메일 발송에 실패했습니다.", e);
        }
    }

    /**
     * 이메일 템플릿 로드
     */
    private String loadEmailTemplate(String templatePath, String code) {
        try {
            Resource resource = new ClassPathResource(templatePath);
            String template = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return template.replace("{{code}}", code);
        } catch (IOException e) {
            throw new EmailTemplateLoadException();
        }
    }
}
