\set ON_ERROR_STOP on
\getenv reader_password F2_TEST_DB_PASSWORD
CREATE ROLE pawbridge_f2_reader LOGIN PASSWORD :'reader_password';
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA pawbridge_f2_fixture;
CREATE TABLE pawbridge_f2_fixture.marker(value TEXT NOT NULL);
INSERT INTO pawbridge_f2_fixture.marker VALUES ('pawbridge-f2-ci-only-v1');
CREATE SCHEMA pawbridge_user;
CREATE TABLE pawbridge_user.users(user_id BIGINT PRIMARY KEY, nickname VARCHAR(30) NOT NULL);
CREATE SCHEMA pawbridge_community;
CREATE TABLE pawbridge_community.posts(
 post_id BIGINT PRIMARY KEY, author_id BIGINT NOT NULL, title VARCHAR(200) NOT NULL,
 content TEXT NOT NULL, board_type VARCHAR(255) NOT NULL
 CHECK(board_type IN ('MISSING','PROTECTION','REPORT','ADOPTION','COMMUNICATION')),
 image_urls JSON, deleted_at TIMESTAMP(6), created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL
);
CREATE TABLE public.users(LIKE pawbridge_user.users INCLUDING ALL);
CREATE TABLE public.posts(LIKE pawbridge_community.posts INCLUDING ALL);
INSERT INTO public.users VALUES(42, 'WRONG_SCHEMA');
INSERT INTO public.posts VALUES(42,42,'WRONG_SCHEMA','WRONG_SCHEMA','REPORT','[]',NULL,'2026-10-17 15:01:02.123456','2026-10-17 16:02:00');
GRANT USAGE ON SCHEMA pawbridge_user,pawbridge_community TO pawbridge_f2_reader;
GRANT SELECT(user_id,nickname) ON pawbridge_user.users TO pawbridge_f2_reader;
GRANT SELECT(post_id,author_id,title,content,board_type,image_urls,deleted_at,created_at,updated_at)
 ON pawbridge_community.posts TO pawbridge_f2_reader;
GRANT SELECT ON public.users,public.posts TO pawbridge_f2_reader;
