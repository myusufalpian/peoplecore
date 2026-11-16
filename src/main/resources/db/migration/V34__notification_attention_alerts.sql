INSERT INTO notification_attention(public_id,event_id,delivery_id,code,created_at)
    SELECT gen_random_uuid(),d.event_id,d.public_id,d.state,d.updated_at FROM notification_delivery d
    WHERE d.state IN ('UNKNOWN','FAILED')
    AND NOT EXISTS (SELECT 1 FROM notification_attention a WHERE a.delivery_id=d.public_id);

CREATE TABLE notification_attention_alert (
    attention_id UUID PRIMARY KEY REFERENCES notification_attention(public_id),
    lease_generation BIGINT NOT NULL DEFAULT 0 CHECK (lease_generation>=0),
    lease_until TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count>=0),
    delivered_at TIMESTAMPTZ
        CHECK (delivered_at IS NULL OR lease_until IS NULL)
);
CREATE INDEX notification_attention_alert_due ON notification_attention_alert(next_attempt_at)
    WHERE delivered_at IS NULL;
INSERT INTO notification_attention_alert(attention_id,next_attempt_at)
    SELECT public_id,created_at FROM notification_attention;

CREATE FUNCTION protect_notification_attention_alert() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.attention_id IS DISTINCT FROM OLD.attention_id OR OLD.delivered_at IS NOT NULL THEN
        RAISE EXCEPTION 'Notification alert identity and acknowledged outcome are immutable' USING ERRCODE='42501';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER notification_attention_alert_identity BEFORE UPDATE ON notification_attention_alert
    FOR EACH ROW EXECUTE FUNCTION protect_notification_attention_alert();
CREATE TRIGGER notification_attention_alert_no_delete BEFORE DELETE OR TRUNCATE ON notification_attention_alert
    FOR EACH STATEMENT EXECUTE FUNCTION reject_notification_evidence_mutation();
