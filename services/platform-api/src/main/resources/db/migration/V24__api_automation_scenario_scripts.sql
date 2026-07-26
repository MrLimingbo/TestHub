ALTER TABLE api_automation_scenario
    ADD COLUMN pre_request_script MEDIUMTEXT NULL AFTER variables,
    ADD COLUMN post_response_script MEDIUMTEXT NULL AFTER pre_request_script;
