ALTER TABLE functional_test_plan
    ADD COLUMN catalog_id CHAR(26) NULL AFTER project_id,
    ADD CONSTRAINT fk_functional_plan_catalog FOREIGN KEY (catalog_id) REFERENCES functional_catalog(id),
    ADD KEY idx_functional_plan_catalog (project_id, catalog_id, updated_at);
