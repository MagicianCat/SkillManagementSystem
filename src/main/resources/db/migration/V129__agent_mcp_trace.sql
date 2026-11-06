ALTER TABLE agent_mcp_audit ADD COLUMN trace_id VARCHAR(120) NULL;
CREATE INDEX idx_agent_mcp_audit_trace ON agent_mcp_audit(trace_id,started_at);
