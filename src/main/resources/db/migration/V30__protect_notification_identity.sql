CREATE FUNCTION reject_notification_evidence_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Notification evidence is append-only' USING ERRCODE='42501';
END;
$$;
CREATE TRIGGER notification_intent_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON notification_intent
    FOR EACH STATEMENT EXECUTE FUNCTION reject_notification_evidence_mutation();
CREATE TRIGGER notification_attempt_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON notification_attempt
    FOR EACH STATEMENT EXECUTE FUNCTION reject_notification_evidence_mutation();
CREATE TRIGGER notification_evidence_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON notification_evidence
    FOR EACH STATEMENT EXECUTE FUNCTION reject_notification_evidence_mutation();
CREATE TRIGGER notification_operation_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON notification_operation
    FOR EACH STATEMENT EXECUTE FUNCTION reject_notification_evidence_mutation();
CREATE TRIGGER notification_attention_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON notification_attention
    FOR EACH STATEMENT EXECUTE FUNCTION reject_notification_evidence_mutation();
CREATE FUNCTION protect_notification_delivery_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP<>'UPDATE' OR NEW.public_id IS DISTINCT FROM OLD.public_id
        OR NEW.original_delivery_id IS DISTINCT FROM OLD.original_delivery_id
        OR NEW.event_id IS DISTINCT FROM OLD.event_id OR NEW.event_type IS DISTINCT FROM OLD.event_type
        OR NEW.aggregate_public_id IS DISTINCT FROM OLD.aggregate_public_id
        OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at
        OR NEW.employee_public_id IS DISTINCT FROM OLD.employee_public_id
        OR NEW.account_public_id IS DISTINCT FROM OLD.account_public_id
        OR NEW.template IS DISTINCT FROM OLD.template OR NEW.template_version IS DISTINCT FROM OLD.template_version
        OR NEW.channel IS DISTINCT FROM OLD.channel OR NEW.eligibility_type IS DISTINCT FROM OLD.eligibility_type
        OR NEW.assignment_public_id IS DISTINCT FROM OLD.assignment_public_id
        OR NEW.destination_id IS DISTINCT FROM OLD.destination_id
        OR NEW.destination_version IS DISTINCT FROM OLD.destination_version
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Notification identity is immutable' USING ERRCODE='42501';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER notification_delivery_identity BEFORE UPDATE OR DELETE ON notification_delivery
    FOR EACH ROW EXECUTE FUNCTION protect_notification_delivery_identity();
CREATE TRIGGER notification_delivery_no_truncate BEFORE TRUNCATE ON notification_delivery
    FOR EACH STATEMENT EXECUTE FUNCTION reject_notification_evidence_mutation();
CREATE FUNCTION protect_invitation_delivery_source() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP<>'UPDATE' OR NEW.invitation_public_id IS DISTINCT FROM OLD.invitation_public_id
        OR NEW.employee_public_id IS DISTINCT FROM OLD.employee_public_id
        OR NEW.destination_id IS DISTINCT FROM OLD.destination_id
        OR NEW.destination_version IS DISTINCT FROM OLD.destination_version
        OR NEW.key_id IS DISTINCT FROM OLD.key_id OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR NEW.ciphertext IS NOT NULL THEN
        RAISE EXCEPTION 'Invitation delivery source is immutable except secret disposal' USING ERRCODE='42501';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER invitation_delivery_source_identity BEFORE UPDATE OR DELETE ON invitation_delivery_source
    FOR EACH ROW EXECUTE FUNCTION protect_invitation_delivery_source();
CREATE TRIGGER invitation_delivery_source_no_truncate BEFORE TRUNCATE ON invitation_delivery_source
    FOR EACH STATEMENT EXECUTE FUNCTION reject_notification_evidence_mutation();
CREATE TRIGGER notification_destination_operation_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON notification_destination_operation
    FOR EACH STATEMENT EXECUTE FUNCTION reject_notification_evidence_mutation();
