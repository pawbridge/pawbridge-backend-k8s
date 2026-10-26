package com.pawbridge.backend.animal.internal.chatbot.infrastructure.client

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.client.dto.PythonChatbotMessageRequest
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.client.dto.PythonChatbotMessageResponse
import feign.Client
import feign.Feign
import feign.Request
import feign.Retryer
import feign.codec.Decoder
import feign.codec.EncodeException
import feign.codec.Encoder
import java.nio.charset.StandardCharsets

/** Root supplies the explicit endpoint, transport and options; no payload/header logging. */
class FeignPythonChatbotClient(
    pythonUrl: String,
    mapper: ObjectMapper,
    client: Client,
    options: Request.Options,
) : PythonChatbotClient {
    private val delegate: PythonChatbotClient = Feign.builder()
        .client(client)
        .options(options)
        .retryer(Retryer.NEVER_RETRY)
        .encoder(
            Encoder { value, _, template ->
                try {
                    template.body(mapper.writeValueAsBytes(value), StandardCharsets.UTF_8)
                } catch (failure: JsonProcessingException) {
                    throw EncodeException("Could not encode chatbot request", failure)
                }
            },
        )
        .decoder(
            Decoder { response, type ->
                if (response.body() == null) {
                    null
                } else {
                    response.body().asInputStream().use { body ->
                        mapper.readValue<Any?>(body, mapper.constructType(type))
                    }
                }
            },
        )
        .target(PythonChatbotClient::class.java, pythonUrl)

    override fun createChatbotMessage(
        internalApiKey: String?,
        request: PythonChatbotMessageRequest?,
    ): PythonChatbotMessageResponse? {
        return delegate.createChatbotMessage(internalApiKey, request)
    }
}
