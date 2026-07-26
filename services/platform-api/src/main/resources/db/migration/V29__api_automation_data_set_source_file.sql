ALTER TABLE api_automation_data_set
    ADD COLUMN source_file_id CHAR(26) NULL AFTER checksum,
    ADD CONSTRAINT fk_api_automation_data_set_source_file
        FOREIGN KEY (source_file_id) REFERENCES file_object(id) ON DELETE SET NULL,
    ADD KEY idx_api_automation_data_set_source_file (source_file_id);
