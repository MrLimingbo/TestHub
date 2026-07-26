ALTER TABLE notification_template
    ADD COLUMN target_url VARCHAR(2048) NULL AFTER body_template,
    ADD COLUMN signing_secret_ciphertext TEXT NULL AFTER target_url;

CREATE TABLE quality_report_schedule (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    name VARCHAR(160) NOT NULL,
    project_ids JSON NOT NULL,
    cron_expression VARCHAR(160) NOT NULL,
    timezone VARCHAR(64) NOT NULL,
    period_days INT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_run_id CHAR(26) NULL,
    last_run_status VARCHAR(24) NULL,
    last_run_at TIMESTAMP(3) NULL,
    created_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_quality_report_schedule_org FOREIGN KEY (organization_id) REFERENCES organization(id),
    CONSTRAINT fk_quality_report_schedule_creator FOREIGN KEY (created_by) REFERENCES user_account(id),
    UNIQUE KEY uk_quality_report_schedule_org_name (organization_id, name),
    KEY idx_quality_report_schedule_enabled (enabled, updated_at)
) ENGINE=InnoDB;

CREATE TABLE quality_report_schedule_run (
    id CHAR(26) PRIMARY KEY,
    schedule_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_ids JSON NOT NULL,
    range_from DATE NOT NULL,
    range_to DATE NOT NULL,
    status VARCHAR(24) NOT NULL,
    delivery_count INT NOT NULL DEFAULT 0,
    summary JSON NOT NULL,
    error_message VARCHAR(512) NULL,
    started_at TIMESTAMP(3) NOT NULL,
    finished_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_quality_report_schedule_run_schedule FOREIGN KEY (schedule_id) REFERENCES quality_report_schedule(id) ON DELETE CASCADE,
    CONSTRAINT fk_quality_report_schedule_run_org FOREIGN KEY (organization_id) REFERENCES organization(id),
    KEY idx_quality_report_schedule_run_schedule (schedule_id, started_at DESC),
    KEY idx_quality_report_schedule_run_org (organization_id, started_at DESC)
) ENGINE=InnoDB;

CREATE TABLE notification_delivery (
    id CHAR(26) PRIMARY KEY,
    schedule_run_id CHAR(26) NOT NULL,
    template_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    template_name VARCHAR(120) NOT NULL,
    event_code VARCHAR(80) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    target_url VARCHAR(2048) NOT NULL,
    signing_secret_ciphertext TEXT NOT NULL,
    subject VARCHAR(255) NOT NULL,
    message TEXT NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(24) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    delivered_at TIMESTAMP(3) NULL,
    response_status INT NULL,
    last_error VARCHAR(512) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_notification_delivery_run FOREIGN KEY (schedule_run_id) REFERENCES quality_report_schedule_run(id) ON DELETE CASCADE,
    CONSTRAINT fk_notification_delivery_org FOREIGN KEY (organization_id) REFERENCES organization(id),
    CONSTRAINT fk_notification_delivery_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_notification_delivery_dedupe (schedule_run_id, template_id, event_code),
    KEY idx_notification_delivery_dispatch (status, next_attempt_at, created_at),
    KEY idx_notification_delivery_project (project_id, created_at DESC)
) ENGINE=InnoDB;
