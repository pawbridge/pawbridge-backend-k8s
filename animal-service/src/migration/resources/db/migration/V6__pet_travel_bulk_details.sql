-- Independent pet conditions: common-detail failures must not block these snapshots.
ALTER TABLE pet_travel_targets
    ADD COLUMN pet_valid_after DATETIME(6) NULL,
    ADD COLUMN pet_changed_at DATETIME(6) NULL;

CREATE TABLE pet_travel_pet_details (
    provider VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_id VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    pet_data JSON NOT NULL,
    source VARCHAR(16) CHARACTER SET ascii NOT NULL,
    fetched_at DATETIME(6) NOT NULL,
    valid_until DATETIME(6) NULL,
    cycle_id CHAR(36) CHARACTER SET ascii NULL,
    PRIMARY KEY (provider, content_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO pet_travel_pet_details(provider,content_id,pet_data,source,fetched_at)
SELECT provider,content_id,pet_data,'LEGACY',published_at FROM pet_travel_places WHERE visible=TRUE;

CREATE TABLE pet_travel_pet_collection_state (
    id TINYINT NOT NULL PRIMARY KEY,
    next_page INT NOT NULL,
    expected_total INT NULL,
    cycle_id CHAR(36) CHARACTER SET ascii NOT NULL,
    completed_at DATETIME(6) NULL,
    error_code VARCHAR(32) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO pet_travel_pet_collection_state(id,next_page,cycle_id) VALUES (1,1,UUID());
