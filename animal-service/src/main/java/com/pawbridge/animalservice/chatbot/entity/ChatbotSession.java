package com.pawbridge.animalservice.chatbot.entity;

import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "chatbot_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatbotSession extends BaseTimeEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 36)
    private String anonymousSessionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "animal_id", nullable = false)
    private Animal animal;

    public ChatbotSession(String anonymousSessionId, Animal animal) {
        this.anonymousSessionId = anonymousSessionId;
        this.animal = animal;
    }

    @PrePersist
    void generateId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }
}
