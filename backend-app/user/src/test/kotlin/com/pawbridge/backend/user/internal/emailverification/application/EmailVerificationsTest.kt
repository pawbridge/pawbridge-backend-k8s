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
    fun given__signup_send__when__sending__then__count_then_mail_then_code_with_five_minutes_and_attempts_reset() {
        store.values["email:attempts:a@b.c"] = "3"

        verifications.sendVerificationCode("a@b.c")

        assertEquals(listOf("count:email:send:count:a@b.c:5:300", "mail:signup:a@b.c"), events)
        val code = codes[0]
        assertTrue(code.matches(Regex("\\d{6}")))
        assertEquals(code, store.values["email:code:a@b.c"])
        assertEquals(300L, store.ttl["email:code:a@b.c"])
        assertNull(store.values["email:attempts:a@b.c"])
    }

    @Test
    fun given__mail_failure__when__sending__then__no_code_is_stored() {
        failMail = true

        assertThrows(EmailSendingException::class.java) { verifications.sendVerificationCode("a@b.c") }

        assertNull(store.values["email:code:a@b.c"])
    }

    @Test
    fun given__five_sends__when__sending_a_sixth__then__too_many_send_attempts() {
        for (i in 0 until 5) {
            verifications.sendVerificationCode("a@b.c")
        }

        val failure = assertThrows(TooManyAttemptsException::class.java) { verifications.sendVerificationCode("a@b.c") }

        assertEquals(TooManyAttemptsException.Kind.SEND, failure.kind())
        assertEquals(5, codes.size)
    }

    @Test
    fun given__matching_code__when__verifying__then__verified_for_one_hour_and_code_and_attempts_removed() {
        store.values["email:code:a@b.c"] = "123456"
        store.values["email:attempts:a@b.c"] = "2"

        assertTrue(verifications.verifyCode("a@b.c", "123456"))

        assertTrue(verifications.isVerified("a@b.c"))
        assertEquals(3600L, store.ttl["email:verified:a@b.c"])
        assertNull(store.values["email:code:a@b.c"])
        assertNull(store.values["email:attempts:a@b.c"])
        verifications.clearVerification("a@b.c")
        assertFalse(verifications.isVerified("a@b.c"))
    }

    @Test
    fun given__missing_or_wrong_code__when__verifying__then__expired_or_invalid_with_attempt_count() {
        assertThrows(ExpiredCodeException::class.java) { verifications.verifyCode("a@b.c", "111111") }
        store.values["email:code:a@b.c"] = "123456"
        for (i in 0 until 5) {
            assertThrows(InvalidCodeException::class.java) { verifications.verifyCode("a@b.c", "000000") }
        }

        val failure = assertThrows(TooManyAttemptsException::class.java) { verifications.verifyCode("a@b.c", "123456") }

        assertEquals(TooManyAttemptsException.Kind.VERIFY, failure.kind())
        assertEquals("5", store.values["email:attempts:a@b.c"])
        assertEquals(300L, store.ttl["email:attempts:a@b.c"])
    }

    @Test
    fun given__password_reset__when__sending_and_verifying__then__separate_keys_and_no_signup_verification() {
        verifications.sendPasswordResetCode("a@b.c")
        val code = codes[0]
        assertEquals("mail:reset:a@b.c", events[1])
        assertEquals(code, store.values["password:reset:code:a@b.c"])
        assertNull(store.values["email:code:a@b.c"])

        assertTrue(verifications.verifyPasswordResetCode("a@b.c", code))

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
