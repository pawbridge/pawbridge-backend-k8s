package com.pawbridge.animalservice.chatbot.entity;

import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "chatbot_block_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatbotBlockLog extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "animal_id", nullable = false)
    private Animal animal;

    @Column(nullable = false, length = 36)
    private String anonymousSessionId;

    @Column(nullable = false, length = 64)
    private String ipHash;

    @Column(nullable = false, length = 50)
    private String category;

    @Column(nullable = false, length = 100)
    private String reason;

    @Column(nullable = false)
    private Integer questionLength;

    @Column(length = 200)
    private String questionPreview;

    public ChatbotBlockLog(
            Animal animal,
            String anonymousSessionId,
            String ipHash,
            String category,
            String reason,
            int questionLength,
            String questionPreview
    ) {
        this.animal = animal;
        this.anonymousSessionId = anonymousSessionId;
        this.ipHash = ipHash;
        this.category = category;
        this.reason = reason;
        this.questionLength = questionLength;
        this.questionPreview = questionPreview;
    }
}
