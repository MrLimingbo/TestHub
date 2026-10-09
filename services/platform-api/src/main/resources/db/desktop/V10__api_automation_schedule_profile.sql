ALTER TABLE api_automation_schedule ADD COLUMN execution_profile_id CHAR(26) NULL;
ALTER TABLE api_automation_schedule ADD CONSTRAINT fk_api_automation_schedule_profile
    FOREIGN KEY (execution_profile_id) REFERENCES api_automation_execution_profile(id) ON DELETE SET NULL;
CREATE INDEX idx_api_automation_schedule_profile ON api_automation_schedule(project_id, execution_profile_id);
