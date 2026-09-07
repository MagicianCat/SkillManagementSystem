CREATE TABLE agent_recommendation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created TIMESTAMP(6) NOT NULL,
    time_updated TIMESTAMP(6) NOT NULL,
    run_ref VARCHAR(64) NOT NULL,
    summary VARCHAR(2000) NOT NULL,
    payload TEXT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_recommendation_run_ref (run_ref)
);
