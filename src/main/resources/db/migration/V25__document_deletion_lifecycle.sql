ALTER TABLE private_document ADD COLUMN deletion_requested_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE private_document ADD COLUMN deletion_restore_deadline TIMESTAMP WITH TIME ZONE;
ALTER TABLE private_document ADD COLUMN purged_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE private_document ADD CONSTRAINT ck_document_deletion_window CHECK (
    deletion_state <> 'CLAIMED'
    OR (deletion_requested_at IS NOT NULL AND deletion_restore_deadline IS NOT NULL)
) NOT VALID;

ALTER TABLE private_document ADD CONSTRAINT ck_document_purged CHECK (
    deletion_state <> 'DELETED' OR purged_at IS NOT NULL
) NOT VALID;

CREATE INDEX ix_document_deletion_due ON private_document (deletion_restore_deadline)
    WHERE deletion_state = 'CLAIMED';
