-- V3__identity_organization.sql
-- Identity and organization (T08/T09/T10): roles, authorization generation, effective assignments,
-- enrollment invitations and account bindings.

-- 1. Role assignments (HRIS-owned roles, effective-dated history)
CREATE TABLE IF NOT EXISTS role_assignments (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES user_accounts (id),
    role VARCHAR(32) NOT NULL,
    scope VARCHAR(128),
    effective_from TIMESTAMP WITH TIME ZONE NOT NULL,
    effective_to TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_role_assignments_account ON role_assignments (account_id);

-- 2. Authorization generation (conservative org-wide invalidation counter, RFC 7.8)
CREATE TABLE IF NOT EXISTS auth_generation (
    id INT PRIMARY KEY CHECK (id = 1),
    generation BIGINT NOT NULL DEFAULT 0
);

INSERT INTO auth_generation (id, generation) VALUES (1, 0) ON CONFLICT DO NOTHING;

-- 3. Effective-dated employee assignments (RFC 7.7)
CREATE TABLE IF NOT EXISTS employee_assignments (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id BIGINT NOT NULL REFERENCES employees (id),
    org_unit VARCHAR(128) NOT NULL,
    manager_employee_id BIGINT REFERENCES employees (id),
    job_level VARCHAR(64) NOT NULL,
    valid_from TIMESTAMP WITH TIME ZONE NOT NULL,
    valid_to TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_employee_assignments_employee ON employee_assignments (employee_id, valid_from);

-- 4. Enrollment invitations (RFC 6.4)
CREATE TABLE IF NOT EXISTS enrollment_invitations (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    employee_id BIGINT NOT NULL REFERENCES employees (id),
    intended_identity_ref VARCHAR(255) NOT NULL,
    secret_hash VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ISSUED',
    delivery_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    issued_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_enrollment_invitations_employee ON enrollment_invitations (employee_id, status);

-- 5. Account bindings (one active account per employee, one employee per account)
CREATE TABLE IF NOT EXISTS account_bindings (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id BIGINT NOT NULL REFERENCES employees (id),
    employee_public_id UUID NOT NULL,
    account_id BIGINT NOT NULL REFERENCES user_accounts (id),
    oidc_issuer VARCHAR(255) NOT NULL,
    oidc_subject VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_account_bindings_employee UNIQUE (employee_id),
    CONSTRAINT uk_account_bindings_account UNIQUE (account_id),
    CONSTRAINT uk_account_bindings_issuer_subject UNIQUE (oidc_issuer, oidc_subject)
);
