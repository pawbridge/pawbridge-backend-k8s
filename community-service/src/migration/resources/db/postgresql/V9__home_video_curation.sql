-- User-selected IDs and publication settings are retained separately from expiring YouTube metadata.
CREATE TABLE pawbridge_community.home_video_board (
    id smallint PRIMARY KEY CHECK (id = 1),
    revision bigint NOT NULL DEFAULT 0
);
INSERT INTO pawbridge_community.home_video_board(id) VALUES (1);
CREATE TABLE pawbridge_community.home_videos (
    id uuid PRIMARY KEY,
    video_id varchar(11) NOT NULL UNIQUE CHECK (video_id ~ '^[A-Za-z0-9_-]{11}$'),
    published boolean NOT NULL DEFAULT false,
    position integer NOT NULL,
    created_at timestamptz NOT NULL,
    title varchar(500),
    channel_title varchar(300),
    thumbnail_url varchar(2048),
    duration_seconds bigint,
    available boolean NOT NULL DEFAULT false,
    checked_at timestamptz
);
CREATE INDEX idx_home_video_refresh ON pawbridge_community.home_videos(checked_at);
