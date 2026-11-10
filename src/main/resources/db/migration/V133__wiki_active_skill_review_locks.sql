CREATE TABLE wiki_active_skill_review_lock (
  skill_id BIGINT NOT NULL PRIMARY KEY,
  review_id BIGINT NOT NULL,
  INDEX idx_wiki_active_skill_review (review_id)
);
