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
GRANT SELECT, INSERT, UPDATE ON role_assignments, account_bindings, employee_assignments,
    enrollment_invitations, activation_admission_lock TO peoplecore_runtime;
GRANT SELECT, UPDATE ON auth_generation TO peoplecore_runtime;
GRANT SELECT, INSERT, DELETE ON activation_attempts TO peoplecore_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON activation_budget_slots TO peoplecore_runtime;
GRANT SELECT, INSERT, UPDATE ON profile_rule_sets, profile_rules, profile_rule_current_activation TO peoplecore_runtime;
GRANT SELECT, INSERT ON profile_rule_activations TO peoplecore_runtime;
GRANT SELECT, INSERT, UPDATE ON employee_contact_profile, profile_proposal, profile_assignment TO peoplecore_runtime;
GRANT SELECT, INSERT, UPDATE ON profile_revision TO peoplecore_runtime;
GRANT SELECT, INSERT ON profile_revision_change, profile_decision, profile_route_evaluation TO peoplecore_runtime;
GRANT SELECT, INSERT, UPDATE ON work_schedule_revision, holiday_calendar_revision TO peoplecore_runtime;
GRANT SELECT, INSERT, UPDATE ON attendance_account TO peoplecore_runtime;
GRANT SELECT, INSERT ON work_schedule_day, holiday_date, attendance_occurrence TO peoplecore_runtime;
GRANT SELECT, INSERT, UPDATE ON employee_schedule_assignment, employee_calendar_assignment TO peoplecore_runtime;
GRANT SELECT, INSERT ON document_version TO peoplecore_runtime;
GRANT SELECT, INSERT, UPDATE ON document_account, private_document, document_upload_attempt, document_verification_attempt, document_hold TO peoplecore_runtime;
GRANT SELECT, INSERT, UPDATE ON reimbursement_account, reimbursement_claim, reimbursement_revision, reimbursement_review_assignment, reimbursement_category_revision TO peoplecore_runtime;
GRANT SELECT, INSERT ON reimbursement_evidence, reimbursement_decision,
    reimbursement_payment, reimbursement_payment_correction, reimbursement_history TO peoplecore_runtime;
GRANT SELECT, INSERT, DELETE ON reimbursement_draft_evidence TO peoplecore_runtime;
GRANT SELECT, INSERT, UPDATE ON payslip_batch, payslip_batch_revision, payslip_current TO peoplecore_runtime;
GRANT SELECT, INSERT ON payslip_batch_item, payslip_publication TO peoplecore_runtime;
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
GRANT SELECT, INSERT ON inbox_events, event_quarantine TO peoplecore_runtime;
GRANT SELECT, INSERT ON event_replays, event_replay_items TO peoplecore_runtime;
GRANT UPDATE (status) ON event_replays TO peoplecore_runtime;
GRANT UPDATE (status, lease_generation, lease_until, completed_at) ON event_replay_items TO peoplecore_runtime;
GRANT SELECT, UPDATE ON event_transport_budget TO peoplecore_runtime;
GRANT SELECT, INSERT, UPDATE ON notification_destination, notification_rate_bucket TO peoplecore_runtime;
GRANT SELECT, INSERT ON invitation_delivery_source TO peoplecore_runtime;
GRANT UPDATE (ciphertext) ON invitation_delivery_source TO peoplecore_runtime;
GRANT SELECT, INSERT ON notification_intent, notification_attempt, notification_evidence,
    notification_operation, notification_attention, notification_destination_operation TO peoplecore_runtime;
GRANT SELECT, INSERT ON notification_delivery TO peoplecore_runtime;
GRANT SELECT, INSERT ON notification_attention_alert TO peoplecore_runtime;
GRANT UPDATE (lease_generation, lease_until, next_attempt_at, attempt_count, delivered_at)
    ON notification_attention_alert TO peoplecore_runtime;
GRANT UPDATE (state, version, lease_generation, lease_ends_at, next_attempt_at, attempt_count,
    manual_attempt, provider_message_id, error_code, updated_at) ON notification_delivery TO peoplecore_runtime;
GRANT SELECT, INSERT ON report_export_job, report_export_candidate TO peoplecore_runtime;
GRANT UPDATE (status, data_as_of, authorization_generation, next_boundary, lease_generation, lease_until,
    candidate_id, byte_size, sha256, reason_code) ON report_export_job TO peoplecore_runtime;
GRANT UPDATE (uploaded_at, byte_size, sha256, deleted_at, next_reconcile_at) ON report_export_candidate TO peoplecore_runtime;
GRANT SELECT, INSERT ON service_recovery_incident, service_recovery_exception,
    service_recovery_approval, service_recovery_operation TO peoplecore_runtime;
GRANT UPDATE (status, reconciled_at, reopened_at, rto_seconds) ON service_recovery_incident TO peoplecore_runtime;
GRANT UPDATE (status, resolved_by, resolution_reason, resolved_at) ON service_recovery_exception TO peoplecore_runtime;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO peoplecore_runtime;
COMMIT;
