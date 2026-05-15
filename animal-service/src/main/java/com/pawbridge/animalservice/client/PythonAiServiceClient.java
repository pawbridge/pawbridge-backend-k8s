package com.pawbridge.animalservice.client;

import com.pawbridge.animalservice.dto.request.SimilarAnimalRequest;
import com.pawbridge.animalservice.dto.request.PythonChatbotMessageRequest;
import com.pawbridge.animalservice.dto.response.PythonChatbotMessageResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;

/**
 * Python AI Service FeignClient
 * - 이미지 벡터 기반 유사 동물 ID 조회
 */
@FeignClient(
        name = "python-ai-service",
        url = "${python-ai-service.url}"
)
public interface PythonAiServiceClient {

    @PostMapping("/api/v1/animals/similar")
    List<Long> getSimilarAnimals(@RequestBody SimilarAnimalRequest request);

    @PostMapping("/internal/chatbot/messages")
    PythonChatbotMessageResponse createChatbotMessage(
            @RequestHeader("X-Internal-Api-Key") String internalApiKey,
            @RequestBody PythonChatbotMessageRequest request
    );
}
