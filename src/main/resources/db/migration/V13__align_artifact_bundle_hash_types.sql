-- Align hash/key columns with entities that use @Column(length=64) (VARCHAR).
ALTER TABLE skill_artifact
    MODIFY COLUMN sha256 varchar(64) NOT NULL;

ALTER TABLE skill_bundle
    MODIFY COLUMN bundle_key varchar(64) NOT NULL,
    MODIFY COLUMN lock_hash varchar(64) NOT NULL,
    MODIFY COLUMN sha256 varchar(64) NULL;
