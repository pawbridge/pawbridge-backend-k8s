CREATE TABLE animal_reports (
    post_id BIGINT PRIMARY KEY REFERENCES posts(post_id),
    report_kind VARCHAR(16) NOT NULL CHECK (report_kind IN ('MISSING', 'SIGHTING')),
    occurred_on DATE NOT NULL,
    approximate_time VARCHAR(40),
    region VARCHAR(120) NOT NULL,
    landmark VARCHAR(200),
    species VARCHAR(40) NOT NULL,
    animal_name VARCHAR(80),
    coat_color VARCHAR(100),
    animal_size VARCHAR(40),
    distinguishing_features VARCHAR(500),
    direction VARCHAR(200)
);

CREATE INDEX animal_reports_kind_date_idx ON animal_reports(report_kind, occurred_on DESC);
CREATE INDEX posts_report_board_created_idx ON posts(board_type, created_at DESC, post_id DESC)
    WHERE deleted_at IS NULL AND board_type IN ('MISSING', 'REPORT');
