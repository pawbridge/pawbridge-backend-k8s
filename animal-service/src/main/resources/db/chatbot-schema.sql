CREATE TABLE IF NOT EXISTS chatbot_sessions (
    id VARCHAR(36) NOT NULL,
    anonymous_session_id VARCHAR(36) NOT NULL,
    animal_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    INDEX idx_chatbot_sessions_anon_animal (anonymous_session_id, animal_id),
    CONSTRAINT fk_chatbot_sessions_animal
        FOREIGN KEY (animal_id) REFERENCES animals (id)
);

CREATE TABLE IF NOT EXISTS chatbot_messages (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id VARCHAR(36) NOT NULL,
    role VARCHAR(20) NOT NULL,
    content VARCHAR(1000) NOT NULL,
    provider VARCHAR(50) NULL,
    safety_notice VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    INDEX idx_chatbot_messages_session_created (session_id, created_at),
    CONSTRAINT fk_chatbot_messages_session
        FOREIGN KEY (session_id) REFERENCES chatbot_sessions (id)
);

CREATE TABLE IF NOT EXISTS chatbot_block_logs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    animal_id BIGINT NOT NULL,
    anonymous_session_id VARCHAR(36) NOT NULL,
    ip_hash VARCHAR(64) NOT NULL,
    category VARCHAR(50) NOT NULL,
    reason VARCHAR(100) NOT NULL,
    question_length INT NOT NULL,
    question_preview VARCHAR(200) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    INDEX idx_chatbot_block_logs_anon_created (anonymous_session_id, created_at),
    INDEX idx_chatbot_block_logs_ip_created (ip_hash, created_at),
    CONSTRAINT fk_chatbot_block_logs_animal
        FOREIGN KEY (animal_id) REFERENCES animals (id)
);
