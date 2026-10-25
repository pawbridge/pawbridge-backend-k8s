package com.pawbridge.backend.animal.internal.chatbot.infrastructure.service

import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.ChatbotGuardResult
import java.util.Locale
import java.util.regex.Pattern

class ChatbotGuardService {

    fun inspect(question: String?): ChatbotGuardResult {
        if (question == null || isBlank(question)) {
            return ChatbotGuardResult.blocked(
                "VALIDATION",
                "BLANK_QUESTION",
                "질문을 입력해주세요.",
                false,
            )
        }

        val normalized = javaTrim(question)
        if (normalized.length > MAX_QUESTION_LENGTH) {
            return ChatbotGuardResult.blocked(
                "VALIDATION",
                "QUESTION_TOO_LONG",
                "질문은 500자 이하로 입력해주세요.",
                false,
            )
        }

        if (containsResidentNumber(normalized) || containsAccountNumber(normalized) || containsSpecificAddress(normalized)) {
            return ChatbotGuardResult.blocked(
                "PERSONAL_INFO",
                "SENSITIVE_PERSONAL_INFO",
                "주민등록번호, 계좌번호, 상세 주소 같은 민감한 개인정보는 입력할 수 없습니다.",
                true,
            )
        }

        if (containsAnyIgnoreCase(normalized, PROMPT_INJECTION_KEYWORDS)) {
            return ChatbotGuardResult.blocked(
                "PROMPT_INJECTION",
                "PROMPT_INJECTION_PATTERN",
                "요청하신 질문은 처리할 수 없습니다. 보호동물 입양과 관련된 질문을 입력해주세요.",
                false,
            )
        }

        if (containsAnyIgnoreCase(normalized, FORBIDDEN_TOPICS) && !containsAnyIgnoreCase(normalized, ALLOWED_TOPICS)) {
            return ChatbotGuardResult.blocked(
                "OUT_OF_DOMAIN",
                "FORBIDDEN_TOPIC",
                "챗봇은 보호동물 입양과 관련된 질문에만 답변할 수 있습니다.",
                false,
            )
        }

        return ChatbotGuardResult.allowed(maskPersonalInfo(normalized))
    }

    private fun containsResidentNumber(value: String): Boolean {
        return RESIDENT_NUMBER_PATTERN.matcher(value).find()
    }

    private fun containsAccountNumber(value: String): Boolean {
        return ACCOUNT_NUMBER_PATTERN.matcher(value).find()
    }

    private fun containsSpecificAddress(value: String): Boolean {
        return SPECIFIC_ADDRESS_PATTERN.matcher(value).matches() &&
            (value.contains("주소") || value.contains("사는") || value.contains("거주"))
    }

    private fun containsAnyIgnoreCase(value: String, keywords: List<String>): Boolean {
        // Java String.toLowerCase() uses the default locale.
        val lower = value.lowercase(Locale.getDefault())
        return keywords
            .map { keyword -> keyword.lowercase(Locale.getDefault()) }
            .any { keyword -> lower.contains(keyword) }
    }

    private fun maskPersonalInfo(value: String): String {
        val masked = PHONE_PATTERN.matcher(value).replaceAll("[PHONE]")
        return EMAIL_PATTERN.matcher(masked).replaceAll("[EMAIL]")
    }

    // Java String.isBlank(): empty or only Character.isWhitespace characters (Kotlin isBlank also accepts NBSP).
    private fun isBlank(value: String): Boolean = value.all { Character.isWhitespace(it) }

    // Java String.trim() removes every character up to U+0020; Kotlin trim() uses Character.isWhitespace.
    private fun javaTrim(value: String): String = value.trim { it <= ' ' }

    private companion object {
        private const val MAX_QUESTION_LENGTH = 500

        private val PHONE_PATTERN: Pattern =
            Pattern.compile("(?<!\\d)(01[016789])[-\\s.]?\\d{3,4}[-\\s.]?\\d{4}(?!\\d)")
        private val EMAIL_PATTERN: Pattern = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
        private val RESIDENT_NUMBER_PATTERN: Pattern = Pattern.compile("\\d{6}[-\\s]?\\d{7}")
        private val ACCOUNT_NUMBER_PATTERN: Pattern =
            Pattern.compile("(?i)(계좌|account|bank).{0,12}\\d{2,6}[-\\s]?\\d{2,6}[-\\s]?\\d{2,8}")
        private val SPECIFIC_ADDRESS_PATTERN: Pattern =
            Pattern.compile(".*(\\d{1,5}[-번길\\s]*\\d{0,5}|아파트|빌라|동\\s*\\d{1,4}|호\\s*\\d{1,4}).*")

        private val PROMPT_INJECTION_KEYWORDS: List<String> = java.util.List.of(
            "ignore previous",
            "system prompt",
            "developer message",
            "jailbreak",
            "이전 지시 무시",
            "시스템 프롬프트",
            "개발자 메시지",
            "규칙 무시",
        )

        private val FORBIDDEN_TOPICS: List<String> = java.util.List.of(
            "코딩",
            "주식",
            "정치",
            "종교",
            "게임",
            "해킹",
            "폭탄",
            "마약",
            "도박",
        )

        private val ALLOWED_TOPICS: List<String> = java.util.List.of(
            "입양",
            "보호",
            "동물",
            "강아지",
            "고양이",
            "품종",
            "중성화",
            "접종",
            "산책",
            "사료",
            "보호소",
            "문의",
            "공고",
            "돌봄",
            "케어",
        )
    }
}
