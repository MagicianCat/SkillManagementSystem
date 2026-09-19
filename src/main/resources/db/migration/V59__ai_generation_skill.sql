CREATE TABLE ai_generation_skill (
    id BIGINT NOT NULL AUTO_INCREMENT,
    generation_id BIGINT NOT NULL,
    skill_id BIGINT NOT NULL,
    skill_version_id BIGINT NULL,
    first_invoked_at DATETIME(3) NULL,
    invocation_count INT NOT NULL DEFAULT 1,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_generation_skill UNIQUE (generation_id, skill_id),
    CONSTRAINT fk_generation_skill_generation FOREIGN KEY (generation_id) REFERENCES ai_generation_event(id) ON DELETE CASCADE,
    CONSTRAINT fk_generation_skill_skill FOREIGN KEY (skill_id) REFERENCES skill(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
