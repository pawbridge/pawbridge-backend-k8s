package com.pawbridge.backend.user.internal.emailverification.infrastructure

import com.pawbridge.backend.user.internal.emailverification.application.EmailSendingException
import com.pawbridge.backend.user.internal.emailverification.application.EmailTemplateLoadException
import com.pawbridge.backend.user.internal.emailverification.application.VerificationMails
import jakarta.mail.MessagingException
import jakarta.mail.internet.InternetAddress
import org.springframework.core.io.ClassPathResource
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import java.io.IOException
import java.io.UnsupportedEncodingException
import java.nio.charset.StandardCharsets

/** Original EmailSenderService: HTML templates with a {{code}} placeholder and the PawBridge sender name. */
class SmtpVerificationMails(
    private val mailSender: JavaMailSender,
    private val fromEmail: String,
) : VerificationMails {

    override fun sendVerification(to: String?, code: String) {
        send(to, "[PawBridge] 이메일 인증 코드", "templates/email_verification.html", code)
    }

    override fun sendPasswordReset(to: String?, code: String) {
        send(to, "[PawBridge] 비밀번호 재설정 인증 코드", "templates/password_reset.html", code)
    }

    private fun send(to: String?, subject: String, templatePath: String, code: String) {
        try {
            val message = mailSender.createMimeMessage()
            val helper = MimeMessageHelper(message, true, "UTF-8")
            helper.setFrom(InternetAddress(fromEmail, "PawBridge"))
            // MimeMessageHelper (Spring Assert) rejects a null recipient with this IllegalArgumentException.
            helper.setTo(requireNotNull(to) { "To address must not be null" })
            helper.setSubject(subject)
            helper.setText(loadTemplate(templatePath, code), true)
            // Other mail failures (MailException) propagate unchanged, exactly like the original.
            mailSender.send(message)
        } catch (failure: MessagingException) {
            throw EmailSendingException(failure)
        } catch (failure: UnsupportedEncodingException) {
            throw EmailSendingException(failure)
        }
    }

    private fun loadTemplate(templatePath: String, code: String): String {
        try {
            // Like the Java version, the resource stream is not closed explicitly.
            val template = String(ClassPathResource(templatePath).inputStream.readAllBytes(), StandardCharsets.UTF_8)
            return template.replace("{{code}}", code)
        } catch (failure: IOException) {
            throw EmailTemplateLoadException()
        }
    }
}
