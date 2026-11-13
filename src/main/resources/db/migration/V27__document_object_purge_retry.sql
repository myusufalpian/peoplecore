ALTER TABLE private_document ADD COLUMN objects_purged_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE private_document ADD CONSTRAINT ck_document_objects_purged CHECK (
    objects_purged_at IS NULL OR deletion_state = 'DELETED'
);

CREATE INDEX ix_document_object_purge_pending ON private_document (deletion_restore_deadline, id)
    WHERE deletion_state = 'DELETED' AND objects_purged_at IS NULL;
