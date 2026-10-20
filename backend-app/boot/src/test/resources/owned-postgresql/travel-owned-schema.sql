-- P14 read-only travel catalog fixture: exact seven source table blocks, indexes and target ALTERs.
-- Source fixed a132a0245804341fe5bba8cb67f787fdf0aefc49 animal PostgreSQL V1.
-- Collector/run/budget/checkpoint tables and migrations for other Animal features are excluded.
-- JSONB is built in; these selected blocks require no extension. Root provisions the new reader default RO.
BEGIN;
SELECT set_config('pawbridge.owned_database', :'owned_database', true),
       set_config('pawbridge.owned_marker', :'owned_marker', true),
       set_config('pawbridge.owned_reader', :'owned_reader', true);
DO $$ BEGIN
 IF current_setting('pawbridge.owned_reader') <> 'pb_travel_reader'
    OR current_database() <> current_setting('pawbridge.owned_database')
    OR current_database() !~ '^pb_travel_owned_[0-9a-f]{12}$'
 THEN RAISE EXCEPTION 'Owned travel identity mismatch'; END IF;
 IF (SELECT count(*) FROM public.pawbridge_owned_fixture) <> 1
    OR NOT EXISTS (SELECT 1 FROM public.pawbridge_owned_fixture WHERE marker=current_setting('pawbridge.owned_marker'))
 THEN RAISE EXCEPTION 'Owned travel marker mismatch'; END IF;
END $$;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA pawbridge_animal;
REVOKE ALL ON SCHEMA pawbridge_animal FROM PUBLIC;
SET LOCAL search_path=pawbridge_animal,public;
CREATE TABLE pet_travel_regions (
    code VARCHAR(5) NOT NULL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    fetched_at timestamp(6) without time zone NOT NULL,
    completed_at timestamp(6) without time zone NULL
);

CREATE TABLE pet_travel_collection_state (
    id SMALLINT NOT NULL PRIMARY KEY,
    phase VARCHAR(16) NOT NULL,
    next_page INT NOT NULL,
    error_code VARCHAR(32) NULL,
    completed_at timestamp(6) without time zone NULL
);

CREATE TABLE pet_travel_targets (
    provider VARCHAR(32) NOT NULL,
    content_id VARCHAR(20) NOT NULL,
    area_code VARCHAR(5) NULL,
    modified_time CHAR(14) NOT NULL,
    shown BOOLEAN NOT NULL,
    image_url VARCHAR(2048) NULL,
    copyright_type VARCHAR(16) NULL,
    generation BIGINT NOT NULL,
    pending BOOLEAN NOT NULL,
    observed_at timestamp(6) without time zone NOT NULL,
    PRIMARY KEY (provider, content_id)
);

CREATE INDEX idx_travel_pending ON pet_travel_targets (pending, observed_at, content_id);

CREATE INDEX idx_travel_region_pending ON pet_travel_targets (area_code, pending);

ALTER TABLE pet_travel_targets
    ADD COLUMN basic_data JSONB NULL,
    ADD COLUMN basic_fetched_at timestamp(6) without time zone NULL,
    ADD COLUMN detail_attempted_at timestamp(6) without time zone NULL,
    ADD COLUMN detail_error VARCHAR(32) NULL;

ALTER TABLE pet_travel_targets
    ADD COLUMN pet_valid_after timestamp(6) without time zone NULL,
    ADD COLUMN pet_changed_at timestamp(6) without time zone NULL;

CREATE TABLE pet_travel_places (
    provider VARCHAR(32) NOT NULL,
    content_id VARCHAR(20) NOT NULL,
    area_code VARCHAR(5) NOT NULL,
    title VARCHAR(500) NOT NULL,
    common_data JSONB NOT NULL,
    pet_data JSONB NOT NULL,
    image_url VARCHAR(2048) NULL,
    copyright_type VARCHAR(16) NULL,
    visible BOOLEAN NOT NULL,
    published_at timestamp(6) without time zone NOT NULL,
    PRIMARY KEY (provider, content_id)
);

CREATE INDEX idx_travel_public_region ON pet_travel_places (area_code, visible, title, content_id);

CREATE TABLE pet_travel_pet_details (
    provider VARCHAR(32) NOT NULL,
    content_id VARCHAR(20) NOT NULL,
    pet_data JSONB NOT NULL,
    source VARCHAR(16) NOT NULL,
    fetched_at timestamp(6) without time zone NOT NULL,
    valid_until timestamp(6) without time zone NULL,
    cycle_id CHAR(36) NULL,
    PRIMARY KEY (provider, content_id)
);

CREATE TABLE pet_travel_visit_details (
    provider VARCHAR(32) NOT NULL,
    content_id VARCHAR(20) NOT NULL,
    content_type_id VARCHAR(2) NOT NULL,
    intro_data JSONB NULL,
    info_data JSONB NULL,
    intro_source_modified_time CHAR(14) NULL,
    info_source_modified_time CHAR(14) NULL,
    image_source_modified_time CHAR(14) NULL,
    intro_fetched_at timestamp(6) without time zone NULL,
    info_fetched_at timestamp(6) without time zone NULL,
    image_fetched_at timestamp(6) without time zone NULL,
    intro_attempted_at timestamp(6) without time zone NULL,
    info_attempted_at timestamp(6) without time zone NULL,
    image_attempted_at timestamp(6) without time zone NULL,
    intro_error VARCHAR(32) NULL,
    info_error VARCHAR(32) NULL,
    image_error VARCHAR(32) NULL,
    PRIMARY KEY (provider, content_id)
);

CREATE INDEX idx_travel_visit_intro ON pet_travel_visit_details (intro_attempted_at, content_id);

CREATE INDEX idx_travel_visit_info ON pet_travel_visit_details (info_attempted_at, content_id);

CREATE INDEX idx_travel_visit_image ON pet_travel_visit_details (image_attempted_at, content_id);

CREATE TABLE pet_travel_images (
    provider VARCHAR(32) NOT NULL,
    content_id VARCHAR(20) NOT NULL,
    serial_number VARCHAR(32) NOT NULL,
    image_name VARCHAR(500) NULL,
    original_url VARCHAR(2048) NOT NULL,
    thumbnail_url VARCHAR(2048) NULL,
    copyright_type VARCHAR(16) NULL,
    display_order INT NOT NULL,
    fetched_at timestamp(6) without time zone NOT NULL,
    PRIMARY KEY (provider, content_id, serial_number)
);

CREATE INDEX idx_travel_images_display ON pet_travel_images (provider, content_id, display_order);
-- All rows below are synthetic. Numeric region ordering differs from lexicographic ordering.
INSERT INTO pet_travel_regions(code,name,fetched_at,completed_at) VALUES
 ('100','synthetic 100','2020-01-01','2020-01-02'),
 ('20','synthetic 20','2020-01-01','2020-01-02'),
 ('99','synthetic empty','2020-01-01',NULL);
INSERT INTO pet_travel_collection_state(id,phase,next_page,error_code,completed_at) VALUES (1,'DISCOVERY',1,NULL,'2020-01-02');
INSERT INTO pet_travel_targets(provider,content_id,area_code,modified_time,shown,image_url,copyright_type,generation,pending,observed_at,basic_data,basic_fetched_at,pet_valid_after,pet_changed_at) VALUES ('KOREA_TOURISM_ORGANIZATION','910001','20','20200101000000',TRUE,'http://tong.visitkorea.or.kr/cms/resource/20/MAIN.jpg','Type1',1,FALSE,'2020-01-01','{"contentid":"910001","title":"Alpha","addr1":"synthetic current address","contenttypeid":"12"}'::jsonb,'2020-01-01','2020-01-01','2020-01-01');
INSERT INTO pet_travel_targets(provider,content_id,area_code,modified_time,shown,image_url,copyright_type,generation,pending,observed_at,basic_data,basic_fetched_at,pet_valid_after,pet_changed_at) VALUES ('KOREA_TOURISM_ORGANIZATION','910002','20','20200101000000',TRUE,NULL,NULL,1,FALSE,'2020-01-01','{"contentid":"910002","title":"Alpha","addr1":"synthetic current address","contenttypeid":"14"}'::jsonb,'2020-01-01','2020-01-01','2020-01-01');
INSERT INTO pet_travel_targets(provider,content_id,area_code,modified_time,shown,image_url,copyright_type,generation,pending,observed_at,basic_data,basic_fetched_at,pet_valid_after,pet_changed_at) VALUES ('KOREA_TOURISM_ORGANIZATION','910003','20','20200101000000',TRUE,NULL,NULL,1,FALSE,'2020-01-01','{"contentid":"910003","title":"Place03","addr1":"synthetic current address","contenttypeid":"28"}'::jsonb,'2020-01-01','2020-01-01','2020-01-01');
INSERT INTO pet_travel_targets(provider,content_id,area_code,modified_time,shown,image_url,copyright_type,generation,pending,observed_at,basic_data,basic_fetched_at,pet_valid_after,pet_changed_at) VALUES ('KOREA_TOURISM_ORGANIZATION','910004','20','20200101000000',TRUE,NULL,NULL,1,FALSE,'2020-01-01','{"contentid":"910004","title":"Place04","addr1":"synthetic current address","contenttypeid":"12"}'::jsonb,'2020-01-01','2020-01-01','2020-01-01');
INSERT INTO pet_travel_targets(provider,content_id,area_code,modified_time,shown,image_url,copyright_type,generation,pending,observed_at,basic_data,basic_fetched_at,pet_valid_after,pet_changed_at) VALUES ('KOREA_TOURISM_ORGANIZATION','910005','20','20200101000000',TRUE,NULL,NULL,1,FALSE,'2020-01-01','{"contentid":"910005","title":"Place05","addr1":"synthetic current address","contenttypeid":"14"}'::jsonb,'2020-01-01','2020-01-01','2020-01-01');
INSERT INTO pet_travel_targets(provider,content_id,area_code,modified_time,shown,image_url,copyright_type,generation,pending,observed_at,basic_data,basic_fetched_at,pet_valid_after,pet_changed_at) VALUES ('KOREA_TOURISM_ORGANIZATION','910006','20','20200101000000',TRUE,NULL,NULL,1,FALSE,'2020-01-01','{"contentid":"910006","title":"Place06","addr1":"synthetic current address","contenttypeid":"28"}'::jsonb,'2020-01-01','2020-01-01','2020-01-01');
INSERT INTO pet_travel_targets(provider,content_id,area_code,modified_time,shown,image_url,copyright_type,generation,pending,observed_at,basic_data,basic_fetched_at,pet_valid_after,pet_changed_at) VALUES ('KOREA_TOURISM_ORGANIZATION','910007','20','20200101000000',TRUE,NULL,NULL,1,FALSE,'2020-01-01','{"contentid":"910007","title":"Place07","addr1":"synthetic current address","contenttypeid":"12"}'::jsonb,'2020-01-01','2020-01-01','2020-01-01');
INSERT INTO pet_travel_targets(provider,content_id,area_code,modified_time,shown,image_url,copyright_type,generation,pending,observed_at,basic_data,basic_fetched_at,pet_valid_after,pet_changed_at) VALUES ('KOREA_TOURISM_ORGANIZATION','910008','20','20200101000000',TRUE,NULL,NULL,1,FALSE,'2020-01-01','{"contentid":"910008","title":"Place08","addr1":"synthetic current address","contenttypeid":"14"}'::jsonb,'2020-01-01','2020-01-01','2020-01-01');
INSERT INTO pet_travel_targets(provider,content_id,area_code,modified_time,shown,image_url,copyright_type,generation,pending,observed_at,basic_data,basic_fetched_at,pet_valid_after,pet_changed_at) VALUES ('KOREA_TOURISM_ORGANIZATION','910009','20','20200101000000',TRUE,NULL,NULL,1,FALSE,'2020-01-01','{"contentid":"910009","title":"Place09","addr1":"synthetic current address","contenttypeid":"28"}'::jsonb,'2020-01-01','2020-01-01','2020-01-01');
INSERT INTO pet_travel_targets(provider,content_id,area_code,modified_time,shown,image_url,copyright_type,generation,pending,observed_at,basic_data,basic_fetched_at,pet_valid_after,pet_changed_at) VALUES ('KOREA_TOURISM_ORGANIZATION','910010','20','20200101000000',TRUE,NULL,NULL,1,FALSE,'2020-01-01','{"contentid":"910010","title":"Place10","addr1":"synthetic current address","contenttypeid":"12"}'::jsonb,'2020-01-01','2020-01-01','2020-01-01');
INSERT INTO pet_travel_targets(provider,content_id,area_code,modified_time,shown,image_url,copyright_type,generation,pending,observed_at,basic_data,basic_fetched_at,pet_valid_after,pet_changed_at) VALUES ('KOREA_TOURISM_ORGANIZATION','910011','20','20200101000000',TRUE,NULL,NULL,1,FALSE,'2020-01-01',NULL,NULL,'2020-01-01','2020-01-01');
INSERT INTO pet_travel_places(provider,content_id,area_code,title,common_data,pet_data,visible,published_at) VALUES ('KOREA_TOURISM_ORGANIZATION','910011','20','Zulu legacy','{"contentid":"910011","title":"Zulu legacy","addr1":"synthetic legacy address","contenttypeid":"28","firstimage":"http://not-provider.invalid/legacy.jpg","cpyrhtDivCd":"Type1"}'::jsonb,'{}'::jsonb,TRUE,'2020-01-01');
INSERT INTO pet_travel_places(provider,content_id,area_code,title,common_data,pet_data,visible,published_at) VALUES ('KOREA_TOURISM_ORGANIZATION','910001','20','old title','{"contentid":"910001","title":"old title","addr1":"old address","contenttypeid":"12","overview":"synthetic overview","firstimage":"https://not-provider.invalid/old.jpg","cpyrhtDivCd":"Type2"}'::jsonb,'{}'::jsonb,TRUE,'2020-01-01');
INSERT INTO pet_travel_targets(provider,content_id,area_code,modified_time,shown,image_url,copyright_type,generation,pending,observed_at,basic_data,basic_fetched_at,pet_valid_after,pet_changed_at) VALUES ('KOREA_TOURISM_ORGANIZATION','910012','20','20200101000000',TRUE,NULL,NULL,1,FALSE,'2020-01-01','{"contentid":"910012","title":"Excluded type","addr1":"synthetic current address","contenttypeid":"99"}'::jsonb,'2020-01-01','2020-01-01','2020-01-01');
INSERT INTO pet_travel_targets(provider,content_id,area_code,modified_time,shown,image_url,copyright_type,generation,pending,observed_at,basic_data,basic_fetched_at,pet_valid_after,pet_changed_at) VALUES ('KOREA_TOURISM_ORGANIZATION','910013','20','20200101000000',FALSE,NULL,NULL,1,FALSE,'2020-01-01','{"contentid":"910013","title":"Hidden","addr1":"synthetic current address","contenttypeid":"12"}'::jsonb,'2020-01-01','2020-01-01','2020-01-01');
INSERT INTO pet_travel_targets(provider,content_id,area_code,modified_time,shown,image_url,copyright_type,generation,pending,observed_at,basic_data,basic_fetched_at,pet_valid_after,pet_changed_at) VALUES ('KOREA_TOURISM_ORGANIZATION','910014',NULL,'20200101000000',TRUE,NULL,NULL,1,FALSE,'2020-01-01','{"contentid":"910014","title":"No area","addr1":"synthetic current address","contenttypeid":"12"}'::jsonb,'2020-01-01','2020-01-01','2020-01-01');
INSERT INTO pet_travel_targets(provider,content_id,area_code,modified_time,shown,image_url,copyright_type,generation,pending,observed_at,basic_data,basic_fetched_at,pet_valid_after,pet_changed_at) VALUES ('KOREA_TOURISM_ORGANIZATION','910015','20','20200101000000',TRUE,NULL,NULL,1,FALSE,'2020-01-01',NULL,NULL,'2020-01-01','2020-01-01');
INSERT INTO pet_travel_targets(provider,content_id,area_code,modified_time,shown,image_url,copyright_type,generation,pending,observed_at,basic_data,basic_fetched_at,pet_valid_after,pet_changed_at) VALUES ('KOREA_TOURISM_ORGANIZATION','910016','20','20200101000000',TRUE,NULL,NULL,1,FALSE,'2020-01-01','{"contentid":"910016","addr1":"synthetic current address","contenttypeid":"99"}'::jsonb,'2020-01-01','2020-01-01','2020-01-01');
INSERT INTO pet_travel_targets(provider,content_id,area_code,modified_time,shown,image_url,copyright_type,generation,pending,observed_at,basic_data,basic_fetched_at,pet_valid_after,pet_changed_at) VALUES ('KOREA_TOURISM_ORGANIZATION','920001','100','20200101000000',TRUE,NULL,NULL,1,FALSE,'2020-01-01','{"contentid":"920001","title":"Other region","addr1":"synthetic current address","contenttypeid":"12"}'::jsonb,'2020-01-01','2020-01-01','2020-01-01');
INSERT INTO pet_travel_targets(provider,content_id,area_code,modified_time,shown,image_url,copyright_type,generation,pending,observed_at,basic_data,basic_fetched_at,pet_valid_after,pet_changed_at) VALUES ('OTHER_PROVIDER','990001','20','20200101000000',TRUE,NULL,NULL,1,FALSE,'2020-01-01','{"contentid":"990001","title":"Foreign provider","addr1":"synthetic current address","contenttypeid":"12"}'::jsonb,'2020-01-01','2020-01-01','2020-01-01');
INSERT INTO pet_travel_pet_details(provider,content_id,pet_data,source,fetched_at,valid_until) VALUES ('KOREA_TOURISM_ORGANIZATION','910001','{"acmpyTypeCd":"DOGS","acmpyPsblCpam":"small animals","acmpyNeedMtr":"synthetic leash","etcAcmpyInfo":"synthetic note"}'::jsonb,'BULK','2021-01-01','2100-01-01');
INSERT INTO pet_travel_visit_details(provider,content_id,content_type_id,intro_data,info_data,intro_source_modified_time,info_source_modified_time,image_source_modified_time,intro_fetched_at,info_fetched_at,image_fetched_at) VALUES ('KOREA_TOURISM_ORGANIZATION','910001','12','{"infocenter":"0000-0000","usetime":"09:00-18:00","parking":"synthetic parking"}'::jsonb,'[{"infoname":"Policy","infotext":"synthetic dogs"},{"infoname":"","infotext":"discarded blank name"}]'::jsonb,'20200101000000','20200101000000','20200101000000','2021-01-01','2021-01-02','2021-01-03');
INSERT INTO pet_travel_images(provider,content_id,serial_number,image_name,original_url,thumbnail_url,copyright_type,display_order,fetched_at) VALUES ('KOREA_TOURISM_ORGANIZATION','910001','2','second','http://tong.visitkorea.or.kr/cms/resource/20/SECOND.jpg',NULL,'Type3',1,'2021-01-03');
INSERT INTO pet_travel_images(provider,content_id,serial_number,image_name,original_url,thumbnail_url,copyright_type,display_order,fetched_at) VALUES ('KOREA_TOURISM_ORGANIZATION','910001','1','first','http://tong.visitkorea.or.kr/cms/resource/20/FIRST.jpg',NULL,'Type1',1,'2021-01-03');
INSERT INTO pet_travel_images(provider,content_id,serial_number,image_name,original_url,thumbnail_url,copyright_type,display_order,fetched_at) VALUES ('KOREA_TOURISM_ORGANIZATION','910001','0','restricted','http://tong.visitkorea.or.kr/cms/resource/20/RESTRICTED.jpg',NULL,'Type2',0,'2021-01-03');
INSERT INTO pet_travel_images(provider,content_id,serial_number,image_name,original_url,thumbnail_url,copyright_type,display_order,fetched_at) VALUES ('KOREA_TOURISM_ORGANIZATION','910001','3','bad host','https://not-provider.invalid/forbidden.jpg',NULL,'Type1',2,'2021-01-03');
REVOKE ALL ON ALL TABLES IN SCHEMA pawbridge_animal FROM PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA pawbridge_animal FROM PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA pawbridge_animal FROM PUBLIC;
GRANT CONNECT ON DATABASE :"owned_database" TO :"owned_reader";
GRANT USAGE ON SCHEMA pawbridge_animal TO :"owned_reader";
GRANT SELECT ON public.pawbridge_owned_fixture TO :"owned_reader";
GRANT SELECT ON pawbridge_animal.pet_travel_regions,pawbridge_animal.pet_travel_collection_state,pawbridge_animal.pet_travel_targets,pawbridge_animal.pet_travel_places,pawbridge_animal.pet_travel_pet_details,pawbridge_animal.pet_travel_visit_details,pawbridge_animal.pet_travel_images TO :"owned_reader";
COMMIT;
