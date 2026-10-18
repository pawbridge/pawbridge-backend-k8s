-- Transient, retryable deletion state. Removed with the member; not a permanent identity tombstone.
CREATE TABLE contact_deletions (
    user_id BIGINT PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
