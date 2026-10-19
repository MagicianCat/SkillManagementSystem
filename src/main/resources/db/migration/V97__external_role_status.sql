SET @external_role_status_column_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'external_role'
      AND column_name = 'status'
);

SET @external_role_status_ddl = IF(
    @external_role_status_column_exists = 0,
    'ALTER TABLE external_role ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT ''ACTIVE'' AFTER role_name',
    'SELECT 1'
);

PREPARE external_role_status_statement FROM @external_role_status_ddl;
EXECUTE external_role_status_statement;
DEALLOCATE PREPARE external_role_status_statement;

UPDATE external_role SET status = 'ACTIVE' WHERE status IS NULL OR status = '';
