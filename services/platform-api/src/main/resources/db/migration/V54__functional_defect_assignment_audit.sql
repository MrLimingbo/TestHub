ALTER TABLE functional_defect
    ADD COLUMN assigned_by CHAR(26) NULL AFTER assignee_id,
    ADD CONSTRAINT fk_functional_defect_assigned_by FOREIGN KEY (assigned_by) REFERENCES user_account(id);

UPDATE functional_defect
SET assigned_by = created_by
WHERE assignee_id IS NOT NULL;
