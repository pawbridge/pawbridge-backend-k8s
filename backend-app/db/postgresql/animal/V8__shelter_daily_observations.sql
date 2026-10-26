-- Observed database state, not APMS event history or a query cache.
-- No backfill: historical state cannot be reconstructed from today's animals.
CREATE TABLE shelter_daily_observations (
    shelter_id bigint NOT NULL,
    observation_date date NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    protected_count bigint NOT NULL CHECK (protected_count >= 0),
    PRIMARY KEY (shelter_id, observation_date),
    CHECK (observation_date = (observed_at AT TIME ZONE 'Asia/Seoul')::date)
);

-- Preserve observations even if the directory entry is later removed.
CREATE INDEX idx_animals_shelter_protect_intake
    ON animals (shelter_id, happen_date DESC, id) WHERE status = 'PROTECT';
