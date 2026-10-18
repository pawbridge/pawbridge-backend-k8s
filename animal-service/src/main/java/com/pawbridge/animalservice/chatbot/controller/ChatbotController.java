package com.pawbridge.animalservice.chatbot.controller;

import com.pawbridge.animalservice.chatbot.dto.ChatbotMessageRequest;
import com.pawbridge.animalservice.chatbot.dto.ChatbotMessageResponse;
import com.pawbridge.animalservice.chatbot.service.ChatbotService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/animals/{animalId}/chat/messages")
@RequiredArgsConstructor
public class ChatbotController {

    private final ChatbotService chatbotService;

    @PostMapping
    public ResponseEntity<ChatbotMessageResponse> createMessage(
            @PathVariable Long animalId,
            @Valid @RequestBody ChatbotMessageRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        return ResponseEntity.ok(chatbotService.createMessage(animalId, request, httpRequest, httpResponse));
    }
}
