-- Preserve receipt identity and metadata so a repeated command cannot issue a new secret.
DO $$
DECLARE
    receipt RECORD;
    payload JSONB;
    compromised BOOLEAN := FALSE;
BEGIN
    FOR receipt IN SELECT id, result_payload FROM command_receipts
        WHERE command_type IN ('INVITATION_ISSUE', 'INVITATION_REISSUE')
          AND result_payload IS NOT NULL
    LOOP
        BEGIN
            payload := receipt.result_payload::JSONB;
            IF jsonb_typeof(payload) <> 'object' OR payload = 'null'::JSONB THEN
                UPDATE command_receipts SET result_payload = NULL WHERE id = receipt.id;
                compromised := TRUE;
            ELSIF payload ? 'secret' THEN
                UPDATE command_receipts SET result_payload = (payload - 'secret')::TEXT
                    WHERE id = receipt.id;
                compromised := TRUE;
            END IF;
        EXCEPTION WHEN invalid_text_representation THEN
            -- An unverifiable receipt must stay reserved and must never be replayed.
            UPDATE command_receipts SET result_payload = NULL WHERE id = receipt.id;
            compromised := TRUE;
        END;
    END LOOP;
    IF compromised THEN
        -- The old payload is not trusted to identify every exposed invitation.
        UPDATE enrollment_invitations SET status = 'REVOKED', version = version + 1
            WHERE status = 'ISSUED';
        UPDATE auth_generation SET generation = generation + 1 WHERE id = 1;
    END IF;
END $$;
