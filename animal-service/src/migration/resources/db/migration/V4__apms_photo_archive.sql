CREATE TABLE apms_photo_scan (
  id TINYINT PRIMARY KEY,
  new_cursor BIGINT NOT NULL,
  sweep_cursor BIGINT NOT NULL
);

CREATE TABLE apms_photo_archive (
  animal_id BIGINT NOT NULL,
  slot TINYINT NOT NULL,
  desertion_no VARCHAR(50) NOT NULL,
  source_url VARCHAR(500) NULL,
  generation BIGINT NOT NULL DEFAULT 1,
  state VARCHAR(16) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  lease_token VARCHAR(36) NULL,
  lease_until DATETIME(6) NULL,
  error_code VARCHAR(64) NULL,
  archived_source_url VARCHAR(500) NULL,
  source_sha256 CHAR(64) NULL,
  stored_sha256 CHAR(64) NULL,
  object_key VARCHAR(200) NULL,
  content_type VARCHAR(32) NULL,
  stored_bytes INT NULL,
  width INT NULL,
  height INT NULL,
  recipe VARCHAR(64) NULL,
  archived_at DATETIME(6) NULL,
  PRIMARY KEY (animal_id, slot),
  KEY idx_photo_due (state, next_attempt_at),
  KEY idx_photo_lease (state, lease_until),
  CONSTRAINT fk_photo_animal FOREIGN KEY (animal_id) REFERENCES animals(id) ON DELETE CASCADE,
  CONSTRAINT chk_photo_slot CHECK (slot IN (1,2))
);
