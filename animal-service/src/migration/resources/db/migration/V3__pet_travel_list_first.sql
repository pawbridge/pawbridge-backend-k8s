-- Keep V2 snapshots intact; discovery can now serve basic data before detail enrichment.
ALTER TABLE pet_travel_targets
    ADD COLUMN basic_data JSON NULL,
    ADD COLUMN basic_fetched_at DATETIME(6) NULL,
    ADD COLUMN detail_attempted_at DATETIME(6) NULL,
    ADD COLUMN detail_error VARCHAR(32) NULL;

-- Old DETAILS checkpoints must not prevent future discovery scans.
UPDATE pet_travel_collection_state SET phase='HIDDEN',next_page=1 WHERE phase='DETAILS';
