package com.pawbridge.animalservice.chatbot.service;

import com.pawbridge.animalservice.chatbot.dto.AnimalChatbotContext;
import com.pawbridge.animalservice.chatbot.dto.ChatbotMessageRequest;
import com.pawbridge.animalservice.chatbot.dto.ChatbotMessageResponse;
import com.pawbridge.animalservice.chatbot.dto.ChatbotRecentMessage;
import com.pawbridge.animalservice.chatbot.entity.ChatbotMessage;
import com.pawbridge.animalservice.chatbot.entity.ChatbotSession;
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

    private final AnimalRepository animalRepository;
    private final ChatbotSessionRepository chatbotSessionRepository;
    private final ChatbotMessageRepository chatbotMessageRepository;
    private final ChatbotCookieService chatbotCookieService;
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
        Animal animal = animalRepository.findWithShelterById(animalId)
                .orElseThrow(AnimalNotFoundException::new);
        ChatbotSession session = resolveSession(request.sessionId(), anonymousSessionId, animal);
        List<ChatbotRecentMessage> recentMessages = getRecentMessages(session);

        PythonChatbotMessageResponse aiResponse = pythonAiServiceClient.createChatbotMessage(
                pythonAiInternalApiKey,
                new PythonChatbotMessageRequest(
                        toAnimalContext(animal),
                        recentMessages,
                        request.question()
                )
        );

        chatbotMessageRepository.saveAll(List.of(
                ChatbotMessage.user(session, request.question()),
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
