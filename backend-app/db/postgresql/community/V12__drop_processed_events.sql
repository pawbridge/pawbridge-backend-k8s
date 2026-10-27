-- Kafka consumer de-duplication record from the MSA services; no build reads or writes it.
-- Plain DROP (RESTRICT): V3's rollback-charset check goes with the table; any unexpected dependent object
-- aborts the migration instead.
DROP TABLE processed_events;
