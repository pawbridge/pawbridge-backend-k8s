-- Immutable published generations replace ES physical indices and the active alias.
-- These are snapshot documents, not the current animals table: current status is
-- rechecked by Animal Service. Retaining the previous generation permits rollback.
CREATE TABLE lost_gallery_builds (
    build_key varchar(96) PRIMARY KEY,
    metadata jsonb NOT NULL CHECK (jsonb_typeof(metadata) = 'object'),
    expected_count integer NOT NULL CHECK (expected_count BETWEEN 1 AND 100000),
    completed boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE lost_gallery_documents (
    build_key varchar(96) NOT NULL REFERENCES lost_gallery_builds(build_key) ON DELETE CASCADE,
    animal_id bigint NOT NULL CHECK (animal_id > 0),
    species varchar(3) NOT NULL CHECK (species IN ('DOG','CAT')),
    status varchar(32),
    model_version varchar(128) NOT NULL,
    image_vector public.vector(1024) NOT NULL CHECK (public.vector_norm(image_vector) > 0),
    animal_vector public.vector(1024) CHECK (animal_vector IS NULL OR public.vector_norm(animal_vector) > 0),
    document jsonb NOT NULL CHECK (jsonb_typeof(document) = 'object'),
    PRIMARY KEY (build_key, animal_id)
);
CREATE INDEX idx_lost_gallery_eligibility ON lost_gallery_documents(build_key, species, model_version, status);
CREATE TABLE lost_gallery_heads (
    alias varchar(96) PRIMARY KEY,
    build_key varchar(96) REFERENCES lost_gallery_builds(build_key),
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- Exact weighted cosine precedes ANN evaluation; no approximate candidate change here.
