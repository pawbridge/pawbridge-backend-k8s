CREATE TABLE post_search_documents (
    post_id BIGINT PRIMARY KEY REFERENCES posts(post_id) ON DELETE CASCADE,
    title_hash TEXT NOT NULL, content_hash TEXT NOT NULL, analyzer_version VARCHAR(64) NOT NULL,
    tokens TSVECTOR NOT NULL
);
CREATE INDEX idx_post_search_tokens ON post_search_documents USING GIN(tokens);
