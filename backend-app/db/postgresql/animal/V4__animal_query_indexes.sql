-- The DB owner provisions extensions; the application/migration role does not install them.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension e JOIN pg_namespace n ON n.oid=e.extnamespace
                   WHERE e.extname='pg_trgm' AND n.nspname='public') THEN
        RAISE EXCEPTION 'The DB owner must provision pg_trgm in public before animal text-search migration';
    END IF;
END $$;

CREATE INDEX idx_animal_public_notice ON animals (notice_end_date, id)
    WHERE status IN ('NOTICE','PROTECT');
CREATE INDEX idx_animal_public_created ON animals (created_at DESC, id)
    WHERE status IN ('NOTICE','PROTECT');
CREATE INDEX idx_animal_happen_date ON animals (happen_date);
