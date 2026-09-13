CREATE TABLE shelter_public_information (
  shelter_id BIGINT NOT NULL,
  details JSON NOT NULL,
  collected_at DATETIME(6) NOT NULL,
  PRIMARY KEY (shelter_id),
  CONSTRAINT fk_shelter_public_information FOREIGN KEY (shelter_id) REFERENCES shelters(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
