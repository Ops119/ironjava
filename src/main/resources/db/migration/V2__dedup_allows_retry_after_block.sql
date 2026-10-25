DROP INDEX remediation_dedup_active;

CREATE UNIQUE INDEX remediation_dedup_active
    ON remediation_run (dedup_key)
    WHERE state NOT IN ('FAILED', 'CANCELLED', 'INTERRUPTED', 'BLOCKED');
