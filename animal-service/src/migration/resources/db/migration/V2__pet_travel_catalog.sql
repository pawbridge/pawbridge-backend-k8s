-- Provider metadata and published snapshots; no photo binaries are stored here.
CREATE TABLE pet_travel_regions (
    code VARCHAR(5) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    fetched_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE pet_travel_collection_state (
    id TINYINT NOT NULL PRIMARY KEY,
    phase VARCHAR(16) NOT NULL,
    next_page INT NOT NULL,
    error_code VARCHAR(32) NULL,
    completed_at DATETIME(6) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO pet_travel_collection_state(id,phase,next_page) VALUES (1,'HIDDEN',1);

CREATE TABLE pet_travel_collection_runs (
    id CHAR(36) CHARACTER SET ascii NOT NULL PRIMARY KEY,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    status VARCHAR(16) NOT NULL,
    requests INT NOT NULL DEFAULT 0,
    discovered INT NOT NULL DEFAULT 0,
    published INT NOT NULL DEFAULT 0,
    error_code VARCHAR(32) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE pet_travel_request_budgets (
    request_day DATE NOT NULL,
    operation VARCHAR(32) CHARACTER SET ascii NOT NULL,
    used INT NOT NULL,
    PRIMARY KEY(request_day,operation)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- A durable work list exists independently of a published place.
CREATE TABLE pet_travel_targets (
    provider VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_id VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    area_code VARCHAR(5) CHARACTER SET ascii COLLATE ascii_bin NULL,
    modified_time CHAR(14) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    shown BOOLEAN NOT NULL,
    image_url VARCHAR(2048) NULL,
    copyright_type VARCHAR(16) NULL,
    generation BIGINT NOT NULL,
    pending BOOLEAN NOT NULL,
    observed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (provider, content_id),
    KEY idx_travel_pending (pending, observed_at, content_id),
    KEY idx_travel_region_pending (area_code, pending)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE pet_travel_places (
    provider VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_id VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    area_code VARCHAR(5) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(500) NOT NULL,
    common_data JSON NOT NULL,
    pet_data JSON NOT NULL,
    image_url VARCHAR(2048) NULL,
    copyright_type VARCHAR(16) NULL,
    visible BOOLEAN NOT NULL,
    published_at DATETIME(6) NOT NULL,
    PRIMARY KEY (provider, content_id),
    KEY idx_travel_public_region (area_code, visible, title, content_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
