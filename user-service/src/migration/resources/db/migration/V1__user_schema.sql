-- Initial user schema: live SHOW CREATE TABLE snapshot, 2026-09-11.
-- Preserve existing charset, indexes, enums and constraints.
-- Exclude live AUTO_INCREMENT counters and all application rows.
-- Existing databases require explicit V1 baseline after equivalence review.

CREATE TABLE `users` (
  `user_id` bigint NOT NULL AUTO_INCREMENT,
  `care_reg_no` varchar(50) DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `email` varchar(255) NOT NULL,
  `name` varchar(20) NOT NULL,
  `nickname` varchar(30) NOT NULL,
  `password` varchar(255) DEFAULT NULL,
  `provider` varchar(20) NOT NULL,
  `provider_id` varchar(100) DEFAULT NULL,
  `role` enum('ROLE_ADMIN','ROLE_SHELTER','ROLE_USER') NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`user_id`),
  UNIQUE KEY `UKruj7llynj9miho19bgmskwipt` (`email`,`provider`),
  UNIQUE KEY `UK2ty1xmrrgtn89xt7kyxx6ta7h` (`nickname`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE `favorites` (
  `favorite_id` bigint NOT NULL AUTO_INCREMENT,
  `animal_id` bigint NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`favorite_id`),
  UNIQUE KEY `uk_user_animal` (`user_id`,`animal_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_animal_id` (`animal_id`),
  CONSTRAINT `fk_favorites_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE `outbox_events` (
  `outbox_event_id` bigint NOT NULL AUTO_INCREMENT,
  `aggregate_id` varchar(100) NOT NULL,
  `aggregate_type` varchar(50) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `event_id` varchar(36) NOT NULL,
  `event_type` varchar(50) NOT NULL,
  `payload` json NOT NULL,
  `topic` varchar(100) NOT NULL,
  PRIMARY KEY (`outbox_event_id`),
  UNIQUE KEY `uk_event_id` (`event_id`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE `processed_events` (
  `event_id` varchar(36) NOT NULL,
  `event_type` varchar(50) NOT NULL,
  `processed_at` datetime(6) NOT NULL,
  PRIMARY KEY (`event_id`),
  KEY `idx_processed_at` (`processed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE `refresh_tokens` (
  `refresh_token_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `token` varchar(500) NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`refresh_token_id`),
  UNIQUE KEY `UKghpmfn23vmxfu3spu3lfg4r2d` (`token`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;
