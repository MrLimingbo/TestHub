-- Parameter audit identifiers contain the 64-character environment and 128-character key.
ALTER TABLE audit_log MODIFY COLUMN resource_id VARCHAR(193) NULL;
