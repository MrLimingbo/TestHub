-- Preserve existing identifiers while allowing the full public parameter-name boundary.
ALTER TABLE audit_log MODIFY COLUMN resource_id VARCHAR(193) NULL;
