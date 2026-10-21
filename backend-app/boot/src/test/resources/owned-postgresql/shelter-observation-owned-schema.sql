-- P23: three reduced original-column tables, plus the helper-created private marker.
-- Root supplies owned_database, owned_owner, owned_runtime and owned_marker once.
BEGIN;
SET LOCAL ROLE :"owned_owner";
SELECT set_config('pawbridge.owned_database', :'owned_database', true),
       set_config('pawbridge.owned_marker', :'owned_marker', true);
DO $$ BEGIN
 IF current_database() <> current_setting('pawbridge.owned_database')
    OR current_database() !~ '^pb_shelter_obs_owned_[0-9a-f]{12}$'
 THEN RAISE EXCEPTION 'Owned shelter observation database mismatch'; END IF;
 IF (SELECT count(*) FROM public.pb_shelter_observation_fixture) <> 1
    OR NOT EXISTS (SELECT 1 FROM public.pb_shelter_observation_fixture WHERE marker=current_setting('pawbridge.owned_marker'))
 THEN RAISE EXCEPTION 'Owned shelter observation marker mismatch'; END IF;
END $$;
CREATE SCHEMA pawbridge_animal;
REVOKE ALL ON SCHEMA pawbridge_animal FROM PUBLIC;
SET LOCAL search_path=pawbridge_animal,public;

-- Only existing columns consumed by the actual recorder/observations reader.
CREATE TABLE shelters (id bigint PRIMARY KEY);
CREATE TABLE animals (
    id bigint PRIMARY KEY,
    shelter_id bigint REFERENCES shelters(id),
    status varchar(20) NOT NULL
);

-- Exact original V8 table block; observations deliberately have no Shelter FK.
CREATE TABLE shelter_daily_observations (
    shelter_id bigint NOT NULL,
    observation_date date NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    protected_count bigint NOT NULL CHECK (protected_count >= 0),
    PRIMARY KEY (shelter_id, observation_date),
    CHECK (observation_date = (observed_at AT TIME ZONE 'Asia/Seoul')::date)
);

INSERT INTO shelters(id) VALUES (101),(102),(103);
INSERT INTO animals(id,shelter_id,status) VALUES
 (201,102,'PROTECT'),(202,102,'PROTECT'),(203,102,'ADOPTED'),
 (204,102,'RETURNED'),(205,102,'NOTICE'),(206,103,'PROTECT');
-- The existing first observation intentionally differs from the current animal count.
INSERT INTO shelter_daily_observations(shelter_id,observation_date,observed_at,protected_count) VALUES
 (103,(statement_timestamp() AT TIME ZONE 'Asia/Seoul')::date,
      ((statement_timestamp() AT TIME ZONE 'Asia/Seoul')::date::timestamp AT TIME ZONE 'Asia/Seoul'),17),
 (102,(statement_timestamp() AT TIME ZONE 'Asia/Seoul')::date-1,statement_timestamp()-interval '1 day',8);

REVOKE ALL ON ALL TABLES IN SCHEMA pawbridge_animal FROM PUBLIC;
GRANT USAGE ON SCHEMA public,pawbridge_animal TO :"owned_runtime";
GRANT SELECT ON shelters,animals,shelter_daily_observations TO :"owned_runtime";
GRANT INSERT(shelter_id,observation_date,observed_at,protected_count) ON shelter_daily_observations TO :"owned_runtime";
GRANT SELECT ON public.pb_shelter_observation_fixture TO :"owned_runtime";
COMMIT;
