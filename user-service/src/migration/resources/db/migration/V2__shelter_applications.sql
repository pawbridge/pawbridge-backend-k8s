-- Additive only. Run through the separately approved schema migration process.
CREATE TABLE shelter_applications (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    shelter_name VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    requested_at DATETIME(6) NOT NULL,
    reviewed_by BIGINT NULL,
    reviewed_at DATETIME(6) NULL,
    review_note VARCHAR(1000) NULL,
    care_reg_no VARCHAR(50) NULL,
    pending_user_id BIGINT GENERATED ALWAYS AS (CASE WHEN status = 'PENDING' THEN user_id ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_shelter_application_pending_user (pending_user_id),
    KEY idx_shelter_application_user (user_id, id),
    KEY idx_shelter_application_status (status, id),
    CONSTRAINT chk_shelter_application_status CHECK (status IN ('PENDING','APPROVED','REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
