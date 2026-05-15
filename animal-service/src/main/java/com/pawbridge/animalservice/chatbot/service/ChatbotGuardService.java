package com.pawbridge.animalservice.chatbot.service;

import com.pawbridge.animalservice.chatbot.dto.ChatbotGuardResult;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class ChatbotGuardService {

    private static final int MAX_QUESTION_LENGTH = 500;

    private static final Pattern PHONE_PATTERN = Pattern.compile("(?<!\\d)(01[016789])[-\\s.]?\\d{3,4}[-\\s.]?\\d{4}(?!\\d)");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern RESIDENT_NUMBER_PATTERN = Pattern.compile("\\d{6}[-\\s]?\\d{7}");
    private static final Pattern ACCOUNT_NUMBER_PATTERN = Pattern.compile("(?i)(계좌|account|bank).{0,12}\\d{2,6}[-\\s]?\\d{2,6}[-\\s]?\\d{2,8}");
    private static final Pattern SPECIFIC_ADDRESS_PATTERN = Pattern.compile(".*(\\d{1,5}[-번길\\s]*\\d{0,5}|아파트|빌라|동\\s*\\d{1,4}|호\\s*\\d{1,4}).*");

    private static final List<String> PROMPT_INJECTION_KEYWORDS = List.of(
            "ignore previous",
            "system prompt",
            "developer message",
            "jailbreak",
            "이전 지시 무시",
            "시스템 프롬프트",
            "개발자 메시지",
            "규칙 무시"
    );

    private static final List<String> FORBIDDEN_TOPICS = List.of(
            "코딩",
            "주식",
            "정치",
            "종교",
            "게임",
            "해킹",
            "폭탄",
            "마약",
            "도박"
    );

    private static final List<String> ALLOWED_TOPICS = List.of(
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
            "케어"
    );

    public ChatbotGuardResult inspect(String question) {
        if (question == null || question.isBlank()) {
            return ChatbotGuardResult.blocked(
                    "VALIDATION",
                    "BLANK_QUESTION",
                    "질문을 입력해주세요.",
                    false
            );
        }

        String normalized = question.trim();
        if (normalized.length() > MAX_QUESTION_LENGTH) {
            return ChatbotGuardResult.blocked(
                    "VALIDATION",
                    "QUESTION_TOO_LONG",
                    "질문은 500자 이하로 입력해주세요.",
                    false
            );
        }

        if (containsResidentNumber(normalized) || containsAccountNumber(normalized) || containsSpecificAddress(normalized)) {
            return ChatbotGuardResult.blocked(
                    "PERSONAL_INFO",
                    "SENSITIVE_PERSONAL_INFO",
                    "주민등록번호, 계좌번호, 상세 주소 같은 민감한 개인정보는 입력할 수 없습니다.",
                    true
            );
        }

        if (containsAnyIgnoreCase(normalized, PROMPT_INJECTION_KEYWORDS)) {
            return ChatbotGuardResult.blocked(
                    "PROMPT_INJECTION",
                    "PROMPT_INJECTION_PATTERN",
                    "요청하신 질문은 처리할 수 없습니다. 보호동물 입양과 관련된 질문을 입력해주세요.",
                    false
            );
        }

        if (containsAnyIgnoreCase(normalized, FORBIDDEN_TOPICS) && !containsAnyIgnoreCase(normalized, ALLOWED_TOPICS)) {
            return ChatbotGuardResult.blocked(
                    "OUT_OF_DOMAIN",
                    "FORBIDDEN_TOPIC",
                    "챗봇은 보호동물 입양과 관련된 질문에만 답변할 수 있습니다.",
                    false
            );
        }

        return ChatbotGuardResult.allowed(maskPersonalInfo(normalized));
    }

    private boolean containsResidentNumber(String value) {
        return RESIDENT_NUMBER_PATTERN.matcher(value).find();
    }

    private boolean containsAccountNumber(String value) {
        return ACCOUNT_NUMBER_PATTERN.matcher(value).find();
    }

    private boolean containsSpecificAddress(String value) {
        return SPECIFIC_ADDRESS_PATTERN.matcher(value).matches()
                && (value.contains("주소") || value.contains("사는") || value.contains("거주"));
    }

    private boolean containsAnyIgnoreCase(String value, List<String> keywords) {
        String lower = value.toLowerCase();
        return keywords.stream()
                .map(String::toLowerCase)
                .anyMatch(lower::contains);
    }

    private String maskPersonalInfo(String value) {
        String masked = PHONE_PATTERN.matcher(value).replaceAll("[PHONE]");
        return EMAIL_PATTERN.matcher(masked).replaceAll("[EMAIL]");
    }
}
