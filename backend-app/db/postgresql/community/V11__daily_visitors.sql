-- Additive only; run through the standalone migration Job, never API startup.
CREATE TABLE pawbridge_community.daily_visitor (
    visit_date date NOT NULL,
    visitor_hash char(64) NOT NULL,
    PRIMARY KEY (visit_date, visitor_hash)
);
CREATE TABLE pawbridge_community.daily_visit_count (
    visit_date date PRIMARY KEY,
    unique_visitors bigint NOT NULL CHECK (unique_visitors >= 0)
);
CREATE TABLE pawbridge_community.visit_collection (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    started_at timestamptz NOT NULL
);
