ALTER TABLE ops_event ADD COLUMN trace_id VARCHAR(120) NULL AFTER request_id;
CREATE INDEX idx_ops_event_trace ON ops_event(trace_id);
