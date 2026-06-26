-- Adds TECHNICIAN to the users.role check constraint, between OPERATOR and VIEWER
-- on the privilege ladder defined by com.huylq.iotprojectserver.security.Role.
--
-- Postgres auto-names a column-level CHECK as <table>_<column>_check, so dropping
-- it by that name is safe here. If a future migration renames or replaces this
-- constraint, this DROP / ADD pair becomes the place to update.

ALTER TABLE users DROP CONSTRAINT users_role_check;

ALTER TABLE users ADD CONSTRAINT users_role_check
    CHECK (role IN ('SUPER_ADMIN', 'ADMIN', 'OPERATOR', 'TECHNICIAN', 'VIEWER'));
