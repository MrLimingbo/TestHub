CREATE TABLE functional_test_plan (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    plan_code VARCHAR(40) NOT NULL,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    description TEXT NOT NULL,
    created_by CHAR(26) NOT NULL,
    updated_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_functional_plan_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_functional_plan_code (project_id, plan_code),
    KEY idx_functional_plan_project (project_id, updated_at)
) ENGINE=InnoDB;

CREATE TABLE functional_test_plan_case (
    id CHAR(26) PRIMARY KEY,
    plan_id CHAR(26) NOT NULL,
    case_id CHAR(26) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_functional_plan_case_plan FOREIGN KEY (plan_id) REFERENCES functional_test_plan(id) ON DELETE CASCADE,
    CONSTRAINT fk_functional_plan_case_case FOREIGN KEY (case_id) REFERENCES functional_case(id),
    UNIQUE KEY uk_functional_plan_case (plan_id, case_id),
    KEY idx_functional_plan_case_order (plan_id, sort_order)
) ENGINE=InnoDB;

CREATE TABLE functional_test_run (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    plan_id CHAR(26) NOT NULL,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'NOT_STARTED',
    created_by CHAR(26) NOT NULL,
    started_at TIMESTAMP(3) NULL,
    finished_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_functional_run_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_functional_run_plan FOREIGN KEY (plan_id) REFERENCES functional_test_plan(id),
    KEY idx_functional_run_project (project_id, created_at)
) ENGINE=InnoDB;

CREATE TABLE functional_test_run_case (
    id CHAR(26) PRIMARY KEY,
    run_id CHAR(26) NOT NULL,
    case_id CHAR(26) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'NOT_RUN',
    note TEXT NOT NULL,
    executed_by CHAR(26) NULL,
    executed_at TIMESTAMP(3) NULL,
    CONSTRAINT fk_functional_run_case_run FOREIGN KEY (run_id) REFERENCES functional_test_run(id) ON DELETE CASCADE,
    CONSTRAINT fk_functional_run_case_case FOREIGN KEY (case_id) REFERENCES functional_case(id),
    UNIQUE KEY uk_functional_run_case (run_id, case_id),
    KEY idx_functional_run_case_status (run_id, status)
) ENGINE=InnoDB;

CREATE TABLE functional_test_run_step (
    id CHAR(26) PRIMARY KEY,
    run_case_id CHAR(26) NOT NULL,
    step_no INT NOT NULL,
    step_text TEXT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'NOT_RUN',
    actual_result TEXT NOT NULL,
    updated_by CHAR(26) NULL,
    updated_at TIMESTAMP(3) NULL,
    CONSTRAINT fk_functional_run_step_case FOREIGN KEY (run_case_id) REFERENCES functional_test_run_case(id) ON DELETE CASCADE,
    UNIQUE KEY uk_functional_run_step (run_case_id, step_no)
) ENGINE=InnoDB;
