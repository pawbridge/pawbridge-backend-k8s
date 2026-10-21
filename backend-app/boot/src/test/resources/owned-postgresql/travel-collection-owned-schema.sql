-- NEW synthetic owned travel-collection database only. Root reviews and executes.
-- Original immutable dev 21484bc02ea4b06da88ebe30ad2aae2d7fa7a572.
-- Travel PostgreSQL V1 folds legacy travel V2/V3/V6/V7; PG V2 is exact Batch 5.2.4.
\set ON_ERROR_STOP on
SELECT current_database() = :'owned_database'
   AND :'owned_database' ~ '^pb_travel_collection_owned_[0-9a-f]{12}$'
   AND :'owned_owner' = replace(:'owned_database','pb_travel_collection_owned_','pb_travel_collection_owner_')
   AND :'owned_writer' = replace(:'owned_database','pb_travel_collection_owned_','pb_travel_collection_writer_') AS target_ok \gset
\if :target_ok
\else
\quit 41
\endif
BEGIN;
SET LOCAL ROLE :"owned_owner";
CREATE SCHEMA pawbridge_animal AUTHORIZATION :"owned_owner";
SET LOCAL search_path = pawbridge_animal, pg_catalog;
-- Provider metadata and published snapshots; no photo binaries are stored here.
CREATE TABLE pet_travel_regions (
    code VARCHAR(5) NOT NULL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    fetched_at timestamp(6) without time zone NOT NULL,
    completed_at timestamp(6) without time zone NULL
);

CREATE TABLE pet_travel_collection_state (
    id SMALLINT NOT NULL PRIMARY KEY,
    phase VARCHAR(16) NOT NULL,
    next_page INT NOT NULL,
    error_code VARCHAR(32) NULL,
    completed_at timestamp(6) without time zone NULL
);
INSERT INTO pet_travel_collection_state(id,phase,next_page) VALUES (1,'HIDDEN',1);

CREATE TABLE pet_travel_collection_runs (
    id CHAR(36) NOT NULL PRIMARY KEY,
    started_at timestamp(6) without time zone NOT NULL,
    finished_at timestamp(6) without time zone NULL,
    status VARCHAR(16) NOT NULL,
    requests INT NOT NULL DEFAULT 0,
    discovered INT NOT NULL DEFAULT 0,
    published INT NOT NULL DEFAULT 0,
    error_code VARCHAR(32) NULL
);

CREATE TABLE pet_travel_request_budgets (
    request_day DATE NOT NULL,
    operation VARCHAR(32) NOT NULL,
    used INT NOT NULL,
    PRIMARY KEY(request_day,operation)
);

-- A durable work list exists independently of a published place.
CREATE TABLE pet_travel_targets (
    provider VARCHAR(32) NOT NULL,
    content_id VARCHAR(20) NOT NULL,
    area_code VARCHAR(5) NULL,
    modified_time CHAR(14) NOT NULL,
    shown BOOLEAN NOT NULL,
    image_url VARCHAR(2048) NULL,
    copyright_type VARCHAR(16) NULL,
    generation BIGINT NOT NULL,
    pending BOOLEAN NOT NULL,
    observed_at timestamp(6) without time zone NOT NULL,
    PRIMARY KEY (provider, content_id)
);
CREATE INDEX idx_travel_pending ON pet_travel_targets (pending, observed_at, content_id);
CREATE INDEX idx_travel_region_pending ON pet_travel_targets (area_code, pending);

CREATE TABLE pet_travel_places (
    provider VARCHAR(32) NOT NULL,
    content_id VARCHAR(20) NOT NULL,
    area_code VARCHAR(5) NOT NULL,
    title VARCHAR(500) NOT NULL,
    common_data JSONB NOT NULL,
    pet_data JSONB NOT NULL,
    image_url VARCHAR(2048) NULL,
    copyright_type VARCHAR(16) NULL,
    visible BOOLEAN NOT NULL,
    published_at timestamp(6) without time zone NOT NULL,
    PRIMARY KEY (provider, content_id)
);
CREATE INDEX idx_travel_public_region ON pet_travel_places (area_code, visible, title, content_id);

-- Keep V2 snapshots intact; discovery can now serve basic data before detail enrichment.
ALTER TABLE pet_travel_targets
    ADD COLUMN basic_data JSONB NULL,
    ADD COLUMN basic_fetched_at timestamp(6) without time zone NULL,
    ADD COLUMN detail_attempted_at timestamp(6) without time zone NULL,
    ADD COLUMN detail_error VARCHAR(32) NULL;

-- Old DETAILS checkpoints must not prevent future discovery scans.
UPDATE pet_travel_collection_state SET phase='HIDDEN',next_page=1 WHERE phase='DETAILS';


-- Independent pet conditions: common-detail failures must not block these snapshots.
ALTER TABLE pet_travel_targets
    ADD COLUMN pet_valid_after timestamp(6) without time zone NULL,
    ADD COLUMN pet_changed_at timestamp(6) without time zone NULL;

CREATE TABLE pet_travel_pet_details (
    provider VARCHAR(32) NOT NULL,
    content_id VARCHAR(20) NOT NULL,
    pet_data JSONB NOT NULL,
    source VARCHAR(16) NOT NULL,
    fetched_at timestamp(6) without time zone NOT NULL,
    valid_until timestamp(6) without time zone NULL,
    cycle_id CHAR(36) NULL,
    PRIMARY KEY (provider, content_id)
);

INSERT INTO pet_travel_pet_details(provider,content_id,pet_data,source,fetched_at)
SELECT provider,content_id,pet_data,'LEGACY',published_at FROM pet_travel_places WHERE visible=TRUE;

CREATE TABLE pet_travel_pet_collection_state (
    id SMALLINT NOT NULL PRIMARY KEY,
    next_page INT NOT NULL,
    expected_total INT NULL,
    cycle_id CHAR(36) NOT NULL,
    completed_at timestamp(6) without time zone NULL,
    error_code VARCHAR(32) NULL
);
INSERT INTO pet_travel_pet_collection_state(id,next_page,cycle_id) VALUES (1,1,gen_random_uuid()::text);

-- Type-specific visit information and provider-hosted gallery photos.
-- Each resource keeps its own source revision so one failed endpoint does not
-- invalidate or repeatedly refetch the other successful resources.
CREATE TABLE pet_travel_visit_details (
    provider VARCHAR(32) NOT NULL,
    content_id VARCHAR(20) NOT NULL,
    content_type_id VARCHAR(2) NOT NULL,
    intro_data JSONB NULL,
    info_data JSONB NULL,
    intro_source_modified_time CHAR(14) NULL,
    info_source_modified_time CHAR(14) NULL,
    image_source_modified_time CHAR(14) NULL,
    intro_fetched_at timestamp(6) without time zone NULL,
    info_fetched_at timestamp(6) without time zone NULL,
    image_fetched_at timestamp(6) without time zone NULL,
    intro_attempted_at timestamp(6) without time zone NULL,
    info_attempted_at timestamp(6) without time zone NULL,
    image_attempted_at timestamp(6) without time zone NULL,
    intro_error VARCHAR(32) NULL,
    info_error VARCHAR(32) NULL,
    image_error VARCHAR(32) NULL,
    PRIMARY KEY (provider, content_id)
);
CREATE INDEX idx_travel_visit_intro ON pet_travel_visit_details (intro_attempted_at, content_id);
CREATE INDEX idx_travel_visit_info ON pet_travel_visit_details (info_attempted_at, content_id);
CREATE INDEX idx_travel_visit_image ON pet_travel_visit_details (image_attempted_at, content_id);

CREATE TABLE pet_travel_images (
    provider VARCHAR(32) NOT NULL,
    content_id VARCHAR(20) NOT NULL,
    serial_number VARCHAR(32) NOT NULL,
    image_name VARCHAR(500) NULL,
    original_url VARCHAR(2048) NOT NULL,
    thumbnail_url VARCHAR(2048) NULL,
    copyright_type VARCHAR(16) NULL,
    display_order INT NOT NULL,
    fetched_at timestamp(6) without time zone NOT NULL,
    PRIMARY KEY (provider, content_id, serial_number)
);
CREATE INDEX idx_travel_images_display ON pet_travel_images (provider, content_id, display_order);

-- Spring Batch 5.2.4 PostgreSQL schema (Apache-2.0), matching the service BOM.
-- PostgreSQL sequences replace the MySQL sequence-emulation tables.
-- Import of existing Batch IDs/counters is a separate, guarded migration step.
-- Autogenerated: do not edit this file

CREATE TABLE BATCH_JOB_INSTANCE  (
	JOB_INSTANCE_ID BIGINT  NOT NULL PRIMARY KEY ,
	VERSION BIGINT ,
	JOB_NAME VARCHAR(100) NOT NULL,
	JOB_KEY VARCHAR(32) NOT NULL,
	constraint JOB_INST_UN unique (JOB_NAME, JOB_KEY)
) ;

CREATE TABLE BATCH_JOB_EXECUTION  (
	JOB_EXECUTION_ID BIGINT  NOT NULL PRIMARY KEY ,
	VERSION BIGINT  ,
	JOB_INSTANCE_ID BIGINT NOT NULL,
	CREATE_TIME TIMESTAMP NOT NULL,
	START_TIME TIMESTAMP DEFAULT NULL ,
	END_TIME TIMESTAMP DEFAULT NULL ,
	STATUS VARCHAR(10) ,
	EXIT_CODE VARCHAR(2500) ,
	EXIT_MESSAGE VARCHAR(2500) ,
	LAST_UPDATED TIMESTAMP,
	constraint JOB_INST_EXEC_FK foreign key (JOB_INSTANCE_ID)
	references BATCH_JOB_INSTANCE(JOB_INSTANCE_ID)
) ;

CREATE TABLE BATCH_JOB_EXECUTION_PARAMS  (
	JOB_EXECUTION_ID BIGINT NOT NULL ,
	PARAMETER_NAME VARCHAR(100) NOT NULL ,
	PARAMETER_TYPE VARCHAR(100) NOT NULL ,
	PARAMETER_VALUE VARCHAR(2500) ,
	IDENTIFYING CHAR(1) NOT NULL ,
	constraint JOB_EXEC_PARAMS_FK foreign key (JOB_EXECUTION_ID)
	references BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
) ;

CREATE TABLE BATCH_STEP_EXECUTION  (
	STEP_EXECUTION_ID BIGINT  NOT NULL PRIMARY KEY ,
	VERSION BIGINT NOT NULL,
	STEP_NAME VARCHAR(100) NOT NULL,
	JOB_EXECUTION_ID BIGINT NOT NULL,
	CREATE_TIME TIMESTAMP NOT NULL,
	START_TIME TIMESTAMP DEFAULT NULL ,
	END_TIME TIMESTAMP DEFAULT NULL ,
	STATUS VARCHAR(10) ,
	COMMIT_COUNT BIGINT ,
	READ_COUNT BIGINT ,
	FILTER_COUNT BIGINT ,
	WRITE_COUNT BIGINT ,
	READ_SKIP_COUNT BIGINT ,
	WRITE_SKIP_COUNT BIGINT ,
	PROCESS_SKIP_COUNT BIGINT ,
	ROLLBACK_COUNT BIGINT ,
	EXIT_CODE VARCHAR(2500) ,
	EXIT_MESSAGE VARCHAR(2500) ,
	LAST_UPDATED TIMESTAMP,
	constraint JOB_EXEC_STEP_FK foreign key (JOB_EXECUTION_ID)
	references BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
) ;

CREATE TABLE BATCH_STEP_EXECUTION_CONTEXT  (
	STEP_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
	SHORT_CONTEXT VARCHAR(2500) NOT NULL,
	SERIALIZED_CONTEXT TEXT ,
	constraint STEP_EXEC_CTX_FK foreign key (STEP_EXECUTION_ID)
	references BATCH_STEP_EXECUTION(STEP_EXECUTION_ID)
) ;

CREATE TABLE BATCH_JOB_EXECUTION_CONTEXT  (
	JOB_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
	SHORT_CONTEXT VARCHAR(2500) NOT NULL,
	SERIALIZED_CONTEXT TEXT ,
	constraint JOB_EXEC_CTX_FK foreign key (JOB_EXECUTION_ID)
	references BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
) ;

CREATE SEQUENCE BATCH_STEP_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE BATCH_JOB_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE BATCH_JOB_SEQ MAXVALUE 9223372036854775807 NO CYCLE;

REVOKE ALL ON ALL TABLES IN SCHEMA pawbridge_animal FROM PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA pawbridge_animal FROM PUBLIC;
REVOKE ALL ON SCHEMA pawbridge_animal FROM PUBLIC;
GRANT USAGE ON SCHEMA pawbridge_animal, public TO :"owned_writer";
GRANT SELECT ON public.pb_travel_collection_fixture TO :"owned_writer";
GRANT SELECT, UPDATE ON pet_travel_collection_state, pet_travel_pet_collection_state TO :"owned_writer";
GRANT SELECT, INSERT, UPDATE ON pet_travel_regions, pet_travel_collection_runs,
    pet_travel_request_budgets, pet_travel_targets, pet_travel_places,
    pet_travel_pet_details, pet_travel_visit_details TO :"owned_writer";
GRANT SELECT, INSERT, DELETE ON pet_travel_images TO :"owned_writer";
GRANT SELECT, INSERT ON batch_job_instance, batch_job_execution_params TO :"owned_writer";
GRANT SELECT, INSERT, UPDATE ON batch_job_execution, batch_step_execution,
    batch_job_execution_context, batch_step_execution_context TO :"owned_writer";
GRANT USAGE ON SEQUENCE batch_job_seq, batch_job_execution_seq, batch_step_execution_seq TO :"owned_writer";
COMMIT;
