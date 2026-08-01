ALTER TABLE functional_defect
    ADD COLUMN due_date DATE NULL AFTER title,
    ADD COLUMN bug_type VARCHAR(32) NOT NULL DEFAULT 'CODE_ERROR' AFTER due_date,
    ADD COLUMN keywords VARCHAR(500) NOT NULL DEFAULT '' AFTER bug_type,
    ADD COLUMN browsers VARCHAR(255) NOT NULL DEFAULT '' AFTER keywords,
    ADD COLUMN operating_systems VARCHAR(255) NOT NULL DEFAULT '' AFTER browsers,
    ADD COLUMN cc_user_ids VARCHAR(2000) NOT NULL DEFAULT '' AFTER operating_systems;
