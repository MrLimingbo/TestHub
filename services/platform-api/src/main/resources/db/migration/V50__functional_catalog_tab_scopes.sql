ALTER TABLE functional_catalog
    ADD COLUMN scope VARCHAR(24) NOT NULL DEFAULT 'REQUIREMENTS' AFTER project_id;

ALTER TABLE functional_catalog
    DROP INDEX uk_functional_catalog_name,
    DROP INDEX idx_functional_catalog_project,
    ADD UNIQUE KEY uk_functional_catalog_scope_name (project_id, scope, parent_id, name),
    ADD KEY idx_functional_catalog_scope (project_id, scope, parent_id, sort_order);

INSERT INTO functional_catalog
    (id, organization_id, project_id, scope, parent_id, name, sort_order, created_by, created_at)
SELECT
    CONCAT('FCAS', UPPER(SUBSTRING(SHA1(id), 1, 22))),
    organization_id,
    project_id,
    'CASES',
    NULL,
    name,
    sort_order,
    created_by,
    created_at
FROM functional_catalog
WHERE scope = 'REQUIREMENTS';

UPDATE functional_catalog cases_catalog
JOIN functional_catalog requirement_catalog
  ON cases_catalog.id = CONCAT('FCAS', UPPER(SUBSTRING(SHA1(requirement_catalog.id), 1, 22)))
SET cases_catalog.parent_id = CASE
    WHEN requirement_catalog.parent_id IS NULL THEN NULL
    ELSE CONCAT('FCAS', UPPER(SUBSTRING(SHA1(requirement_catalog.parent_id), 1, 22)))
END
WHERE cases_catalog.scope = 'CASES'
  AND requirement_catalog.scope = 'REQUIREMENTS';

UPDATE functional_case test_case
JOIN functional_catalog requirement_catalog
  ON requirement_catalog.id = test_case.catalog_id
 AND requirement_catalog.scope = 'REQUIREMENTS'
JOIN functional_catalog cases_catalog
  ON cases_catalog.id = CONCAT('FCAS', UPPER(SUBSTRING(SHA1(requirement_catalog.id), 1, 22)))
 AND cases_catalog.scope = 'CASES'
SET test_case.catalog_id = cases_catalog.id;
