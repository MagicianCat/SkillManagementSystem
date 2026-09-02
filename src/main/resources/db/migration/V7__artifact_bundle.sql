CREATE TABLE skill_artifact (
 id bigint NOT NULL AUTO_INCREMENT,
 skill_version_id bigint NOT NULL, platform_id bigint NOT NULL, os_type varchar(32) NOT NULL,
 artifact_type varchar(32) NOT NULL, object_key varchar(512) NOT NULL, file_name varchar(255) NOT NULL,
 size_bytes bigint NOT NULL, sha256 char(64) NOT NULL, adapter_version varchar(64) NOT NULL,
 build_task_id bigint NULL, status varchar(32) NOT NULL, time_created datetime(3) NOT NULL, time_updated datetime(3) NOT NULL,
 PRIMARY KEY (id), UNIQUE KEY uk_artifact_target(skill_version_id,platform_id,os_type,artifact_type,adapter_version),
 CONSTRAINT fk_artifact_version FOREIGN KEY(skill_version_id) REFERENCES skill_version(id),
 CONSTRAINT fk_artifact_platform FOREIGN KEY(platform_id) REFERENCES platform(id)
);
CREATE TABLE skill_bundle (
 id bigint NOT NULL AUTO_INCREMENT, bundle_key char(64) NOT NULL, platform_id bigint NOT NULL, os_type varchar(32) NOT NULL,
 lock_hash char(64) NOT NULL, resolver_version varchar(32) NOT NULL, object_key varchar(512) NULL, file_name varchar(255) NULL,
 size_bytes bigint NULL, sha256 char(64) NULL, status varchar(32) NOT NULL, created_by bigint NOT NULL,
 time_created datetime(3) NOT NULL, time_updated datetime(3) NOT NULL, version_no int NOT NULL DEFAULT 0,
 PRIMARY KEY(id), UNIQUE KEY uk_bundle_key(bundle_key), CONSTRAINT fk_bundle_platform FOREIGN KEY(platform_id) REFERENCES platform(id),
 CONSTRAINT fk_bundle_user FOREIGN KEY(created_by) REFERENCES iam_user(id)
);
CREATE TABLE skill_bundle_item (
 id bigint NOT NULL AUTO_INCREMENT, bundle_id bigint NOT NULL, skill_version_id bigint NOT NULL, artifact_id bigint NOT NULL,
 root_selected boolean NOT NULL, dependency_depth int NOT NULL, required_by_path varchar(2048) NOT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_bundle_item(bundle_id,skill_version_id), CONSTRAINT fk_item_bundle FOREIGN KEY(bundle_id) REFERENCES skill_bundle(id),
 CONSTRAINT fk_item_version FOREIGN KEY(skill_version_id) REFERENCES skill_version(id), CONSTRAINT fk_item_artifact FOREIGN KEY(artifact_id) REFERENCES skill_artifact(id)
);
