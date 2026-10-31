package id.mydev.peoplecore.attendance.application.service;

import id.mydev.peoplecore.attendance.application.command.AttendancePayloads.CorrectionPayload;
import id.mydev.peoplecore.attendance.application.command.AttendancePayloads.CorrectionAction;
import id.mydev.peoplecore.attendance.application.command.AttendanceResults.CorrectionResult;
import id.mydev.peoplecore.attendance.application.command.AttendanceResults.EffectiveResult;
import id.mydev.peoplecore.attendance.domain.model.AttendanceOccurrence;
import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.organization.domain.model.Employee;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class AttendanceCorrectionService {
    private static final String COLUMNS="id,public_id,employee_id,employee_public_id,work_date,status,proposed_in,"
        + "proposed_out,reason,version,active_revision_id";
    private static final Set<String> EDITABLE=Set.of("DRAFT","RETURNED");
    private final JdbcTemplate jdbc;
    private final AttendanceRecordingService recording;
    private final AttendanceOccurrenceResolver resolver;
    private final AttendanceAccountGuard guard;
    private final AttendanceReviewerService reviewers;
    private final AccountAccessService access;
    private final EmployeeService employees;
    private final AdmissionGuard admission;
    private final EntityManager entities;
    private final Clock clock;

    public AttendanceCorrectionService(JdbcTemplate jdbc,AttendanceRecordingService recording,
        AttendanceOccurrenceResolver resolver,AttendanceAccountGuard guard,AttendanceReviewerService reviewers,
        AccountAccessService access,EmployeeService employees,AdmissionGuard admission,
        EntityManager entities,Clock clock) {
        this.jdbc=jdbc;this.recording=recording;this.resolver=resolver;this.guard=guard;
        this.reviewers=reviewers;this.access=access;this.employees=employees;
        this.admission=admission;this.entities=entities;this.clock=clock;
    }

    @Transactional
    public CorrectionResult create(CorrectionPayload payload, Authentication caller) {
        enter();
        Employee employee=recording.requireSelf(caller,Instant.now(clock));
        guard.ensureLocked(employee.getId());
        employee=recording.requireSelf(caller,Instant.now(clock));
        requireReason(payload.reason());
        if (payload.publicId()==null || payload.workDate()==null || payload.proposedIn()==null) {
            throw new IllegalArgumentException("Correction ID, work date and proposed IN are required");
        }
        AttendanceRecordingService.requireEmployment(employee,payload.workDate());
        validateProposal(employee,payload.workDate(),payload.proposedIn(),payload.proposedOut());
        Long open=jdbc.queryForObject("SELECT count(id) FROM attendance_correction WHERE employee_id=? "
            + "AND work_date=? AND status IN ('DRAFT','PENDING','RETURNED')",Long.class,
            employee.getId(),payload.workDate());
        if (open>0) throw AttendanceRecordingService.conflict("OPEN_CORRECTION","An open correction already exists");
        jdbc.update("INSERT INTO attendance_correction(public_id,employee_id,employee_public_id,work_date,status,"
            + "proposed_in,proposed_out,reason,created_at) VALUES(?,?,?,?,'DRAFT',?,?,?,?)",
            payload.publicId(),employee.getId(),employee.getPublicId(),payload.workDate(),
            ts(payload.proposedIn()),ts(payload.proposedOut()),payload.reason(),ts(Instant.now(clock)));
        return result(find(payload.publicId()),employee,Instant.now(clock));
    }

    @Transactional
    public CorrectionResult act(String action,CorrectionAction payload,Authentication caller) {
        enter();
        Correction current=find(payload.correctionId());
        guard.ensureLocked(current.employeeId());
        current=find(payload.correctionId());
        Employee employee=employees.requireInternalByPublicId(current.employeePublicId());
        Instant now=Instant.now(clock);
        UserAccount actor=access.requireActive(caller,now);
        boolean owner=access.ownEmployeePublicId(actor).filter(employee.getPublicId()::equals).isPresent()
            && !access.activeRoles(actor.getId(),now).isEmpty();
        if (Set.of("EDIT","DISCARD","SUBMIT","RESUBMIT","WITHDRAW").contains(action)) {
            if (!owner) throw new ResourceNotFoundException();
        } else if (Set.of("REASSIGN","ROUTE_RETRY").contains(action)) {
            access.requireHrScope(caller,employees.currentOrgUnit(employee.getId(),now).orElse(null),now);
        } else {
            requireReviewer(current,employee,actor,now);
        }
        if (current.version()!=payload.expectedVersion()) {
            throw AttendanceRecordingService.conflict("STALE_VERSION","Correction version changed");
        }
        if (!Objects.equals(current.revisionId(),payload.revisionId())) {
            throw AttendanceRecordingService.conflict("STALE_REVISION","Submitted revision changed");
        }
        switch(action) {
            case "EDIT" -> edit(current,payload,employee);
            case "DISCARD" -> { requireState(current,Set.of("DRAFT")); changeState(current,"DISCARDED"); }
            case "WITHDRAW" -> { requireState(current,Set.of("PENDING","RETURNED")); changeState(current,"WITHDRAWN"); }
            case "SUBMIT","RESUBMIT" -> submit(current,payload,employee);
            case "APPROVE","REJECT","RETURN" -> decide(action,current,payload,employee,actor);
            case "REASSIGN","ROUTE_RETRY" -> repair(action,current,payload,employee);
            default -> throw new IllegalArgumentException("Unsupported correction action");
        }
        access.requireActive(caller,Instant.now(clock));
        return result(find(current.publicId()),employee,Instant.now(clock));
    }

    private void edit(Correction current,CorrectionAction payload,Employee employee) {
        requireState(current,EDITABLE);
        requireReason(payload.reason());
        validateProposal(employee,current.workDate(),payload.proposedIn(),payload.proposedOut());
        jdbc.update("UPDATE attendance_correction SET proposed_in=?,proposed_out=?,reason=?,version=version+1 "
            + "WHERE id=?",ts(payload.proposedIn()),ts(payload.proposedOut()),payload.reason(),current.id());
    }

    private void submit(Correction current,CorrectionAction payload,Employee employee) {
        requireState(current,EDITABLE);
        Instant now=Instant.now(clock);
        AttendanceRecordingService.requireEmployment(employee,current.workDate());
        validateProposal(employee,current.workDate(),current.proposedIn(),current.proposedOut());
        AttendanceOccurrence occurrence=resolver.resolveOrPin(employee.getId(),current.workDate());
        if (now.isAfter(AttendanceTimePolicy.deadline(current.workDate(),occurrence.getTimezone()))) {
            throw AttendanceRecordingService.conflict("CORRECTION_DEADLINE","Correction deadline elapsed");
        }
        Optional<EffectiveResult> evidence=recording.currentEvidence(occurrence.getId());
        UUID baseline=evidence.map(EffectiveResult::publicId).orElse(null);
        if (!Objects.equals(baseline,payload.expectedEvidenceId())) {
            throw AttendanceRecordingService.conflict("EVIDENCE_CHANGED","Effective evidence changed");
        }
        var context=employees.organizationContext(employee.getId(),now);
        var route=reviewers.resolve(employee,now);
        UUID revision=UUID.randomUUID();
        jdbc.update("INSERT INTO attendance_correction_revision(public_id,correction_id,occurrence_id,baseline_id,"
            + "proposed_in,proposed_out,reason,organization_assignment_id,org_unit,original_reviewer_id,created_at) "
            + "VALUES(?,?,?,?,?,?,?,?,?,?,?)",revision,current.id(),occurrence.getId(),baseline,
            ts(current.proposedIn()),ts(current.proposedOut()),current.reason(),
            context.map(EmployeeService.OrganizationContext::assignmentPublicId).orElse(null),
            context.map(EmployeeService.OrganizationContext::orgUnit).orElse(null),route.reviewerId(),ts(now));
        if (route.reviewerId()!=null) assign(revision,route.reviewerId(),null,"Original submission route",now);
        jdbc.update("UPDATE attendance_correction SET active_revision_id=?,status='PENDING',version=version+1 WHERE id=?",
            revision,current.id());
    }

    private void decide(String action,Correction current,CorrectionAction payload,Employee employee,UserAccount actor) {
        requireState(current,Set.of("PENDING"));
        if (!"APPROVE".equals(action)) requireReason(payload.reason());
        var revision=jdbc.query("SELECT occurrence_id,baseline_id,proposed_in,proposed_out FROM "
            + "attendance_correction_revision WHERE public_id=?",(rs,row)->new Submitted(
                rs.getLong("occurrence_id"),rs.getObject("baseline_id",UUID.class),
                rs.getTimestamp("proposed_in").toInstant(),instant(rs,"proposed_out")),current.revisionId()).getFirst();
        if ("APPROVE".equals(action)) {
            UUID effective=recording.currentEvidence(revision.occurrenceId()).map(EffectiveResult::publicId).orElse(null);
            if (!Objects.equals(effective,revision.baselineId())) {
                throw AttendanceRecordingService.conflict("EVIDENCE_CHANGED","Submitted evidence changed; return or withdraw");
            }
        }
        Instant now=Instant.now(clock);
        requireReviewer(current,employee,actor,now);
        Assignment assignment=assignment(current.revisionId()).orElseThrow(ResourceNotFoundException::new);
        String status=switch(action) { case "APPROVE" -> "APPROVED"; case "REJECT" -> "REJECTED"; default -> "RETURNED"; };
        UUID decision=UUID.randomUUID();
        jdbc.update("INSERT INTO attendance_correction_decision(public_id,revision_id,assignment_id,reviewer_id,"
            + "decision,reason,created_at) VALUES(?,?,?,?,?,?,?)",decision,current.revisionId(),assignment.publicId(),
            actor.getId(),status,payload.reason(),ts(now));
        if ("APPROVE".equals(action)) recording.appendEffective(revision.occurrenceId(),revision.baselineId(),
            revision.proposedIn(),revision.proposedOut(),decision,now);
        changeState(current,status);
    }

    private void repair(String action,Correction current,CorrectionAction payload,Employee employee) {
        requireState(current,Set.of("PENDING"));
        requireReason(payload.reason());
        Assignment previous=assignment(current.revisionId()).orElse(null);
        if ("ROUTE_RETRY".equals(action)) {
            if (previous!=null) throw AttendanceRecordingService.conflict("ASSIGNMENT_EXISTS","Use explicit reassignment");
            var route=reviewers.resolve(employee,Instant.now(clock));
            if (route.reviewerId()!=null) assign(current.revisionId(),route.reviewerId(),null,payload.reason(),Instant.now(clock));
        } else {
            UserAccount reviewer=access.findAccountByPublicId(payload.reviewerId()).orElseThrow(ResourceNotFoundException::new);
            Instant now=Instant.now(clock);
            if (!reviewers.eligible(reviewer,employee,now,false) && !reviewers.eligible(reviewer,employee,now,true)) {
                throw AttendanceRecordingService.conflict("REVIEWER_INELIGIBLE","Replacement reviewer is ineligible");
            }
            assign(current.revisionId(),reviewer.getId(),previous==null?null:previous.publicId(),payload.reason(),now);
        }
        jdbc.update("UPDATE attendance_correction SET version=version+1 WHERE id=?",current.id());
    }

    @Transactional
    public CorrectionResult get(UUID id,Authentication caller) {
        enter();
        Correction correction=find(id);
        Employee employee=employees.requireInternalByPublicId(correction.employeePublicId());
        Instant now=Instant.now(clock);
        requireRead(correction,employee,caller,now);
        CorrectionResult result=result(correction,employee,now);
        requireRead(correction,employee,caller,Instant.now(clock));
        return result;
    }

    @Transactional
    public void verifyAction(String type,UUID correctionId,Authentication caller,boolean replay) {
        enter();
        if ("ATTENDANCE_CORRECTION_CREATE".equals(type)) {
            recording.requireSelf(caller,Instant.now(clock));return;
        }
        Correction current=find(correctionId);
        Employee employee=employees.requireInternalByPublicId(current.employeePublicId());
        Instant now=Instant.now(clock);
        UserAccount actor=access.requireActive(caller,now);
        if (type.endsWith("_REASSIGN") || type.endsWith("_ROUTE_RETRY")) {
            access.requireHrScope(caller,employees.currentOrgUnit(employee.getId(),now).orElse(null),now);return;
        }
        if (type.endsWith("_APPROVE") || type.endsWith("_RETURN") || type.endsWith("_REJECT")) {
            Assignment assignment=assignment(current.revisionId()).orElseThrow(ResourceNotFoundException::new);
            if (!assignment.reviewerId().equals(actor.getId()) || (!reviewers.eligible(actor,employee,now,false)
                && !reviewers.eligible(actor,employee,now,true))) throw new ResourceNotFoundException();
            return;
        }
        if (access.ownEmployeePublicId(actor).filter(employee.getPublicId()::equals).isEmpty()
            || access.activeRoles(actor.getId(),now).isEmpty()) throw new ResourceNotFoundException();
    }

    @Transactional
    public UUID verifyCreateReplay(Authentication caller) {
        enter();
        return recording.requireSelf(caller,Instant.now(clock)).getPublicId();
    }

    public record CorrectionPage(List<CorrectionResult> rows,int page,int size,long total) { }
    public record CorrectionHistory(UUID publicId,String kind,Instant proposedIn,Instant proposedOut,
                                    String reason,String decision,Instant createdAt) { }
    public record HistoryPage(List<CorrectionHistory> rows,UUID nextAfter) { }

    @Transactional
    public CorrectionPage list(UUID employeePublicId,LocalDate from,LocalDate to,String status,
                               int page,int size,Authentication caller) {
        if (page<0 || size<1 || size>100 || from==null || to==null || to.isBefore(from)
            || java.time.temporal.ChronoUnit.DAYS.between(from,to)>=366) {
            throw new IllegalArgumentException("A bounded date range and page are required");
        }
        if (status!=null && !Set.of("DRAFT","PENDING","RETURNED","DISCARDED","WITHDRAWN","APPROVED","REJECTED").contains(status)) {
            throw new IllegalArgumentException("Unsupported correction status");
        }
        enter();
        Employee employee=employees.requireInternalByPublicId(employeePublicId);
        Instant now=Instant.now(clock);
        UserAccount actor=access.requireActive(caller,now);
        boolean owner=access.ownEmployeePublicId(actor).filter(employeePublicId::equals).isPresent()
            && !access.activeRoles(actor.getId(),now).isEmpty();
        boolean hr=access.coversUnit(actor.getId(),employees.currentOrgUnit(employee.getId(),now).orElse(null),now);
        if (!owner && !hr && !reviewers.eligible(actor,employee,now,false)
            && !reviewers.eligible(actor,employee,now,true)) throw new ResourceNotFoundException();
        String predicate="employee_id=? AND work_date>=? AND work_date<=?";
        java.util.ArrayList<Object> args=new java.util.ArrayList<>(List.of(employee.getId(),from,to));
        if (status!=null) {
            predicate+=" AND status=?";
            args.add(status);
        }
        if (!owner && !hr) {
            predicate+=" AND status='PENDING' AND active_revision_id IN (SELECT a.revision_id "
                + "FROM attendance_correction_assignment a WHERE a.reviewer_id=? AND a.id=(SELECT max(b.id) "
                + "FROM attendance_correction_assignment b WHERE b.revision_id=a.revision_id))";
            args.add(actor.getId());
        }
        Long count=jdbc.queryForObject("SELECT count(id) FROM attendance_correction WHERE "+predicate,Long.class,args.toArray());
        args.add(size);args.add(Math.multiplyExact((long)page,size));
        List<CorrectionResult> rows=jdbc.query("SELECT "+COLUMNS+" FROM attendance_correction WHERE "+predicate
            + " ORDER BY created_at,public_id LIMIT ? OFFSET ?",this::map,args.toArray()).stream()
            .map(correction->result(correction,employee,Instant.now(clock))).toList();
        Instant fresh=Instant.now(clock);
        UserAccount freshActor=access.requireActive(caller,fresh);
        if (owner || hr) access.requireEmployeeRead(freshActor,employeePublicId,
            employees.currentOrgUnit(employee.getId(),fresh).orElse(null),fresh);
        else if (!reviewers.eligible(freshActor,employee,fresh,false)
            && !reviewers.eligible(freshActor,employee,fresh,true)) throw new ResourceNotFoundException();
        return new CorrectionPage(rows,page,size,count);
    }

    @Transactional
    public HistoryPage history(UUID id,String kind,UUID after,int size,Authentication caller) {
        if (size<1 || size>100) throw new IllegalArgumentException("History size must be between 1 and 100");
        enter();
        Correction correction=find(id);
        Employee employee=employees.requireInternalByPublicId(correction.employeePublicId());
        requireRead(correction,employee,caller,Instant.now(clock));
        String sql;
        if ("REVISIONS".equals(kind)) {
            sql="SELECT public_id,proposed_in,proposed_out,reason,NULL AS decision,created_at FROM attendance_correction_revision "
                + "WHERE correction_id=? AND id>COALESCE((SELECT id FROM attendance_correction_revision "
                + "WHERE public_id=? AND correction_id=?),0) ORDER BY id LIMIT ?";
        } else if ("DECISIONS".equals(kind)) {
            sql="SELECT d.public_id,NULL AS proposed_in,NULL AS proposed_out,d.reason,d.decision,d.created_at "
                + "FROM attendance_correction_decision d JOIN attendance_correction_revision r ON r.public_id=d.revision_id "
                + "WHERE r.correction_id=? AND d.id>COALESCE((SELECT d2.id FROM attendance_correction_decision d2 "
                + "JOIN attendance_correction_revision r2 ON r2.public_id=d2.revision_id WHERE d2.public_id=? "
                + "AND r2.correction_id=?),0) ORDER BY d.id LIMIT ?";
        } else if ("ASSIGNMENTS".equals(kind)) {
            sql="SELECT a.public_id,NULL AS proposed_in,NULL AS proposed_out,a.reason,NULL AS decision,a.created_at "
                + "FROM attendance_correction_assignment a JOIN attendance_correction_revision r ON r.public_id=a.revision_id "
                + "WHERE r.correction_id=? AND a.id>COALESCE((SELECT a2.id FROM attendance_correction_assignment a2 "
                + "JOIN attendance_correction_revision r2 ON r2.public_id=a2.revision_id WHERE a2.public_id=? "
                + "AND r2.correction_id=?),0) ORDER BY a.id LIMIT ?";
        } else throw new IllegalArgumentException("History kind must be REVISIONS, DECISIONS or ASSIGNMENTS");
        List<CorrectionHistory> rows=jdbc.query(sql,(rs,row)->new CorrectionHistory(rs.getObject("public_id",UUID.class),
            kind,instant(rs,"proposed_in"),instant(rs,"proposed_out"),rs.getString("reason"),rs.getString("decision"),
            rs.getTimestamp("created_at").toInstant()),correction.id(),after,correction.id(),size+1);
        UUID next=rows.size()>size?rows.get(size-1).publicId():null;
        requireRead(correction,employee,caller,Instant.now(clock));
        return new HistoryPage(rows.stream().limit(size).toList(),next);
    }

    private void requireRead(Correction correction,Employee employee,Authentication caller,Instant now) {
        UserAccount actor=access.requireActive(caller,now);
        if (access.coversUnit(actor.getId(),employees.currentOrgUnit(employee.getId(),now).orElse(null),now)) return;
        if (access.ownEmployeePublicId(actor).filter(employee.getPublicId()::equals).isPresent()
            && !access.activeRoles(actor.getId(),now).isEmpty()) return;
        requireReviewer(correction,employee,actor,now);
    }

    private void requireReviewer(Correction current,Employee employee,UserAccount actor,Instant now) {
        Assignment assignment=assignment(current.revisionId()).orElseThrow(ResourceNotFoundException::new);
        if (!"PENDING".equals(current.status()) || !assignment.reviewerId().equals(actor.getId())
            || (!reviewers.eligible(actor,employee,now,false) && !reviewers.eligible(actor,employee,now,true))) {
            throw new ResourceNotFoundException();
        }
    }

    private void validateProposal(Employee employee,LocalDate date,Instant in,Instant out) {
        AttendanceRecordingService.requireEmployment(employee,date);
        var pinned=resolver.findPinned(employee.getId(),date);
        Instant start; Instant end;
        if (pinned.isPresent()) {
            if (pinned.get().isHoliday()) throw new IllegalArgumentException("Holiday is not a work occurrence");
            start=pinned.get().getStartAt();end=pinned.get().getEndAt();
        } else {
            var effective=resolver.computeEffective(employee.getId(),date);
            if (effective.holidayName()!=null) throw new IllegalArgumentException("Holiday is not a work occurrence");
            start=effective.startAt();end=effective.endAt();
        }
        Optional<EffectiveResult> evidence=pinned.flatMap(pin->recording.currentEvidence(pin.getId()));
        AttendanceTimePolicy.validatePair(start,end,in,out,evidence.map(EffectiveResult::inAt).orElse(null),
            evidence.map(EffectiveResult::outAt).orElse(null),Instant.now(clock));
    }

    private CorrectionResult result(Correction correction,Employee employee,Instant now) {
        String blocked=null;
        if ("PENDING".equals(correction.status())) {
            Optional<Assignment> assignment=assignment(correction.revisionId());
            if (assignment.isEmpty()) blocked="REVIEWER_UNAVAILABLE";
            else {
                var reviewer=access.findAccountById(assignment.get().reviewerId());
                if (reviewer.isEmpty() || (!reviewers.eligible(reviewer.get(),employee,now,false)
                    && !reviewers.eligible(reviewer.get(),employee,now,true))) blocked="REVIEWER_INELIGIBLE";
                UUID baseline=jdbc.queryForObject("SELECT baseline_id FROM attendance_correction_revision WHERE public_id=?",
                    UUID.class,correction.revisionId());
                var pin=resolver.findPinned(employee.getId(),correction.workDate()).orElseThrow();
                if (!Objects.equals(baseline,recording.currentEvidence(pin.getId()).map(EffectiveResult::publicId).orElse(null))) {
                    blocked="EVIDENCE_CHANGED";
                }
            }
        }
        return new CorrectionResult(correction.publicId(),employee.getPublicId(),correction.workDate(),
            correction.status(),correction.version(),correction.revisionId(),blocked);
    }

    private Optional<Assignment> assignment(UUID revision) {
        if (revision==null) return Optional.empty();
        return jdbc.query("SELECT public_id,reviewer_id FROM attendance_correction_assignment WHERE revision_id=? "
            + "ORDER BY id DESC LIMIT 1",(rs,row)->new Assignment(rs.getObject("public_id",UUID.class),
                rs.getLong("reviewer_id")),revision).stream().findFirst();
    }

    private void assign(UUID revision,Long reviewer,UUID previous,String reason,Instant now) {
        jdbc.update("INSERT INTO attendance_correction_assignment(public_id,revision_id,reviewer_id,previous_assignment_id,"
            + "reason,created_at) VALUES(?,?,?,?,?,?)",UUID.randomUUID(),revision,reviewer,previous,reason,ts(now));
    }

    private void enter() {
        admission.acquire(AdmissionGuard.Mode.SHARED);entities.flush();entities.clear();
    }

    private Correction find(UUID id) {
        return jdbc.query("SELECT "+COLUMNS+" FROM attendance_correction WHERE public_id=?",
            this::map,id).stream().findFirst().orElseThrow(ResourceNotFoundException::new);
    }

    private Correction map(ResultSet rs,int row) throws SQLException {
        return new Correction(rs.getLong("id"),rs.getObject("public_id",UUID.class),rs.getLong("employee_id"),
            rs.getObject("employee_public_id",UUID.class),rs.getObject("work_date",LocalDate.class),rs.getString("status"),
            rs.getTimestamp("proposed_in").toInstant(),instant(rs,"proposed_out"),rs.getString("reason"),
            rs.getLong("version"),rs.getObject("active_revision_id",UUID.class));
    }

    private static Instant instant(ResultSet rs,String column) throws SQLException {
        Timestamp timestamp=rs.getTimestamp(column); return timestamp==null?null:timestamp.toInstant();
    }
    private static Timestamp ts(Instant instant) { return instant==null?null:Timestamp.from(instant); }
    private static void requireReason(String reason) {
        if (!StringUtils.hasText(reason) || reason.length()>2000) throw new IllegalArgumentException("A bounded reason is required");
    }
    private static void requireState(Correction current,Set<String> allowed) {
        if (!allowed.contains(current.status())) throw AttendanceRecordingService.conflict("INVALID_STATE","Invalid correction state");
    }
    private void changeState(Correction current,String state) {
        jdbc.update("UPDATE attendance_correction SET status=?,version=version+1 WHERE id=?",state,current.id());
    }
    private record Assignment(UUID publicId,Long reviewerId) { }
    private record Submitted(Long occurrenceId,UUID baselineId,Instant proposedIn,Instant proposedOut) { }
    private record Correction(Long id,UUID publicId,Long employeeId,UUID employeePublicId,LocalDate workDate,
        String status,Instant proposedIn,Instant proposedOut,String reason,long version,UUID revisionId) { }
}
