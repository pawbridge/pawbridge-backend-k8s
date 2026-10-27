-- Revision and pending flags are changed in the source transaction, including
-- JPQL bulk updates. A failed source transaction cannot publish search changes.
ALTER TABLE animals ADD COLUMN search_revision bigint NOT NULL DEFAULT 1 CHECK (search_revision > 0);
ALTER TABLE animals ADD COLUMN search_dirty boolean NOT NULL DEFAULT true;
ALTER TABLE shelters ADD COLUMN search_revision bigint NOT NULL DEFAULT 1 CHECK (search_revision > 0);
ALTER TABLE shelters ADD COLUMN search_dirty boolean NOT NULL DEFAULT true;
CREATE INDEX idx_animals_search_pending ON animals(id) WHERE search_dirty;
CREATE INDEX idx_shelters_search_pending ON shelters(id) WHERE search_dirty;

CREATE FUNCTION mark_animal_search_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.breed,NEW.color,NEW.special_mark,NEW.description,NEW.happen_place)
       IS DISTINCT FROM ROW(OLD.breed,OLD.color,OLD.special_mark,OLD.description,OLD.happen_place) THEN
        NEW.search_revision := OLD.search_revision + 1;
        NEW.search_dirty := true;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER animal_search_changed BEFORE UPDATE ON animals
FOR EACH ROW EXECUTE FUNCTION mark_animal_search_changed();

CREATE FUNCTION mark_shelter_search_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.name,NEW.address) IS DISTINCT FROM ROW(OLD.name,OLD.address) THEN
        NEW.search_revision := OLD.search_revision + 1;
        NEW.search_dirty := true;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER shelter_search_changed BEFORE UPDATE ON shelters
FOR EACH ROW EXECUTE FUNCTION mark_shelter_search_changed();

-- Shelter text is stored once, not copied to every resident animal on changes.
CREATE TABLE animal_search_documents (
    animal_id bigint PRIMARY KEY REFERENCES animals(id) ON DELETE CASCADE,
    source_revision bigint NOT NULL,
    analyzer_version varchar(80) NOT NULL,
    tokens tsvector NOT NULL,
    breed_tokens tsvector NOT NULL,
    relation_tokens tsvector NOT NULL
);
CREATE TABLE shelter_search_documents (
    shelter_id bigint PRIMARY KEY REFERENCES shelters(id) ON DELETE CASCADE,
    source_revision bigint NOT NULL,
    analyzer_version varchar(80) NOT NULL,
    tokens tsvector NOT NULL
);
CREATE INDEX idx_animal_search_tokens ON animal_search_documents USING gin(tokens);
CREATE INDEX idx_animal_search_breed ON animal_search_documents USING gin(breed_tokens);
CREATE INDEX idx_animal_search_relations ON animal_search_documents USING gin(relation_tokens);
CREATE INDEX idx_shelter_search_tokens ON shelter_search_documents USING gin(tokens);
