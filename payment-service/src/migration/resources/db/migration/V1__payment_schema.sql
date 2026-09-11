-- Initial payment schema: live SHOW CREATE TABLE snapshot, 2026-09-11.
-- Preserve existing charset, indexes, enums and constraints.
-- Exclude live AUTO_INCREMENT counters and all application rows.
-- Existing databases require explicit V1 baseline after equivalence review.

CREATE TABLE `outbox` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `aggregate_id` varchar(100) NOT NULL,
  `aggregate_type` varchar(50) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `event_type` varchar(50) NOT NULL,
  `payload` json NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE `payments` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `amount` bigint NOT NULL,
  `approved_at` datetime(6) DEFAULT NULL,
  `method` varchar(20) DEFAULT NULL,
  `order_id` varchar(36) NOT NULL,
  `payment_key` varchar(100) NOT NULL,
  `requested_at` datetime(6) DEFAULT NULL,
  `status` enum('ABORTED','CANCELED','DONE','READY') NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK35yqdahtiysne6iij9ske72bj` (`payment_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;
