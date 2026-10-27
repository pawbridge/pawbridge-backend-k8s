-- Tables left over from the MSA services; no build reads or writes them.
-- processed_events was the Kafka consumer de-duplication record (Kafka, outbox and CDC are gone).
-- sync_history was never written by the modular monolith and is empty.
-- animal_image_embeddings (V3) stayed empty: similar-animal search reads the published lost_gallery_documents.
-- Plain DROP (RESTRICT): V7's rollback-charset checks, sync_history's identity sequence and the embeddings
-- foreign key to animals go with the tables; any unexpected dependent object aborts the migration instead.
DROP TABLE processed_events;
DROP TABLE sync_history;
DROP TABLE animal_image_embeddings;
