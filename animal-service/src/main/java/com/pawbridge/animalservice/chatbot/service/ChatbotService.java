package com.pawbridge.animalservice.chatbot.service;

import com.pawbridge.animalservice.chatbot.dto.AnimalChatbotContext;
import com.pawbridge.animalservice.chatbot.dto.ChatbotGuardResult;
import com.pawbridge.animalservice.chatbot.dto.ChatbotMessageRequest;
import com.pawbridge.animalservice.chatbot.dto.ChatbotMessageResponse;
import com.pawbridge.animalservice.chatbot.dto.ChatbotRecentMessage;
import com.pawbridge.animalservice.chatbot.entity.ChatbotBlockLog;
import com.pawbridge.animalservice.chatbot.entity.ChatbotMessage;
import com.pawbridge.animalservice.chatbot.entity.ChatbotSession;
import com.pawbridge.animalservice.chatbot.repository.ChatbotBlockLogRepository;
import com.pawbridge.animalservice.chatbot.repository.ChatbotMessageRepository;
import com.pawbridge.animalservice.chatbot.repository.ChatbotSessionRepository;
import com.pawbridge.animalservice.client.PythonAiServiceClient;
import com.pawbridge.animalservice.dto.request.PythonChatbotMessageRequest;
import com.pawbridge.animalservice.dto.response.PythonChatbotMessageResponse;
import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.exception.AnimalNotFoundException;
import com.pawbridge.animalservice.repository.AnimalRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ChatbotService {

    private static final int MAX_SESSION_MESSAGES = 20;
    private static final int QUESTION_PREVIEW_MAX_LENGTH = 200;
    private static final String SAFETY_NOTICE = "이 답변은 일반적인 참고 정보이며, 정확한 건강 상태나 치료 판단은 동물병원 또는 수의사에게 확인해 주세요.";

    private final AnimalRepository animalRepository;
    private final ChatbotSessionRepository chatbotSessionRepository;
    private final ChatbotMessageRepository chatbotMessageRepository;
    private final ChatbotBlockLogRepository chatbotBlockLogRepository;
    private final ChatbotCookieService chatbotCookieService;
    private final ChatbotClientIpResolver chatbotClientIpResolver;
    private final ChatbotIpHashService chatbotIpHashService;
    private final ChatbotRateLimitService chatbotRateLimitService;
    private final ChatbotGuardService chatbotGuardService;
    private final PythonAiServiceClient pythonAiServiceClient;

    @Value("${python-ai-service.internal-api-key:}")
    private String pythonAiInternalApiKey;

    public ChatbotMessageResponse createMessage(
            Long animalId,
            ChatbotMessageRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        String anonymousSessionId = chatbotCookieService.resolveAnonymousSessionId(httpRequest, httpResponse);
        String clientIp = chatbotClientIpResolver.resolve(httpRequest);
        String ipHash = chatbotIpHashService.hash(clientIp);

        Animal animal = animalRepository.findWithShelterById(animalId)
                .orElseThrow(AnimalNotFoundException::new);
        ChatbotSession session = resolveSession(request.sessionId(), anonymousSessionId, animal);
        chatbotRateLimitService.check(anonymousSessionId, ipHash);

        if (chatbotMessageRepository.countBySession(session) >= MAX_SESSION_MESSAGES) {
            ChatbotGuardResult guardResult = ChatbotGuardResult.blocked(
                    "RATE_LIMIT",
                    "SESSION_MESSAGE_LIMIT",
                    "이 대화에서 사용할 수 있는 메시지 수를 초과했습니다. 새로고침 후 새 대화를 시작해주세요.",
                    false
            );
            saveBlockLog(animal, anonymousSessionId, ipHash, request.question(), guardResult);
            return blockedResponse(session, guardResult);
        }

        ChatbotGuardResult guardResult = chatbotGuardService.inspect(request.question());
        if (guardResult.blocked()) {
            saveBlockLog(animal, anonymousSessionId, ipHash, request.question(), guardResult);
            return blockedResponse(session, guardResult);
        }

        List<ChatbotRecentMessage> recentMessages = getRecentMessages(session);

        PythonChatbotMessageResponse aiResponse = pythonAiServiceClient.createChatbotMessage(
                pythonAiInternalApiKey,
                new PythonChatbotMessageRequest(
                        toAnimalContext(animal),
                        recentMessages,
                        guardResult.questionForLlm()
                )
        );

        chatbotMessageRepository.saveAll(List.of(
                ChatbotMessage.user(session, guardResult.questionForLlm()),
                ChatbotMessage.assistant(
                        session,
                        aiResponse.answer(),
                        aiResponse.provider(),
                        aiResponse.safetyNotice()
                )
        ));

        return new ChatbotMessageResponse(
                session.getId(),
                aiResponse.answer(),
                aiResponse.safetyNotice(),
                aiResponse.provider()
        );
    }

    private ChatbotMessageResponse blockedResponse(ChatbotSession session, ChatbotGuardResult guardResult) {
        return new ChatbotMessageResponse(
                session.getId(),
                guardResult.userMessage(),
                SAFETY_NOTICE,
                "guard"
        );
    }

    private void saveBlockLog(
            Animal animal,
            String anonymousSessionId,
            String ipHash,
            String question,
            ChatbotGuardResult guardResult
    ) {
        String preview = guardResult.sensitive() ? null : toPreview(question);
        chatbotBlockLogRepository.save(new ChatbotBlockLog(
                animal,
                anonymousSessionId,
                ipHash,
                guardResult.category(),
                guardResult.reason(),
                question != null ? question.length() : 0,
                preview
        ));
    }

    private String toPreview(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        String normalized = question.trim();
        if (normalized.length() <= QUESTION_PREVIEW_MAX_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, QUESTION_PREVIEW_MAX_LENGTH);
    }

    private ChatbotSession resolveSession(String sessionId, String anonymousSessionId, Animal animal) {
        if (sessionId != null && !sessionId.isBlank()) {
            return chatbotSessionRepository.findByIdAndAnonymousSessionIdAndAnimal_Id(
                    sessionId,
                    anonymousSessionId,
                    animal.getId()
            ).orElseThrow(() -> new IllegalArgumentException("Invalid chatbot session"));
        }
        return chatbotSessionRepository.save(new ChatbotSession(anonymousSessionId, animal));
    }

    private List<ChatbotRecentMessage> getRecentMessages(ChatbotSession session) {
        List<ChatbotMessage> messages = new ArrayList<>(
                chatbotMessageRepository.findTop6BySessionOrderByCreatedAtDesc(session)
        );
        Collections.reverse(messages);
        return messages.stream()
                .map(message -> new ChatbotRecentMessage(
                        message.getRole().getApiValue(),
                        message.getContent()
                ))
                .toList();
    }

    private AnimalChatbotContext toAnimalContext(Animal animal) {
        return new AnimalChatbotContext(
                animal.getSpecies() != null ? animal.getSpecies().name() : null,
                animal.getBreed(),
                animal.getBirthYear() != null ? animal.getBirthYear() + "년생" : null,
                animal.getWeight(),
                animal.getColor(),
                animal.getGender() != null ? animal.getGender().name() : null,
                animal.getNeuterStatus() != null ? animal.getNeuterStatus().name() : null,
                animal.getSpecialMark(),
                animal.getApmsProcessState()
        );
    }
}
