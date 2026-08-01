ALTER TABLE functional_case
    ADD COLUMN case_type VARCHAR(24) NOT NULL DEFAULT 'FUNCTIONAL' AFTER case_code,
    ADD KEY idx_functional_case_project_type (project_id, case_type, updated_at);
