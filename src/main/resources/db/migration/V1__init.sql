CREATE TABLE scan_run (
    id UUID PRIMARY KEY,
    repository_id VARCHAR(128) NOT NULL,
    allowed_branch VARCHAR(256) NOT NULL,
    base_sha VARCHAR(80),
    state VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64),
    reason_message VARCHAR(2000),
    actor VARCHAR(256) NOT NULL,
    idempotency_key VARCHAR(200),
    tool_versions TEXT,
    evidence TEXT,
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    lease_owner VARCHAR(128),
    lease_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ
);

CREATE TABLE scanner_report (
    id UUID PRIMARY KEY,
    scan_run_id UUID REFERENCES scan_run (id),
    remediation_run_id UUID,
    phase VARCHAR(32) NOT NULL,
    raw_output TEXT NOT NULL,
    scanner_version VARCHAR(64),
    exit_code INT,
    complete BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE finding (
    id UUID PRIMARY KEY,
    scan_run_id UUID NOT NULL REFERENCES scan_run (id),
    repository_id VARCHAR(128) NOT NULL,
    base_sha VARCHAR(80) NOT NULL,
    coordinate VARCHAR(512) NOT NULL,
    group_id VARCHAR(256) NOT NULL,
    artifact_id VARCHAR(256) NOT NULL,
    version VARCHAR(128) NOT NULL,
    scope VARCHAR(32) NOT NULL,
    direct_dependency BOOLEAN NOT NULL,
    pom_path VARCHAR(512) NOT NULL,
    version_source VARCHAR(32) NOT NULL,
    advisory_ids TEXT NOT NULL,
    primary_advisory_id VARCHAR(128),
    aliases TEXT NOT NULL,
    severity VARCHAR(128) NOT NULL,
    affected_ranges TEXT NOT NULL,
    summary VARCHAR(4000),
    eligibility VARCHAR(32) NOT NULL,
    eligibility_reasons TEXT NOT NULL,
    candidates TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE remediation_run (
    id UUID PRIMARY KEY,
    repository_id VARCHAR(128) NOT NULL,
    finding_id UUID NOT NULL REFERENCES finding (id),
    scan_run_id UUID NOT NULL REFERENCES scan_run (id),
    base_sha VARCHAR(80) NOT NULL,
    coordinate VARCHAR(512) NOT NULL,
    advisory_id VARCHAR(128) NOT NULL,
    previous_version VARCHAR(128) NOT NULL,
    candidate_version VARCHAR(128),
    state VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64),
    reason_message VARCHAR(2000),
    dedup_key VARCHAR(1024) NOT NULL,
    patch_diff TEXT,
    patch_hash VARCHAR(128),
    patched_pom TEXT,
    recommendation TEXT,
    verification TEXT,
    pr_url VARCHAR(1024),
    pr_number INT,
    branch_name VARCHAR(512),
    publish_attempted BOOLEAN NOT NULL DEFAULT FALSE,
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    actor VARCHAR(256) NOT NULL,
    idempotency_key VARCHAR(200),
    model_usage TEXT,
    lease_owner VARCHAR(128),
    lease_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX remediation_dedup_active
    ON remediation_run (dedup_key)
    WHERE state NOT IN ('FAILED', 'CANCELLED', 'INTERRUPTED');

CREATE TABLE idempotency_record (
    id UUID PRIMARY KEY,
    actor VARCHAR(256) NOT NULL,
    operation VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    request_hash VARCHAR(128) NOT NULL,
    response_status INT NOT NULL,
    response_body TEXT NOT NULL,
    resource_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (actor, operation, idempotency_key)
);

CREATE TABLE audit_event (
    id UUID PRIMARY KEY,
    actor VARCHAR(256) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    run_id UUID,
    outcome VARCHAR(4000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE queue_control (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    paused BOOLEAN NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by VARCHAR(256) NOT NULL
);

INSERT INTO queue_control (id, paused, updated_at, updated_by)
VALUES (1, FALSE, NOW() AT TIME ZONE 'utc', 'system');

CREATE INDEX scan_run_state_idx ON scan_run (state, created_at);
CREATE INDEX remediation_state_idx ON remediation_run (state, created_at);
CREATE INDEX finding_scan_idx ON finding (scan_run_id);
CREATE INDEX audit_run_idx ON audit_event (run_id, created_at);
