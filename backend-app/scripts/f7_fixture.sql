\set ON_ERROR_STOP on
-- F7 only extends the existing SELECT-only reader. No new role/schema/index/writer grant.
GRANT SELECT(post_id,title_hash,content_hash,analyzer_version,tokens)
 ON pawbridge_community.post_search_documents TO pawbridge_f2_reader;
