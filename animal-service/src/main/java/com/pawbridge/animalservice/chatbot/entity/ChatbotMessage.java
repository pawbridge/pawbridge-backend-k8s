package com.pawbridge.animalservice.chatbot.entity;

import com.pawbridge.animalservice.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "chatbot_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatbotMessage extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private ChatbotSession session;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChatbotMessageRole role;

    @Column(nullable = false, length = 1000)
    private String content;

    @Column(length = 50)
    private String provider;

    @Column(length = 500)
    private String safetyNotice;

    private ChatbotMessage(
            ChatbotSession session,
            ChatbotMessageRole role,
            String content,
            String provider,
            String safetyNotice
    ) {
        this.session = session;
        this.role = role;
        this.content = content;
        this.provider = provider;
        this.safetyNotice = safetyNotice;
    }

    public static ChatbotMessage user(ChatbotSession session, String content) {
        return new ChatbotMessage(session, ChatbotMessageRole.USER, content, null, null);
    }

    public static ChatbotMessage assistant(
            ChatbotSession session,
            String content,
            String provider,
            String safetyNotice
    ) {
        return new ChatbotMessage(session, ChatbotMessageRole.ASSISTANT, content, provider, safetyNotice);
    }
}
