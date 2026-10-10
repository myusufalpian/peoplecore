-- V4__identity_enrollment_hardening.sql
-- Enrollment hardening: expected verified identity, assignment public IDs,
-- and active-only binding uniqueness (history rows are retained).

-- 1. Expected independently verified identity on invitations (RFC 6.4)
ALTER TABLE enrollment_invitations
    ADD COLUMN IF NOT EXISTS expected_issuer VARCHAR(255),
    ADD COLUMN IF NOT EXISTS expected_subject VARCHAR(255);

UPDATE enrollment_invitations
SET expected_issuer = intended_identity_ref,
    expected_subject = intended_identity_ref
WHERE expected_issuer IS NULL OR expected_subject IS NULL;

ALTER TABLE enrollment_invitations
    ALTER COLUMN expected_issuer SET NOT NULL,
    ALTER COLUMN expected_subject SET NOT NULL;

-- 2. Public UUID for employee assignments (internal bigint never leaves the API)
ALTER TABLE employee_assignments
    ADD COLUMN IF NOT EXISTS public_id UUID;

UPDATE employee_assignments
SET public_id = gen_random_uuid()
WHERE public_id IS NULL;

ALTER TABLE employee_assignments
    ALTER COLUMN public_id SET NOT NULL;

ALTER TABLE employee_assignments
    ADD CONSTRAINT uk_employee_assignments_public_id UNIQUE (public_id);

-- 3. Uniqueness applies to active bindings only; revoked rows stay as history
ALTER TABLE account_bindings
    DROP CONSTRAINT IF EXISTS uk_account_bindings_employee;

ALTER TABLE account_bindings
    DROP CONSTRAINT IF EXISTS uk_account_bindings_account;

ALTER TABLE account_bindings
    DROP CONSTRAINT IF EXISTS uk_account_bindings_issuer_subject;

CREATE UNIQUE INDEX IF NOT EXISTS uk_active_binding_employee
    ON account_bindings (employee_id) WHERE revoked_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_active_binding_account
    ON account_bindings (account_id) WHERE revoked_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_active_binding_identity
    ON account_bindings (oidc_issuer, oidc_subject) WHERE revoked_at IS NULL;
