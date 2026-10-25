package com.pawbridge.backend.user.internal.emailverification.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EmailVerificationsTest {
    private val events = ArrayList<String>()
    private val store = FakeStore(events)
    private val codes = ArrayList<String>()
    private var failMail = false
    private val mails = object : VerificationMails {
        override fun sendVerification(to: String?, code: String) {
            mail("signup", to, code)
        }

        override fun sendPasswordReset(to: String?, code: String) {
            mail("reset", to, code)
        }

        private fun mail(kind: String, to: String?, code: String) {
            events.add("mail:" + kind + ":" + to)
            codes.add(code)
            if (failMail) {
                throw EmailSendingException()
            }
        }
    }
    private val verifications = EmailVerifications(store, mails)

    @Test
    fun `회원가입 발송일 때__보내면__횟수 집계 다음 메일 순서로 처리하고 코드는 5분 유효에 시도 횟수를 초기화한다`() {
        // given
        store.values["email:attempts:a@b.c"] = "3"

        // when
        verifications.sendVerificationCode("a@b.c")

        // then
        assertEquals(listOf("count:email:send:count:a@b.c:5:300", "mail:signup:a@b.c"), events)
        val code = codes[0]
        assertTrue(code.matches(Regex("\\d{6}")))
        assertEquals(code, store.values["email:code:a@b.c"])
        assertEquals(300L, store.ttl["email:code:a@b.c"])
        assertNull(store.values["email:attempts:a@b.c"])
    }

    @Test
    fun `메일 발송이 실패했을 때__보내면__코드를 저장하지 않는다`() {
        // given
        failMail = true

        // when
        assertThrows(EmailSendingException::class.java) { verifications.sendVerificationCode("a@b.c") }

        // then
        assertNull(store.values["email:code:a@b.c"])
    }

    @Test
    fun `이미 다섯 번 보냈을 때__여섯 번째로 보내면__발송 횟수 초과로 거부한다`() {
        // given
        for (i in 0 until 5) {
            verifications.sendVerificationCode("a@b.c")
        }

        // when
        val failure = assertThrows(TooManyAttemptsException::class.java) { verifications.sendVerificationCode("a@b.c") }

        // then
        assertEquals(TooManyAttemptsException.Kind.SEND, failure.kind())
        assertEquals(5, codes.size)
    }

    @Test
    fun `코드가 일치할 때__검증하면__한 시간 동안 인증된 상태가 되고 코드와 시도 횟수는 지워진다`() {
        // given
        store.values["email:code:a@b.c"] = "123456"
        store.values["email:attempts:a@b.c"] = "2"

        // when
        val verified = verifications.verifyCode("a@b.c", "123456")

        // then
        assertTrue(verified)
        assertTrue(verifications.isVerified("a@b.c"))
        assertEquals(3600L, store.ttl["email:verified:a@b.c"])
        assertNull(store.values["email:code:a@b.c"])
        assertNull(store.values["email:attempts:a@b.c"])
        verifications.clearVerification("a@b.c")
        assertFalse(verifications.isVerified("a@b.c"))
    }

    @Test
    fun `코드가 없거나 틀렸을 때__검증하면__만료 또는 불일치로 거부하고 시도 횟수를 센다`() {
        // given
        assertThrows(ExpiredCodeException::class.java) { verifications.verifyCode("a@b.c", "111111") }
        store.values["email:code:a@b.c"] = "123456"
        for (i in 0 until 5) {
            assertThrows(InvalidCodeException::class.java) { verifications.verifyCode("a@b.c", "000000") }
        }

        // when
        val failure = assertThrows(TooManyAttemptsException::class.java) { verifications.verifyCode("a@b.c", "123456") }

        // then
        assertEquals(TooManyAttemptsException.Kind.VERIFY, failure.kind())
        assertEquals("5", store.values["email:attempts:a@b.c"])
        assertEquals(300L, store.ttl["email:attempts:a@b.c"])
    }

    @Test
    fun `비밀번호 재설정일 때__보내고 검증하면__키를 분리해 쓰고 회원가입 인증은 만들지 않는다`() {
        // given
        verifications.sendPasswordResetCode("a@b.c")
        val code = codes[0]
        assertEquals("mail:reset:a@b.c", events[1])
        assertEquals(code, store.values["password:reset:code:a@b.c"])
        assertNull(store.values["email:code:a@b.c"])

        // when
        val verified = verifications.verifyPasswordResetCode("a@b.c", code)

        // then
        assertTrue(verified)
        assertEquals("true", store.values["password:reset:verified:a@b.c"])
        assertFalse(verifications.isVerified("a@b.c"))
        verifications.clearPasswordResetVerification("a@b.c")
        assertNull(store.values["password:reset:verified:a@b.c"])
    }

    private class FakeStore(private val log: MutableList<String>) : VerificationStore {
        val values = HashMap<String, String>()
        val ttl = HashMap<String, Long>()

        override fun tryIncrementSendCount(key: String, maxAttempts: Int, ttlSeconds: Long): Boolean {
            log.add("count:" + key + ":" + maxAttempts + ":" + ttlSeconds)
            val current = Integer.parseInt(values.getOrDefault(key, "0"))
            if (current >= maxAttempts) {
                return false
            }
            values[key] = (current + 1).toString()
            return true
        }

        override fun get(key: String): String? {
            return values[key]
        }

        override fun put(key: String, value: String, ttlSeconds: Long) {
            values[key] = value
            ttl[key] = ttlSeconds
        }

        override fun delete(key: String) {
            values.remove(key)
        }

        override fun incrementWithExpire(key: String, ttlSeconds: Long) {
            values[key] = (Integer.parseInt(values.getOrDefault(key, "0")) + 1).toString()
            ttl[key] = ttlSeconds
        }
    }
}
