\set ON_ERROR_STOP on
\getenv runtime_password PEOPLECORE_RUNTIME_DB_PASSWORD
BEGIN;
SELECT format('CREATE ROLE peoplecore_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD %L', :'runtime_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'peoplecore_runtime') \gexec
ALTER ROLE peoplecore_runtime NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
SELECT format('ALTER ROLE peoplecore_runtime PASSWORD %L', :'runtime_password') \gexec
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO peoplecore_runtime;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM peoplecore_runtime;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM peoplecore_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON command_receipts, outbox_events, user_accounts, employees TO peoplecore_runtime;
GRANT SELECT, INSERT, UPDATE ON profile_rule_sets, profile_rules, profile_rule_current_activation TO peoplecore_runtime;
GRANT SELECT, INSERT ON profile_rule_activations TO peoplecore_runtime;
GRANT SELECT, INSERT, UPDATE ON employee_contact_profile, profile_proposal, profile_assignment TO peoplecore_runtime;
GRANT SELECT, INSERT, UPDATE ON profile_revision TO peoplecore_runtime;
GRANT SELECT, INSERT ON profile_revision_change, profile_decision, profile_route_evaluation TO peoplecore_runtime;
GRANT SELECT, INSERT, UPDATE ON work_schedule_revision, holiday_calendar_revision TO peoplecore_runtime;
GRANT SELECT, INSERT, UPDATE ON attendance_account TO peoplecore_runtime;
GRANT SELECT, INSERT ON work_schedule_day, holiday_date, attendance_occurrence TO peoplecore_runtime;
GRANT SELECT, INSERT, UPDATE ON employee_schedule_assignment, employee_calendar_assignment TO peoplecore_runtime;
REVOKE ALL ON audit_events FROM peoplecore_runtime;
GRANT SELECT, INSERT ON clock_event, attendance_effective_revision, attendance_correction_revision,
    attendance_correction_assignment, attendance_correction_decision, attendance_rule, attendance_rule_activation TO peoplecore_runtime;
GRANT SELECT, INSERT, UPDATE ON attendance_correction, attendance_rule_set TO peoplecore_runtime;
GRANT SELECT, INSERT, UPDATE ON leave_account, leave_period, leave_reservation TO peoplecore_runtime;
GRANT SELECT ON leave_policy_revision TO peoplecore_runtime;
GRANT SELECT, INSERT ON leave_ledger, leave_rule_set, leave_rule, leave_rule_activation, leave_revision,
    leave_portion, leave_decision, leave_recovery, leave_cancellation_decision, leave_history, leave_route_evaluation TO peoplecore_runtime;
GRANT SELECT, INSERT, UPDATE ON leave_rule_current, leave_request, leave_occupancy,
    leave_review_assignment, leave_cancellation, leave_cancellation_assignment TO peoplecore_runtime;

GRANT SELECT, INSERT ON audit_events TO peoplecore_runtime;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO peoplecore_runtime;
COMMIT;
