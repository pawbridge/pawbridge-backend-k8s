package com.pawbridge.backend.user.internal.emailverification.infrastructure

import jakarta.mail.Message
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mail.MailSendException
import org.springframework.mail.javamail.JavaMailSenderImpl
import java.nio.charset.StandardCharsets

class SmtpVerificationMailsTest {
    @Test
    fun given__templates__when__loading__then__both_original_templates_carry_the_code_placeholder() {
        for (name in listOf("templates/email_verification.html", "templates/password_reset.html")) {
            javaClass.classLoader.getResourceAsStream(name).use { input ->
                assertNotNull(input, name)
                assertTrue(String(input!!.readAllBytes(), StandardCharsets.UTF_8).contains("{{code}}"), name)
            }
        }
    }

    @Test
    fun given__signup_and_reset__when__sending__then__original_subjects_sender_and_recipient() {
        val sent = ArrayList<MimeMessage>()
        val sender = object : JavaMailSenderImpl() {
            override fun send(message: MimeMessage) {
                sent.add(message)
            }
        }
        val mails = SmtpVerificationMails(sender, "noreply@example.test")

        mails.sendVerification("to@example.test", "123456")
        mails.sendPasswordReset("to@example.test", "654321")

        assertEquals(2, sent.size)
        assertEquals("[PawBridge] 이메일 인증 코드", sent[0].subject)
        assertEquals("[PawBridge] 비밀번호 재설정 인증 코드", sent[1].subject)
        for (message in sent) {
            val from = message.from[0]
            assertEquals("noreply@example.test", (from as InternetAddress).address)
            assertEquals("PawBridge", from.personal)
            assertEquals("to@example.test", message.getRecipients(Message.RecipientType.TO)[0].toString())
        }
    }

    @Test
    fun given__transport_mail_exception__when__sending__then__it_propagates_unchanged_like_the_original() {
        val sender = object : JavaMailSenderImpl() {
            override fun send(message: MimeMessage) {
                throw MailSendException("smtp down")
            }
        }
        val mails = SmtpVerificationMails(sender, "noreply@example.test")

        assertThrows(MailSendException::class.java) { mails.sendVerification("to@example.test", "123456") }
    }
}
