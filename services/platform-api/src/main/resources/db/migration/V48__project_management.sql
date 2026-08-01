ALTER TABLE project
    ADD COLUMN description VARCHAR(2000) NOT NULL DEFAULT '' AFTER status,
    ADD COLUMN created_by CHAR(26) NULL AFTER description,
    ADD COLUMN updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) AFTER created_at,
    ADD CONSTRAINT fk_project_creator FOREIGN KEY (created_by) REFERENCES user_account(id);
