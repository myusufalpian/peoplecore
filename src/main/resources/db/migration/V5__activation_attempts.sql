-- V5__activation_attempts.sql
-- Bounded activation attempt accounting (RFC 6.4). Rows survive command rollback
-- because they are written in a separate transaction; a new invitation resets
-- the budget, so throttling can never permanently lock out a legitimate recipient.

CREATE TABLE IF NOT EXISTS activation_attempts (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    invitation_public_id UUID NOT NULL,
    principal_issuer VARCHAR(255) NOT NULL,
    principal_subject VARCHAR(255) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_activation_attempts_invitation
    ON activation_attempts (invitation_public_id, created_at);
