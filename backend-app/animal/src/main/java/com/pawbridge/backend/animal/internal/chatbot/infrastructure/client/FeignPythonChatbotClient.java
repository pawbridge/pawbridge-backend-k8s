package com.pawbridge.backend.animal.internal.chatbot.infrastructure.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.client.dto.PythonChatbotMessageRequest;
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.client.dto.PythonChatbotMessageResponse;
import feign.Client;
import feign.Feign;
import feign.Request;
import feign.Retryer;
import feign.codec.EncodeException;
import java.nio.charset.StandardCharsets;

/** Root supplies the explicit endpoint, transport and options; no payload/header logging. */
public final class FeignPythonChatbotClient implements PythonChatbotClient {
    private final PythonChatbotClient delegate;

    public FeignPythonChatbotClient(String pythonUrl, ObjectMapper mapper, Client client, Request.Options options) {
        delegate = Feign.builder().client(client).options(options).retryer(Retryer.NEVER_RETRY)
                .encoder((value, type, template) -> {
                    try { template.body(mapper.writeValueAsBytes(value), StandardCharsets.UTF_8); }
                    catch (JsonProcessingException failure) { throw new EncodeException("Could not encode chatbot request", failure); }
                })
                .decoder((response, type) -> {
                    if (response.body() == null) return null;
                    try (var body = response.body().asInputStream()) {
                        return mapper.readValue(body, mapper.constructType(type));
                    }
                })
                .target(PythonChatbotClient.class, pythonUrl);
    }

    @Override public PythonChatbotMessageResponse createChatbotMessage(String internalApiKey, PythonChatbotMessageRequest request) {
        return delegate.createChatbotMessage(internalApiKey, request);
    }
}
