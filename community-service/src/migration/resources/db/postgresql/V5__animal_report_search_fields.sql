-- Preserve existing free-text region/species. Do not infer classifications for historical rows.
ALTER TABLE animal_reports
    ADD COLUMN province VARCHAR(40),
    ADD COLUMN district VARCHAR(40),
    ADD COLUMN animal_type VARCHAR(16),
    ADD CONSTRAINT animal_reports_animal_type_check
        CHECK (animal_type IS NULL OR animal_type IN ('DOG', 'CAT', 'OTHER')),
    ADD CONSTRAINT animal_reports_district_province_check
        CHECK (district IS NULL OR province IS NOT NULL);

CREATE INDEX animal_reports_region_occurred_idx
    ON animal_reports (province, district, occurred_on)
    WHERE deleted_at IS NULL;
CREATE INDEX animal_reports_type_occurred_idx
    ON animal_reports (animal_type, occurred_on)
    WHERE deleted_at IS NULL;
