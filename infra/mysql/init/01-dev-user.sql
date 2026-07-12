-- The Docker image creates testhub@% from MYSQL_USER. Colima port forwarding
-- can resolve host connections as localhost, so keep a matching local account
-- for the documented local development connection string as well.
CREATE USER IF NOT EXISTS 'testhub'@'localhost' IDENTIFIED BY 'testhub_dev_password';
GRANT ALL PRIVILEGES ON testhub.* TO 'testhub'@'localhost';
FLUSH PRIVILEGES;
