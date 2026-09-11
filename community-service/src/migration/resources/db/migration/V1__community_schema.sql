-- Initial community schema: live SHOW CREATE TABLE snapshot, 2026-09-11.
-- Preserve existing charset, indexes, enums and constraints.
-- Exclude live AUTO_INCREMENT counters and all application rows.
-- Existing databases require explicit V1 baseline after equivalence review.

CREATE TABLE `posts` (
  `post_id` bigint NOT NULL AUTO_INCREMENT,
  `author_id` bigint NOT NULL,
  `board_type` enum('ADOPTION','COMMUNICATION','MISSING','PROTECTION','REPORT') NOT NULL,
  `content` text NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `image_urls` json DEFAULT NULL,
  `title` varchar(200) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE `comments` (
  `comment_id` bigint NOT NULL AUTO_INCREMENT,
  `author_id` bigint NOT NULL,
  `content` text NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `post_id` bigint NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE `outbox_events` (
  `outbox_id` bigint NOT NULL AUTO_INCREMENT,
  `aggregate_id` varchar(255) NOT NULL,
  `aggregate_type` varchar(255) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `event_id` varchar(255) NOT NULL,
  `payload` json NOT NULL,
  `type` varchar(255) NOT NULL,
  PRIMARY KEY (`outbox_id`),
  UNIQUE KEY `UK7ba1uqwbn85u1g6jg4ja1tk6k` (`event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE `processed_events` (
  `event_id` varchar(255) NOT NULL,
  `event_type` varchar(255) NOT NULL,
  `processed_at` datetime(6) NOT NULL,
  PRIMARY KEY (`event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;
