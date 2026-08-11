CREATE TABLE project_environment (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    name VARCHAR(160) NOT NULL,
    environment_code VARCHAR(64) NOT NULL,
    base_url VARCHAR(2000) NOT NULL DEFAULT '',
    description VARCHAR(1200) NOT NULL DEFAULT '',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    version INT NOT NULL DEFAULT 1,
    created_by CHAR(26) NOT NULL,
    updated_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_project_environment_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_project_environment_code (project_id, environment_code),
    KEY idx_project_environment_project_updated (project_id, updated_at)
) ENGINE=InnoDB;

INSERT INTO project_environment
    (id, organization_id, project_id, name, environment_code, base_url, description,
     enabled, is_default, version, created_by, updated_by)
SELECT UPPER(LEFT(SHA2(CONCAT(p.id, ':', source.environment_code), 256), 26)),
       p.organization_id,
       p.id,
       source.environment_code,
       source.environment_code,
       COALESCE(MAX(CASE
           WHEN pp.secret = FALSE AND pp.parameter_key IN ('BASE_URL', 'API_BASE_URL', 'baseUrl')
               THEN pp.parameter_value
           ELSE NULL
       END), ''),
       '',
       TRUE,
       FALSE,
       1,
       COALESCE(p.created_by, source.updated_by,
                (SELECT MIN(m.user_id) FROM membership m WHERE m.organization_id = p.organization_id),
                (SELECT MIN(u.id) FROM user_account u)),
       COALESCE(source.updated_by, p.created_by,
                (SELECT MIN(m.user_id) FROM membership m WHERE m.organization_id = p.organization_id),
                (SELECT MIN(u.id) FROM user_account u))
FROM project p
JOIN (
    SELECT raw.project_id, raw.environment_code, MAX(raw.updated_by) AS updated_by
    FROM (
        SELECT project_id, environment_code, updated_by FROM project_parameter
        UNION ALL
        SELECT project_id, environment_code, created_by AS updated_by FROM api_automation_scenario
        UNION ALL
        SELECT project_id, environment_code, created_by AS updated_by FROM api_automation_execution_profile
        UNION ALL
        SELECT project_id, environment_code, updated_by
        FROM api_definition_case
        WHERE environment_code IS NOT NULL AND environment_code <> ''
    ) raw
    GROUP BY raw.project_id, raw.environment_code
) source ON source.project_id = p.id
LEFT JOIN project_parameter pp
       ON pp.project_id = p.id AND pp.environment_code = source.environment_code
WHERE p.status <> 'DELETED'
GROUP BY p.id, p.organization_id, p.created_by, source.environment_code, source.updated_by;

INSERT INTO project_environment
    (id, organization_id, project_id, name, environment_code, base_url, description,
     enabled, is_default, version, created_by, updated_by)
SELECT UPPER(LEFT(SHA2(CONCAT(p.id, ':test'), 256), 26)),
       p.organization_id,
       p.id,
       '默认测试环境',
       'test',
       '',
       '项目创建时生成的默认环境',
       TRUE,
       TRUE,
       1,
       COALESCE(p.created_by,
                (SELECT MIN(m.user_id) FROM membership m WHERE m.organization_id = p.organization_id),
                (SELECT MIN(u.id) FROM user_account u)),
       COALESCE(p.created_by,
                (SELECT MIN(m.user_id) FROM membership m WHERE m.organization_id = p.organization_id),
                (SELECT MIN(u.id) FROM user_account u))
FROM project p
WHERE p.status <> 'DELETED'
  AND NOT EXISTS (SELECT 1 FROM project_environment pe WHERE pe.project_id = p.id);

UPDATE project_environment pe
JOIN (
    SELECT project_id,
           SUBSTRING(MIN(CONCAT(
               CASE
                   WHEN environment_code = 'test' THEN '0'
                   WHEN environment_code = 'default' THEN '1'
                   ELSE '2'
               END,
               environment_code
           )), 2) AS default_code
    FROM project_environment
    GROUP BY project_id
) selected ON selected.project_id = pe.project_id
SET pe.is_default = (pe.environment_code = selected.default_code);
