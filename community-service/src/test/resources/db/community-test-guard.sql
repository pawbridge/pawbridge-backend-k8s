CREATE SCHEMA migration_test_guard;
CREATE TABLE migration_test_guard.guard (marker text NOT NULL);
INSERT INTO migration_test_guard.guard VALUES ('community-application-disposable');
CREATE SCHEMA pawbridge_community;
