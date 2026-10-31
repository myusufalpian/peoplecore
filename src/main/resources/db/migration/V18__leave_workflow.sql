CREATE TABLE leave_rule_set (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 public_id UUID NOT NULL UNIQUE,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE leave_rule (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 rule_set_id BIGINT NOT NULL REFERENCES leave_rule_set(id),
 rule_id VARCHAR(128) NOT NULL,
 priority INTEGER NOT NULL,
 org_unit VARCHAR(128) NOT NULL,
 hr_account_public_id UUID NOT NULL,
 cancellation_account_public_id UUID NOT NULL,
 UNIQUE(rule_set_id,rule_id)
);
CREATE TABLE leave_rule_activation (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 rule_set_id BIGINT NOT NULL REFERENCES leave_rule_set(id),
 activated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE leave_rule_current (
 id INTEGER PRIMARY KEY CHECK(id=1),
 rule_set_id BIGINT NOT NULL REFERENCES leave_rule_set(id)
);
CREATE TABLE leave_request (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 public_id UUID NOT NULL UNIQUE,
 employee_id BIGINT NOT NULL REFERENCES employees(id),
 status VARCHAR(24) NOT NULL CHECK(status IN ('DRAFT','PENDING','RETURNED','REJECTED','WITHDRAWN','DISCARDED','APPROVED','CANCELLED')),
 revision INTEGER NOT NULL DEFAULT 0,
 version BIGINT NOT NULL DEFAULT 0,
 from_date DATE NOT NULL,
 to_date DATE NOT NULL,
 portion VARCHAR(16) NOT NULL CHECK(portion IN ('FULL','FIRST_HALF','SECOND_HALF')),
 reason VARCHAR(2000) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CHECK(from_date<=to_date),
 CHECK(portion='FULL' OR from_date=to_date)
);
CREATE TABLE leave_revision (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 request_id BIGINT NOT NULL REFERENCES leave_request(id),
 revision INTEGER NOT NULL,
 year INTEGER NOT NULL,
 policy_revision_id BIGINT NOT NULL REFERENCES leave_policy_revision(id),
 half_units INTEGER NOT NULL CHECK(half_units>0),
 org_unit VARCHAR(128) NOT NULL,
 manager_employee_public_id UUID,
 from_date DATE NOT NULL,
 to_date DATE NOT NULL,
 portion VARCHAR(16) NOT NULL,
 reason VARCHAR(2000) NOT NULL,
 submitted_at TIMESTAMP WITH TIME ZONE NOT NULL,
 rule_set_id BIGINT REFERENCES leave_rule_set(id),
 UNIQUE(request_id,revision)
);
CREATE TABLE leave_portion (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 public_id UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
 request_id BIGINT NOT NULL REFERENCES leave_request(id),
 revision INTEGER NOT NULL,
 employee_id BIGINT NOT NULL REFERENCES employees(id),
 occurrence_id BIGINT NOT NULL REFERENCES attendance_occurrence(id),
 work_date DATE NOT NULL,
 half INTEGER NOT NULL CHECK(half IN (1,2)),
 start_at TIMESTAMP WITH TIME ZONE NOT NULL,
 end_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CHECK(start_at<end_at),
 UNIQUE(request_id,revision,work_date,half)
);
CREATE TABLE leave_occupancy (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 portion_id BIGINT NOT NULL UNIQUE REFERENCES leave_portion(id),
 request_id BIGINT NOT NULL REFERENCES leave_request(id),
 revision INTEGER NOT NULL,
 employee_id BIGINT NOT NULL REFERENCES employees(id),
 work_date DATE NOT NULL,
 half INTEGER NOT NULL CHECK(half IN (1,2)),
 start_at TIMESTAMP WITH TIME ZONE NOT NULL,
 end_at TIMESTAMP WITH TIME ZONE NOT NULL,
 active BOOLEAN NOT NULL DEFAULT TRUE,
 CHECK(start_at<end_at)
);
CREATE UNIQUE INDEX uk_leave_active_slot ON leave_occupancy(employee_id,work_date,half) WHERE active;
CREATE INDEX ix_leave_active_interval ON leave_occupancy(employee_id,start_at,end_at) WHERE active;
CREATE TABLE leave_review_assignment (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 request_id BIGINT NOT NULL REFERENCES leave_request(id),
 revision INTEGER NOT NULL,
 step INTEGER NOT NULL CHECK(step IN (1,2)),
 reviewer_account_public_id UUID,
 reviewer_employee_public_id UUID,
 fallback BOOLEAN NOT NULL DEFAULT FALSE,
 active BOOLEAN NOT NULL DEFAULT TRUE,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE UNIQUE INDEX uk_leave_active_review ON leave_review_assignment(request_id,revision,step) WHERE active;
CREATE TABLE leave_decision (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 request_id BIGINT NOT NULL REFERENCES leave_request(id),
 revision INTEGER NOT NULL,
 step INTEGER NOT NULL,
 reviewer_account_public_id UUID NOT NULL,
 reviewer_employee_public_id UUID NOT NULL,
 outcome VARCHAR(24) NOT NULL,
 reason VARCHAR(2000),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 UNIQUE(request_id,revision,step)
);
CREATE TABLE leave_recovery (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 request_id BIGINT NOT NULL REFERENCES leave_request(id),
 revision INTEGER NOT NULL,
 action VARCHAR(24) NOT NULL,
 reason VARCHAR(2000) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE leave_cancellation (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 public_id UUID NOT NULL UNIQUE,
 request_id BIGINT NOT NULL REFERENCES leave_request(id),
 status VARCHAR(24) NOT NULL CHECK(status IN ('PENDING','APPROVED','REJECTED','WITHDRAWN')),
 version BIGINT NOT NULL DEFAULT 0,
 reason VARCHAR(2000) NOT NULL,
 org_unit VARCHAR(128) NOT NULL,
 rule_set_id BIGINT REFERENCES leave_rule_set(id),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE UNIQUE INDEX uk_leave_open_cancellation ON leave_cancellation(request_id) WHERE status='PENDING';
CREATE TABLE leave_cancellation_assignment (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 cancellation_id BIGINT NOT NULL REFERENCES leave_cancellation(id),
 reviewer_account_public_id UUID,
 reviewer_employee_public_id UUID,
 active BOOLEAN NOT NULL DEFAULT TRUE,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE UNIQUE INDEX uk_leave_active_cancel_review ON leave_cancellation_assignment(cancellation_id) WHERE active;
CREATE TABLE leave_cancellation_decision (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 cancellation_id BIGINT NOT NULL UNIQUE REFERENCES leave_cancellation(id),
 reviewer_account_public_id UUID NOT NULL,
 outcome VARCHAR(24) NOT NULL,
 reason VARCHAR(2000),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE leave_history (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 public_id UUID NOT NULL UNIQUE,
 request_id BIGINT NOT NULL REFERENCES leave_request(id),
 cancellation_public_id UUID,
 revision INTEGER NOT NULL,
 action VARCHAR(64) NOT NULL,
 payload TEXT NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE leave_route_evaluation (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 request_id BIGINT NOT NULL REFERENCES leave_request(id),
 revision INTEGER NOT NULL,
 cancellation_public_id UUID,
 configuration_public_id UUID,
 manager_account_public_id UUID,
 hr_account_public_id UUID,
 cancellation_account_public_id UUID,
 blocked_reason VARCHAR(64),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE FUNCTION reject_leave_evidence_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Leave evidence is append-only'; END;
$$;
DO $$ DECLARE target TEXT; BEGIN
 FOREACH target IN ARRAY ARRAY['leave_rule_set','leave_rule','leave_rule_activation','leave_revision','leave_portion','leave_decision','leave_recovery','leave_cancellation_decision','leave_history','leave_route_evaluation'] LOOP
  EXECUTE format('CREATE TRIGGER seal_leave_evidence BEFORE UPDATE OR DELETE OR TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION reject_leave_evidence_mutation()',target);
 END LOOP;
END; $$;

CREATE FUNCTION protect_leave_request_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Leave requests cannot be deleted'; END IF;
 IF NEW.id IS DISTINCT FROM OLD.id OR NEW.public_id IS DISTINCT FROM OLD.public_id
  OR NEW.employee_id IS DISTINCT FROM OLD.employee_id OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
  RAISE EXCEPTION 'Leave request identity is immutable';
 END IF;
 IF NEW.version<>OLD.version+1 THEN RAISE EXCEPTION 'Leave request version must advance once'; END IF;
 IF OLD.status NOT IN ('DRAFT','RETURNED') AND
  (NEW.from_date IS DISTINCT FROM OLD.from_date OR NEW.to_date IS DISTINCT FROM OLD.to_date
   OR NEW.portion IS DISTINCT FROM OLD.portion OR NEW.reason IS DISTINCT FROM OLD.reason) THEN
  RAISE EXCEPTION 'Submitted request content is immutable';
 END IF;
 IF NEW.revision<>OLD.revision AND NOT
  (OLD.status IN ('DRAFT','RETURNED') AND NEW.status='PENDING' AND NEW.revision=OLD.revision+1) THEN
  RAISE EXCEPTION 'Invalid leave revision transition';
 END IF;
 IF NEW.status<>OLD.status AND NOT
  ((OLD.status='DRAFT' AND NEW.status IN ('PENDING','DISCARDED'))
   OR (OLD.status='RETURNED' AND NEW.status IN ('PENDING','WITHDRAWN'))
   OR (OLD.status='PENDING' AND NEW.status IN ('RETURNED','REJECTED','WITHDRAWN','APPROVED'))
   OR (OLD.status='APPROVED' AND NEW.status='CANCELLED')) THEN
  RAISE EXCEPTION 'Invalid leave status transition';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER protect_leave_request BEFORE UPDATE OR DELETE ON leave_request
 FOR EACH ROW EXECUTE FUNCTION protect_leave_request_identity();

CREATE FUNCTION protect_leave_cancellation_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Leave cancellations cannot be deleted'; END IF;
 IF NEW.id IS DISTINCT FROM OLD.id OR NEW.public_id IS DISTINCT FROM OLD.public_id
  OR NEW.request_id IS DISTINCT FROM OLD.request_id OR NEW.reason IS DISTINCT FROM OLD.reason
  OR NEW.org_unit IS DISTINCT FROM OLD.org_unit OR NEW.rule_set_id IS DISTINCT FROM OLD.rule_set_id
  OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
  RAISE EXCEPTION 'Cancellation provenance is immutable';
 END IF;
 IF NEW.version<>OLD.version+1 OR OLD.status<>'PENDING'
  OR NEW.status NOT IN ('PENDING','APPROVED','REJECTED','WITHDRAWN') THEN
  RAISE EXCEPTION 'Invalid cancellation transition';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER protect_leave_cancellation BEFORE UPDATE OR DELETE ON leave_cancellation
 FOR EACH ROW EXECUTE FUNCTION protect_leave_cancellation_identity();

CREATE FUNCTION protect_leave_active_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Leave operational evidence cannot be deleted'; END IF;
 IF (to_jsonb(NEW)-'active') IS DISTINCT FROM (to_jsonb(OLD)-'active')
  OR (NOT OLD.active AND NEW.active) THEN
  RAISE EXCEPTION 'Leave provenance is immutable; only deactivation is permitted';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER protect_leave_occupancy BEFORE UPDATE OR DELETE ON leave_occupancy
 FOR EACH ROW EXECUTE FUNCTION protect_leave_active_evidence();
CREATE TRIGGER protect_leave_review_assignment BEFORE UPDATE OR DELETE ON leave_review_assignment
 FOR EACH ROW EXECUTE FUNCTION protect_leave_active_evidence();
CREATE TRIGGER protect_leave_cancel_assignment BEFORE UPDATE OR DELETE ON leave_cancellation_assignment
 FOR EACH ROW EXECUTE FUNCTION protect_leave_active_evidence();
