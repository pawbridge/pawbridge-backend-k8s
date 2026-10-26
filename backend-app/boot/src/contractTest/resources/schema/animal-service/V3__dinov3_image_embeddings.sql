-- vector must be provisioned in public by the database owner before this migration.
-- DINOv2 384-dimensional vectors are intentionally NOT migrated or padded.
-- These are reusable image features, not a published lost-gallery snapshot.
CREATE TABLE animal_image_embeddings (
    animal_id BIGINT NOT NULL REFERENCES animals(id) ON DELETE CASCADE,
    source_sha256 VARCHAR(64) NOT NULL CHECK (source_sha256 ~ '^[a-f0-9]{64}$'),
    model_version VARCHAR(128) NOT NULL CHECK (length(trim(model_version)) > 0),
    image_vector public.vector(1024) NOT NULL,
    animal_vector public.vector(1024),
    focus_status VARCHAR(64) NOT NULL CHECK (length(trim(focus_status)) > 0),
    coat_color_version VARCHAR(128),
    coat_color JSONB,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (animal_id, source_sha256, model_version),
    CONSTRAINT chk_embedding_color CHECK (
        coat_color IS NULL OR (
            coat_color_version IS NOT NULL AND length(trim(coat_color_version)) > 0
            AND jsonb_typeof(coat_color) = 'object'
        )
    )
);
-- ANN indexes and recommendation scoring follow after exact-search quality validation.
