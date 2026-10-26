-- Preserve the existing MySQL character repertoire during the rollback window.
-- Source: live information_schema.columns checked on 2026-09-21; exact inventory
-- and isolated coverage are in infrastructure/migration-tests/rollback-charset-columns.json.
-- JSON and utf8mb4 columns remain unrestricted. Flyway history is not reverse-copied;
-- Spring Batch sequence tables become PG sequences and have no text payload.
-- Use C collation so regexp ranges follow code points, not locale sort order.
-- New validated CHECKs fail migration/import on incompatible existing values.
-- Remove these named constraints only through a reviewed follow-up migration after
-- ending the rollback window (or validating the rollback target charset upgrade).

ALTER TABLE animals ADD CONSTRAINT ck_rollback_charset_animals
    CHECK (api_source COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND apms_desertion_no COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND apms_notice_no COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND apms_process_state COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND breed COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND color COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND description COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND gender COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND happen_place COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND image_url COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND image_url2 COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND neuter_status COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND special_mark COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND species COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND status COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND weight COLLATE "C" !~ U&'[\+010000-\+10FFFF]');

ALTER TABLE apms_photo_archive ADD CONSTRAINT ck_rollback_charset_apms_photo_archive
    CHECK (desertion_no COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND source_url COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND state COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND lease_token COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND error_code COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND archived_source_url COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND source_sha256 COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND stored_sha256 COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND object_key COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND content_type COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND recipe COLLATE "C" !~ U&'[\+010000-\+10FFFF]');

ALTER TABLE batch_job_execution ADD CONSTRAINT ck_rollback_charset_batch_job_execution
    CHECK (status COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND exit_code COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND exit_message COLLATE "C" !~ U&'[\+010000-\+10FFFF]');

ALTER TABLE batch_job_execution_context ADD CONSTRAINT ck_rollback_charset_batch_job_execution_context
    CHECK (short_context COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND serialized_context COLLATE "C" !~ U&'[\+010000-\+10FFFF]');

ALTER TABLE batch_job_execution_params ADD CONSTRAINT ck_rollback_charset_batch_job_execution_params
    CHECK (parameter_name COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND parameter_type COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND parameter_value COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND identifying COLLATE "C" !~ U&'[\+010000-\+10FFFF]');

ALTER TABLE batch_job_instance ADD CONSTRAINT ck_rollback_charset_batch_job_instance
    CHECK (job_name COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND job_key COLLATE "C" !~ U&'[\+010000-\+10FFFF]');

ALTER TABLE batch_step_execution ADD CONSTRAINT ck_rollback_charset_batch_step_execution
    CHECK (step_name COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND status COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND exit_code COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND exit_message COLLATE "C" !~ U&'[\+010000-\+10FFFF]');

ALTER TABLE batch_step_execution_context ADD CONSTRAINT ck_rollback_charset_batch_step_execution_context
    CHECK (short_context COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND serialized_context COLLATE "C" !~ U&'[\+010000-\+10FFFF]');

ALTER TABLE chatbot_block_logs ADD CONSTRAINT ck_rollback_charset_chatbot_block_logs
    CHECK (anonymous_session_id COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND ip_hash COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND category COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND reason COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND question_preview COLLATE "C" !~ U&'[\+010000-\+10FFFF]');

ALTER TABLE chatbot_messages ADD CONSTRAINT ck_rollback_charset_chatbot_messages
    CHECK (session_id COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND role COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND content COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND provider COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND safety_notice COLLATE "C" !~ U&'[\+010000-\+10FFFF]');

ALTER TABLE chatbot_sessions ADD CONSTRAINT ck_rollback_charset_chatbot_sessions
    CHECK (id COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND anonymous_session_id COLLATE "C" !~ U&'[\+010000-\+10FFFF]');

ALTER TABLE outbox_events ADD CONSTRAINT ck_rollback_charset_outbox_events
    CHECK (aggregate_id COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND aggregate_type COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND event_id COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND event_type COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND payload COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND topic COLLATE "C" !~ U&'[\+010000-\+10FFFF]');

ALTER TABLE pet_travel_collection_runs ADD CONSTRAINT ck_rollback_charset_pet_travel_collection_runs
    CHECK (id COLLATE "C" !~ U&'[\0080-\+10FFFF]');

ALTER TABLE pet_travel_images ADD CONSTRAINT ck_rollback_charset_pet_travel_images
    CHECK (provider COLLATE "C" !~ U&'[\0080-\+10FFFF]'
       AND content_id COLLATE "C" !~ U&'[\0080-\+10FFFF]'
       AND serial_number COLLATE "C" !~ U&'[\0080-\+10FFFF]'
       AND copyright_type COLLATE "C" !~ U&'[\0080-\+10FFFF]');

ALTER TABLE pet_travel_pet_collection_state ADD CONSTRAINT ck_rollback_charset_pet_travel_pet_collection_state
    CHECK (cycle_id COLLATE "C" !~ U&'[\0080-\+10FFFF]');

ALTER TABLE pet_travel_pet_details ADD CONSTRAINT ck_rollback_charset_pet_travel_pet_details
    CHECK (provider COLLATE "C" !~ U&'[\0080-\+10FFFF]'
       AND content_id COLLATE "C" !~ U&'[\0080-\+10FFFF]'
       AND source COLLATE "C" !~ U&'[\0080-\+10FFFF]'
       AND cycle_id COLLATE "C" !~ U&'[\0080-\+10FFFF]');

ALTER TABLE pet_travel_places ADD CONSTRAINT ck_rollback_charset_pet_travel_places
    CHECK (provider COLLATE "C" !~ U&'[\0080-\+10FFFF]'
       AND content_id COLLATE "C" !~ U&'[\0080-\+10FFFF]'
       AND area_code COLLATE "C" !~ U&'[\0080-\+10FFFF]');

ALTER TABLE pet_travel_regions ADD CONSTRAINT ck_rollback_charset_pet_travel_regions
    CHECK (code COLLATE "C" !~ U&'[\0080-\+10FFFF]');

ALTER TABLE pet_travel_request_budgets ADD CONSTRAINT ck_rollback_charset_pet_travel_request_budgets
    CHECK (operation COLLATE "C" !~ U&'[\0080-\+10FFFF]');

ALTER TABLE pet_travel_targets ADD CONSTRAINT ck_rollback_charset_pet_travel_targets
    CHECK (provider COLLATE "C" !~ U&'[\0080-\+10FFFF]'
       AND content_id COLLATE "C" !~ U&'[\0080-\+10FFFF]'
       AND area_code COLLATE "C" !~ U&'[\0080-\+10FFFF]'
       AND modified_time COLLATE "C" !~ U&'[\0080-\+10FFFF]');

ALTER TABLE pet_travel_visit_details ADD CONSTRAINT ck_rollback_charset_pet_travel_visit_details
    CHECK (provider COLLATE "C" !~ U&'[\0080-\+10FFFF]'
       AND content_id COLLATE "C" !~ U&'[\0080-\+10FFFF]'
       AND content_type_id COLLATE "C" !~ U&'[\0080-\+10FFFF]'
       AND intro_source_modified_time COLLATE "C" !~ U&'[\0080-\+10FFFF]'
       AND info_source_modified_time COLLATE "C" !~ U&'[\0080-\+10FFFF]'
       AND image_source_modified_time COLLATE "C" !~ U&'[\0080-\+10FFFF]'
       AND intro_error COLLATE "C" !~ U&'[\0080-\+10FFFF]'
       AND info_error COLLATE "C" !~ U&'[\0080-\+10FFFF]'
       AND image_error COLLATE "C" !~ U&'[\0080-\+10FFFF]');

ALTER TABLE processed_events ADD CONSTRAINT ck_rollback_charset_processed_events
    CHECK (event_id COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND event_type COLLATE "C" !~ U&'[\+010000-\+10FFFF]');

ALTER TABLE shelters ADD CONSTRAINT ck_rollback_charset_shelters
    CHECK (address COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND adoption_procedure COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND care_reg_no COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND email COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND introduction COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND name COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND operating_hours COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND organization_name COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND owner_name COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND phone COLLATE "C" !~ U&'[\+010000-\+10FFFF]');

ALTER TABLE sync_history ADD CONSTRAINT ck_rollback_charset_sync_history
    CHECK (api_source COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND error_message COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND sync_status COLLATE "C" !~ U&'[\+010000-\+10FFFF]');
