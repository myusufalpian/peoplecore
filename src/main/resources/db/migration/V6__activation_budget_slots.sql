-- V6__activation_budget_slots.sql
-- Atomic activation admission budgets. Each reserve runs in a single short
-- transaction that upserts and row-locks its budget rows in a stable key order,
-- so concurrent admissions serialize instead of racing a count check.

CREATE TABLE IF NOT EXISTS activation_budget_slots (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    scope VARCHAR(32) NOT NULL,
    ref_a VARCHAR(320) NOT NULL,
    ref_b VARCHAR(320) NOT NULL,
    window_start TIMESTAMP WITH TIME ZONE NOT NULL,
    used INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_activation_budget_slots UNIQUE (scope, ref_a, ref_b)
);

CREATE INDEX IF NOT EXISTS idx_activation_budget_window
    ON activation_budget_slots (window_start);

-- Single-row admission mutex: every reserve locks this row first so concurrent
-- admissions serialize without dialect-specific upsert statements.
CREATE TABLE IF NOT EXISTS activation_admission_lock (
    id INT PRIMARY KEY CHECK (id = 1)
);

INSERT INTO activation_admission_lock (id) VALUES (1) ON CONFLICT DO NOTHING;
