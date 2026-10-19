\set ON_ERROR_STOP on
\getenv fixture_owner F3_CI_OWNER
BEGIN;
CREATE SCHEMA pawbridge_f16_fixture;
CREATE TABLE pawbridge_f16_fixture.marker(value TEXT NOT NULL, owner TEXT NOT NULL);
INSERT INTO pawbridge_f16_fixture.marker VALUES ('pawbridge-f16-ci-only-v1', :'fixture_owner');
CREATE TABLE pawbridge_community.daily_visit_count (
    visit_date date PRIMARY KEY,
    unique_visitors bigint NOT NULL CHECK (unique_visitors >= 0)
);
GRANT SELECT(visit_date,unique_visitors) ON pawbridge_community.daily_visit_count TO pawbridge_f2_reader;
COMMIT;
