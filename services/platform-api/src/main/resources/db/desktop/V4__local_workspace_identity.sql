-- Installation-local identity is created only by an authenticated desktop launcher.
CREATE TABLE desktop_workspace_identity (
    singleton_id INT PRIMARY KEY CHECK (singleton_id = 1),
    user_id CHAR(26),
    FOREIGN KEY (user_id) REFERENCES user_account(id)
);
INSERT INTO desktop_workspace_identity(singleton_id,user_id) VALUES(1,NULL);
