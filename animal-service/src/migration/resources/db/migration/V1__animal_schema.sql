-- Initial animal schema: live SHOW CREATE TABLE snapshot, 2026-09-11.
-- Preserve existing utf8mb3, enum values, indexes and constraint names.
-- Live AUTO_INCREMENT counters and application rows are intentionally excluded.
-- Existing databases must be explicitly baselined at V1 after equivalence review.

CREATE TABLE `shelters` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `address` varchar(500) DEFAULT NULL,
  `adoption_procedure` varchar(2000) DEFAULT NULL,
  `care_reg_no` varchar(50) NOT NULL,
  `email` varchar(100) DEFAULT NULL,
  `introduction` varchar(2000) DEFAULT NULL,
  `name` varchar(200) NOT NULL,
  `operating_hours` varchar(200) DEFAULT NULL,
  `organization_name` varchar(200) DEFAULT NULL,
  `owner_name` varchar(100) DEFAULT NULL,
  `phone` varchar(50) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_care_reg_no` (`care_reg_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;
CREATE TABLE `animals` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `api_source` enum('APMS_ANIMAL','GYEONGGI','MANUAL','UNKNOWN') NOT NULL,
  `apms_desertion_no` varchar(50) DEFAULT NULL,
  `apms_notice_no` varchar(100) NOT NULL,
  `apms_process_state` varchar(50) DEFAULT NULL,
  `apms_updated_at` datetime(6) DEFAULT NULL,
  `birth_year` int DEFAULT NULL,
  `breed` varchar(100) DEFAULT NULL,
  `color` varchar(100) DEFAULT NULL,
  `description` varchar(2000) DEFAULT NULL,
  `favorite_count` int NOT NULL,
  `gender` enum('FEMALE','MALE','UNKNOWN') NOT NULL,
  `happen_date` date DEFAULT NULL,
  `happen_place` varchar(200) DEFAULT NULL,
  `image_url` varchar(500) DEFAULT NULL,
  `image_url2` varchar(500) DEFAULT NULL,
  `neuter_status` enum('NO','UNKNOWN','YES') NOT NULL,
  `notice_end_date` date NOT NULL,
  `notice_start_date` date NOT NULL,
  `special_mark` varchar(1000) DEFAULT NULL,
  `species` enum('CAT','DOG','ETC') NOT NULL,
  `status` enum('ADOPTED','ADOPTION_PENDING','DONATED','ESCAPED','EUTHANIZED','NATURAL_DEATH','NOTICE','PROTECT','RELEASED','RETURNED','UNKNOWN') NOT NULL,
  `weight` varchar(50) DEFAULT NULL,
  `shelter_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_apms_desertion_no` (`apms_desertion_no`),
  KEY `idx_species_status` (`species`,`status`),
  KEY `idx_notice_end_date` (`notice_end_date`),
  KEY `idx_shelter_id` (`shelter_id`),
  KEY `idx_apms_notice_no` (`apms_notice_no`),
  CONSTRAINT `FK7fmlpw3o4ourhtv3qy8gl6cn5` FOREIGN KEY (`shelter_id`) REFERENCES `shelters` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;
CREATE TABLE `sync_history` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `api_source` enum('APMS_ANIMAL','GYEONGGI','MANUAL','UNKNOWN') NOT NULL,
  `end_time` datetime(6) DEFAULT NULL,
  `error_message` text,
  `fail_count` int NOT NULL,
  `start_time` datetime(6) NOT NULL,
  `success_count` int NOT NULL,
  `sync_status` enum('FAIL','IN_PROGRESS','PARTIAL_SUCCESS','SUCCESS') NOT NULL,
  `total_count` int DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;
CREATE TABLE `processed_events` (
  `event_id` varchar(50) NOT NULL,
  `event_type` varchar(50) NOT NULL,
  `processed_at` datetime(6) NOT NULL,
  PRIMARY KEY (`event_id`),
  KEY `idx_processed_at` (`processed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;
CREATE TABLE `outbox_events` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `aggregate_id` varchar(50) NOT NULL,
  `aggregate_type` varchar(50) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `event_id` varchar(50) NOT NULL,
  `event_type` varchar(50) NOT NULL,
  `payload` text NOT NULL,
  `topic` varchar(100) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK7ba1uqwbn85u1g6jg4ja1tk6k` (`event_id`),
  KEY `idx_outbox_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;
CREATE TABLE `chatbot_sessions` (
  `id` varchar(36) NOT NULL,
  `anonymous_session_id` varchar(36) NOT NULL,
  `animal_id` bigint NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_chatbot_sessions_anon_animal` (`anonymous_session_id`,`animal_id`),
  KEY `fk_chatbot_sessions_animal` (`animal_id`),
  CONSTRAINT `fk_chatbot_sessions_animal` FOREIGN KEY (`animal_id`) REFERENCES `animals` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;
CREATE TABLE `chatbot_messages` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` varchar(36) NOT NULL,
  `role` varchar(20) NOT NULL,
  `content` varchar(1000) NOT NULL,
  `provider` varchar(50) DEFAULT NULL,
  `safety_notice` varchar(500) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_chatbot_messages_session_created` (`session_id`,`created_at`),
  CONSTRAINT `fk_chatbot_messages_session` FOREIGN KEY (`session_id`) REFERENCES `chatbot_sessions` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;
CREATE TABLE `chatbot_block_logs` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `animal_id` bigint NOT NULL,
  `anonymous_session_id` varchar(36) NOT NULL,
  `ip_hash` varchar(64) NOT NULL,
  `category` varchar(50) NOT NULL,
  `reason` varchar(100) NOT NULL,
  `question_length` int NOT NULL,
  `question_preview` varchar(200) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_chatbot_block_logs_anon_created` (`anonymous_session_id`,`created_at`),
  KEY `idx_chatbot_block_logs_ip_created` (`ip_hash`,`created_at`),
  KEY `fk_chatbot_block_logs_animal` (`animal_id`),
  CONSTRAINT `fk_chatbot_block_logs_animal` FOREIGN KEY (`animal_id`) REFERENCES `animals` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;
CREATE TABLE `BATCH_JOB_INSTANCE` (
  `JOB_INSTANCE_ID` bigint NOT NULL,
  `VERSION` bigint DEFAULT NULL,
  `JOB_NAME` varchar(100) NOT NULL,
  `JOB_KEY` varchar(32) NOT NULL,
  PRIMARY KEY (`JOB_INSTANCE_ID`),
  UNIQUE KEY `JOB_INST_UN` (`JOB_NAME`,`JOB_KEY`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;
CREATE TABLE `BATCH_JOB_EXECUTION` (
  `JOB_EXECUTION_ID` bigint NOT NULL,
  `VERSION` bigint DEFAULT NULL,
  `JOB_INSTANCE_ID` bigint NOT NULL,
  `CREATE_TIME` datetime(6) NOT NULL,
  `START_TIME` datetime(6) DEFAULT NULL,
  `END_TIME` datetime(6) DEFAULT NULL,
  `STATUS` varchar(10) DEFAULT NULL,
  `EXIT_CODE` varchar(2500) DEFAULT NULL,
  `EXIT_MESSAGE` varchar(2500) DEFAULT NULL,
  `LAST_UPDATED` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`JOB_EXECUTION_ID`),
  KEY `JOB_INST_EXEC_FK` (`JOB_INSTANCE_ID`),
  CONSTRAINT `JOB_INST_EXEC_FK` FOREIGN KEY (`JOB_INSTANCE_ID`) REFERENCES `BATCH_JOB_INSTANCE` (`JOB_INSTANCE_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;
CREATE TABLE `BATCH_JOB_EXECUTION_PARAMS` (
  `JOB_EXECUTION_ID` bigint NOT NULL,
  `PARAMETER_NAME` varchar(100) NOT NULL,
  `PARAMETER_TYPE` varchar(100) NOT NULL,
  `PARAMETER_VALUE` varchar(2500) DEFAULT NULL,
  `IDENTIFYING` char(1) NOT NULL,
  KEY `JOB_EXEC_PARAMS_FK` (`JOB_EXECUTION_ID`),
  CONSTRAINT `JOB_EXEC_PARAMS_FK` FOREIGN KEY (`JOB_EXECUTION_ID`) REFERENCES `BATCH_JOB_EXECUTION` (`JOB_EXECUTION_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;
CREATE TABLE `BATCH_STEP_EXECUTION` (
  `STEP_EXECUTION_ID` bigint NOT NULL,
  `VERSION` bigint NOT NULL,
  `STEP_NAME` varchar(100) NOT NULL,
  `JOB_EXECUTION_ID` bigint NOT NULL,
  `CREATE_TIME` datetime(6) NOT NULL,
  `START_TIME` datetime(6) DEFAULT NULL,
  `END_TIME` datetime(6) DEFAULT NULL,
  `STATUS` varchar(10) DEFAULT NULL,
  `COMMIT_COUNT` bigint DEFAULT NULL,
  `READ_COUNT` bigint DEFAULT NULL,
  `FILTER_COUNT` bigint DEFAULT NULL,
  `WRITE_COUNT` bigint DEFAULT NULL,
  `READ_SKIP_COUNT` bigint DEFAULT NULL,
  `WRITE_SKIP_COUNT` bigint DEFAULT NULL,
  `PROCESS_SKIP_COUNT` bigint DEFAULT NULL,
  `ROLLBACK_COUNT` bigint DEFAULT NULL,
  `EXIT_CODE` varchar(2500) DEFAULT NULL,
  `EXIT_MESSAGE` varchar(2500) DEFAULT NULL,
  `LAST_UPDATED` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`STEP_EXECUTION_ID`),
  KEY `JOB_EXEC_STEP_FK` (`JOB_EXECUTION_ID`),
  CONSTRAINT `JOB_EXEC_STEP_FK` FOREIGN KEY (`JOB_EXECUTION_ID`) REFERENCES `BATCH_JOB_EXECUTION` (`JOB_EXECUTION_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;
CREATE TABLE `BATCH_STEP_EXECUTION_CONTEXT` (
  `STEP_EXECUTION_ID` bigint NOT NULL,
  `SHORT_CONTEXT` varchar(2500) NOT NULL,
  `SERIALIZED_CONTEXT` text,
  PRIMARY KEY (`STEP_EXECUTION_ID`),
  CONSTRAINT `STEP_EXEC_CTX_FK` FOREIGN KEY (`STEP_EXECUTION_ID`) REFERENCES `BATCH_STEP_EXECUTION` (`STEP_EXECUTION_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;
CREATE TABLE `BATCH_JOB_EXECUTION_CONTEXT` (
  `JOB_EXECUTION_ID` bigint NOT NULL,
  `SHORT_CONTEXT` varchar(2500) NOT NULL,
  `SERIALIZED_CONTEXT` text,
  PRIMARY KEY (`JOB_EXECUTION_ID`),
  CONSTRAINT `JOB_EXEC_CTX_FK` FOREIGN KEY (`JOB_EXECUTION_ID`) REFERENCES `BATCH_JOB_EXECUTION` (`JOB_EXECUTION_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;
CREATE TABLE `BATCH_STEP_EXECUTION_SEQ` (
  `ID` bigint NOT NULL,
  `UNIQUE_KEY` char(1) NOT NULL,
  UNIQUE KEY `UNIQUE_KEY_UN` (`UNIQUE_KEY`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;
CREATE TABLE `BATCH_JOB_EXECUTION_SEQ` (
  `ID` bigint NOT NULL,
  `UNIQUE_KEY` char(1) NOT NULL,
  UNIQUE KEY `UNIQUE_KEY_UN` (`UNIQUE_KEY`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;
CREATE TABLE `BATCH_JOB_SEQ` (
  `ID` bigint NOT NULL,
  `UNIQUE_KEY` char(1) NOT NULL,
  UNIQUE KEY `UNIQUE_KEY_UN` (`UNIQUE_KEY`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

-- Seed only new databases; baseline on an existing database skips this migration.
INSERT INTO BATCH_STEP_EXECUTION_SEQ (ID, UNIQUE_KEY) VALUES (0, '0');
INSERT INTO BATCH_JOB_EXECUTION_SEQ (ID, UNIQUE_KEY) VALUES (0, '0');
INSERT INTO BATCH_JOB_SEQ (ID, UNIQUE_KEY) VALUES (0, '0');
