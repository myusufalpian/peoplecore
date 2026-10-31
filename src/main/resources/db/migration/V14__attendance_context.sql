-- V14__attendance_context.sql
-- sealed work schedule/calendar revisions, effective
-- assignment timelines [fromDate,toDate), attendance accounts and
-- unique employee/work-date occurrences. Published content is sealed;
-- assignments are half-open intervals with lineage.

CREATE TABLE IF NOT EXISTS work_schedule_revision (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Jakarta',
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_schedule_revision_status CHECK (status IN ('DRAFT', 'PUBLISHED')),
    CONSTRAINT ck_schedule_revision_timezone CHECK (timezone <> '')
);

CREATE TABLE IF NOT EXISTS work_schedule_day (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    revision_id BIGINT NOT NULL REFERENCES work_schedule_revision (id) ON DELETE CASCADE,
    weekday INT NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    overnight_offset INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_schedule_day_revision_weekday UNIQUE (revision_id, weekday),
    CONSTRAINT ck_schedule_day_weekday CHECK (weekday BETWEEN 1 AND 7),
    CONSTRAINT ck_schedule_day_overnight CHECK (overnight_offset IN (0, 1))
);

CREATE TABLE IF NOT EXISTS holiday_calendar_revision (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    location VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_calendar_revision_status CHECK (status IN ('DRAFT', 'PUBLISHED')),
    CONSTRAINT ck_calendar_revision_location CHECK (location <> '')
);

CREATE TABLE IF NOT EXISTS holiday_date (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    revision_id BIGINT NOT NULL REFERENCES holiday_calendar_revision (id) ON DELETE CASCADE,
    holiday_day DATE NOT NULL,
    holiday_name VARCHAR(256) NOT NULL,
    CONSTRAINT uk_holiday_date_revision_day UNIQUE (revision_id, holiday_day),
    CONSTRAINT ck_holiday_date_name CHECK (holiday_name <> '')
);

CREATE TABLE IF NOT EXISTS employee_schedule_assignment (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    employee_id BIGINT NOT NULL REFERENCES employees (id),
    revision_id BIGINT NOT NULL REFERENCES work_schedule_revision (id),
    from_date DATE NOT NULL,
    to_date DATE,
    superseded BOOLEAN NOT NULL DEFAULT FALSE,
    supersedes_public_id UUID,
    backdate_reason VARCHAR(1000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_schedule_assignment_interval CHECK (to_date IS NULL OR from_date < to_date)
);

CREATE INDEX IF NOT EXISTS idx_schedule_assignment_employee
    ON employee_schedule_assignment (employee_id, from_date);

CREATE TABLE IF NOT EXISTS employee_calendar_assignment (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    employee_id BIGINT NOT NULL REFERENCES employees (id),
    revision_id BIGINT NOT NULL REFERENCES holiday_calendar_revision (id),
    from_date DATE NOT NULL,
    to_date DATE,
    superseded BOOLEAN NOT NULL DEFAULT FALSE,
    supersedes_public_id UUID,
    backdate_reason VARCHAR(1000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_calendar_assignment_interval CHECK (to_date IS NULL OR from_date < to_date)
);

CREATE INDEX IF NOT EXISTS idx_calendar_assignment_employee
    ON employee_calendar_assignment (employee_id, from_date);

CREATE TABLE IF NOT EXISTS attendance_account (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id BIGINT NOT NULL UNIQUE REFERENCES employees (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS attendance_occurrence (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    employee_id BIGINT NOT NULL REFERENCES employees (id),
    work_date DATE NOT NULL,
    start_at TIMESTAMP WITH TIME ZONE NOT NULL,
    end_at TIMESTAMP WITH TIME ZONE NOT NULL,
    timezone VARCHAR(64) NOT NULL,
    schedule_revision_id BIGINT NOT NULL REFERENCES work_schedule_revision (id),
    calendar_revision_id BIGINT REFERENCES holiday_calendar_revision (id),
    schedule_assignment_id BIGINT NOT NULL REFERENCES employee_schedule_assignment (id),
    calendar_assignment_id BIGINT REFERENCES employee_calendar_assignment (id),
    holiday_name VARCHAR(256),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_occurrence_employee_date UNIQUE (employee_id, work_date),
    CONSTRAINT ck_occurrence_interval CHECK (start_at < end_at),
    CONSTRAINT ck_occurrence_duration CHECK (end_at - start_at < INTERVAL '24 hours')
);

CREATE INDEX IF NOT EXISTS idx_occurrence_employee_date
    ON attendance_occurrence (employee_id, work_date);

-- Published schedule/calendar content is immutable: rows under a PUBLISHED
-- revision cannot be inserted, updated, deleted or moved; publish a new
-- revision instead. Parent rows are locked before the status check so a
-- concurrent publication cannot commit between check and child write.
CREATE OR REPLACE FUNCTION reject_published_schedule_write()
RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        PERFORM 1 FROM work_schedule_revision WHERE id = NEW.revision_id FOR UPDATE;
        IF EXISTS (SELECT 1 FROM work_schedule_revision s
                   WHERE s.id = NEW.revision_id AND s.status = 'PUBLISHED') THEN
            RAISE EXCEPTION 'Published schedule content is immutable';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        PERFORM 1 FROM work_schedule_revision WHERE id = OLD.revision_id FOR UPDATE;
        IF EXISTS (SELECT 1 FROM work_schedule_revision s
                   WHERE s.id = OLD.revision_id AND s.status = 'PUBLISHED') THEN
            RAISE EXCEPTION 'Published schedule content is immutable';
        END IF;
        RETURN OLD;
    END IF;
    IF OLD.revision_id = NEW.revision_id THEN
        PERFORM 1 FROM work_schedule_revision WHERE id = NEW.revision_id FOR UPDATE;
    ELSIF OLD.revision_id < NEW.revision_id THEN
        PERFORM 1 FROM work_schedule_revision WHERE id = OLD.revision_id FOR UPDATE;
        PERFORM 1 FROM work_schedule_revision WHERE id = NEW.revision_id FOR UPDATE;
    ELSE
        PERFORM 1 FROM work_schedule_revision WHERE id = NEW.revision_id FOR UPDATE;
        PERFORM 1 FROM work_schedule_revision WHERE id = OLD.revision_id FOR UPDATE;
    END IF;
    IF EXISTS (SELECT 1 FROM work_schedule_revision s
               WHERE s.id IN (OLD.revision_id, NEW.revision_id) AND s.status = 'PUBLISHED') THEN
        RAISE EXCEPTION 'Published schedule content is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_schedule_day_immutable ON work_schedule_day;
CREATE TRIGGER trg_schedule_day_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON work_schedule_day
    FOR EACH ROW EXECUTE FUNCTION reject_published_schedule_write();

CREATE OR REPLACE FUNCTION reject_published_schedule_revision_write()
RETURNS trigger AS $$
BEGIN
    IF OLD.status = 'PUBLISHED' THEN
        RAISE EXCEPTION 'Published schedule content is immutable';
    END IF;
    IF NEW.status <> 'DRAFT' AND NEW.status <> 'PUBLISHED' THEN
        RAISE EXCEPTION 'Unknown schedule revision status';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_schedule_revision_immutable ON work_schedule_revision;
CREATE TRIGGER trg_schedule_revision_immutable
    BEFORE UPDATE ON work_schedule_revision
    FOR EACH ROW EXECUTE FUNCTION reject_published_schedule_revision_write();

CREATE OR REPLACE FUNCTION reject_published_holiday_write()
RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        PERFORM 1 FROM holiday_calendar_revision WHERE id = NEW.revision_id FOR UPDATE;
        IF EXISTS (SELECT 1 FROM holiday_calendar_revision c
                   WHERE c.id = NEW.revision_id AND c.status = 'PUBLISHED') THEN
            RAISE EXCEPTION 'Published calendar content is immutable';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        PERFORM 1 FROM holiday_calendar_revision WHERE id = OLD.revision_id FOR UPDATE;
        IF EXISTS (SELECT 1 FROM holiday_calendar_revision c
                   WHERE c.id = OLD.revision_id AND c.status = 'PUBLISHED') THEN
            RAISE EXCEPTION 'Published calendar content is immutable';
        END IF;
        RETURN OLD;
    END IF;
    IF OLD.revision_id = NEW.revision_id THEN
        PERFORM 1 FROM holiday_calendar_revision WHERE id = NEW.revision_id FOR UPDATE;
    ELSIF OLD.revision_id < NEW.revision_id THEN
        PERFORM 1 FROM holiday_calendar_revision WHERE id = OLD.revision_id FOR UPDATE;
        PERFORM 1 FROM holiday_calendar_revision WHERE id = NEW.revision_id FOR UPDATE;
    ELSE
        PERFORM 1 FROM holiday_calendar_revision WHERE id = NEW.revision_id FOR UPDATE;
        PERFORM 1 FROM holiday_calendar_revision WHERE id = OLD.revision_id FOR UPDATE;
    END IF;
    IF EXISTS (SELECT 1 FROM holiday_calendar_revision c
               WHERE c.id IN (OLD.revision_id, NEW.revision_id) AND c.status = 'PUBLISHED') THEN
        RAISE EXCEPTION 'Published calendar content is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_holiday_date_immutable ON holiday_date;
CREATE TRIGGER trg_holiday_date_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON holiday_date
    FOR EACH ROW EXECUTE FUNCTION reject_published_holiday_write();

CREATE OR REPLACE FUNCTION reject_published_calendar_revision_write()
RETURNS trigger AS $$
BEGIN
    IF OLD.status = 'PUBLISHED' THEN
        RAISE EXCEPTION 'Published calendar content is immutable';
    END IF;
    IF NEW.status <> 'DRAFT' AND NEW.status <> 'PUBLISHED' THEN
        RAISE EXCEPTION 'Unknown calendar revision status';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_calendar_revision_immutable ON holiday_calendar_revision;
CREATE TRIGGER trg_calendar_revision_immutable
    BEFORE UPDATE ON holiday_calendar_revision
    FOR EACH ROW EXECUTE FUNCTION reject_published_calendar_revision_write();

-- Canonical occurrences are pinned evidence: they are append-only and
-- cannot be updated, deleted or truncated by runtime roles.
CREATE FUNCTION reject_occurrence_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Attendance occurrences are append-only' USING ERRCODE = '42501';
END;
$$;

DROP TRIGGER IF EXISTS trg_occurrence_immutable ON attendance_occurrence;
CREATE TRIGGER trg_occurrence_immutable
BEFORE UPDATE OR DELETE OR TRUNCATE ON attendance_occurrence
FOR EACH STATEMENT EXECUTE FUNCTION reject_occurrence_mutation();
