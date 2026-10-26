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
    fun `원본 템플릿 두 개가 있을 때__불러오면__둘 다 코드 자리표시자를 가진다`() {
        // when
        for (name in listOf("templates/email_verification.html", "templates/password_reset.html")) {
            javaClass.classLoader.getResourceAsStream(name).use { input ->
                // then
                assertNotNull(input, name)
                assertTrue(String(input!!.readAllBytes(), StandardCharsets.UTF_8).contains("{{code}}"), name)
            }
        }
    }

    @Test
    fun `회원가입과 비밀번호 재설정 메일일 때__보내면__원래의 제목과 발신자와 수신자를 쓴다`() {
        // given
        val sent = ArrayList<MimeMessage>()
        val sender = object : JavaMailSenderImpl() {
            override fun send(message: MimeMessage) {
                sent.add(message)
            }
        }
        val mails = SmtpVerificationMails(sender, "noreply@example.test")

        // when
        mails.sendVerification("to@example.test", "123456")
        mails.sendPasswordReset("to@example.test", "654321")

        // then
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
    fun `전송 계층이 메일 예외를 던질 때__보내면__원래처럼 그대로 전파한다`() {
        // given
        val sender = object : JavaMailSenderImpl() {
            override fun send(message: MimeMessage) {
                throw MailSendException("smtp down")
            }
        }
        val mails = SmtpVerificationMails(sender, "noreply@example.test")

        // when
        // then
        assertThrows(MailSendException::class.java) { mails.sendVerification("to@example.test", "123456") }
    }
}
