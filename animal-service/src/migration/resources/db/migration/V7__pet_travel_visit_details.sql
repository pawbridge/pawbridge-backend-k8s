-- Type-specific visit information and provider-hosted gallery photos.
-- Each resource keeps its own source revision so one failed endpoint does not
-- invalidate or repeatedly refetch the other successful resources.
CREATE TABLE pet_travel_visit_details (
    provider VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_id VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_type_id VARCHAR(2) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    intro_data JSON NULL,
    info_data JSON NULL,
    intro_source_modified_time CHAR(14) CHARACTER SET ascii COLLATE ascii_bin NULL,
    info_source_modified_time CHAR(14) CHARACTER SET ascii COLLATE ascii_bin NULL,
    image_source_modified_time CHAR(14) CHARACTER SET ascii COLLATE ascii_bin NULL,
    intro_fetched_at DATETIME(6) NULL,
    info_fetched_at DATETIME(6) NULL,
    image_fetched_at DATETIME(6) NULL,
    intro_attempted_at DATETIME(6) NULL,
    info_attempted_at DATETIME(6) NULL,
    image_attempted_at DATETIME(6) NULL,
    intro_error VARCHAR(32) CHARACTER SET ascii NULL,
    info_error VARCHAR(32) CHARACTER SET ascii NULL,
    image_error VARCHAR(32) CHARACTER SET ascii NULL,
    PRIMARY KEY (provider, content_id),
    KEY idx_travel_visit_intro (intro_attempted_at, content_id),
    KEY idx_travel_visit_info (info_attempted_at, content_id),
    KEY idx_travel_visit_image (image_attempted_at, content_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE pet_travel_images (
    provider VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_id VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    serial_number VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    image_name VARCHAR(500) NULL,
    original_url VARCHAR(2048) NOT NULL,
    thumbnail_url VARCHAR(2048) NULL,
    copyright_type VARCHAR(16) CHARACTER SET ascii NULL,
    display_order INT NOT NULL,
    fetched_at DATETIME(6) NOT NULL,
    PRIMARY KEY (provider, content_id, serial_number),
    KEY idx_travel_images_display (provider, content_id, display_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
