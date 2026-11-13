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
import id.mydev.peoplecore.attendance.domain.repository.AttendanceCorrectionRepository;
import id.mydev.peoplecore.attendance.domain.repository.AttendanceCorrectionRepository.Assignment;
import id.mydev.peoplecore.attendance.domain.repository.AttendanceCorrectionRepository.Correction;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.time.temporal.ChronoUnit;

@Service
public class AttendanceCorrectionService {
    private static final Set<String> EDITABLE=Set.of("DRAFT","RETURNED");
    private final AttendanceCorrectionRepository repository;
    private final AttendanceRecordingService recording;
    private final AttendanceOccurrenceResolver resolver;
    private final AttendanceAccountGuard guard;
    private final AttendanceReviewerService reviewers;
    private final AccountAccessService access;
    private final EmployeeService employees;
    private final AdmissionGuard admission;
    private final EntityManager entities;
    private final Clock clock;

    public AttendanceCorrectionService(AttendanceCorrectionRepository repository,AttendanceRecordingService recording,
        AttendanceOccurrenceResolver resolver,AttendanceAccountGuard guard,AttendanceReviewerService reviewers,
        AccountAccessService access,EmployeeService employees,AdmissionGuard admission,
        EntityManager entities,Clock clock) {
        this.repository=repository;this.recording=recording;this.resolver=resolver;this.guard=guard;
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
        Long open=repository.countOpen(employee.getId(), payload.workDate());
        if (open>0) throw AttendanceRecordingService.conflict("OPEN_CORRECTION","An open correction already exists");
        repository.create(payload.publicId(), employee.getId(), employee.getPublicId(), payload.workDate(), payload.proposedIn(), payload.proposedOut(), payload.reason(), Instant.now(clock));
        return result(repository.find(payload.publicId()),employee,Instant.now(clock));
    }

    @Transactional
    public CorrectionResult act(String action,CorrectionAction payload,Authentication caller) {
        enter();
        Correction current=repository.find(payload.correctionId());
        guard.ensureLocked(current.employeeId());
        current=repository.find(payload.correctionId());
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
            case "DISCARD" -> { requireState(current,Set.of("DRAFT")); repository.changeState(current,"DISCARDED"); }
            case "WITHDRAW" -> { requireState(current,Set.of("PENDING","RETURNED")); repository.changeState(current,"WITHDRAWN"); }
            case "SUBMIT","RESUBMIT" -> submit(current,payload,employee);
            case "APPROVE","REJECT","RETURN" -> decide(action,current,payload,employee,actor);
            case "REASSIGN","ROUTE_RETRY" -> repair(action,current,payload,employee);
            default -> throw new IllegalArgumentException("Unsupported correction action");
        }
        access.requireActive(caller,Instant.now(clock));
        return result(repository.find(current.publicId()),employee,Instant.now(clock));
    }

    private void edit(Correction current,CorrectionAction payload,Employee employee) {
        requireState(current,EDITABLE);
        requireReason(payload.reason());
        validateProposal(employee,current.workDate(),payload.proposedIn(),payload.proposedOut());
        repository.edit(payload.proposedIn(), payload.proposedOut(), payload.reason(), current.id());
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
        repository.insertRevision(revision, current.id(), occurrence.getId(), baseline, current.proposedIn(), current.proposedOut(), current.reason(), context.map(EmployeeService.OrganizationContext::assignmentPublicId).orElse(null), context.map(EmployeeService.OrganizationContext::orgUnit).orElse(null), route.reviewerId(), now);
        if (route.reviewerId()!=null) repository.assign(revision,route.reviewerId(),null,"Original submission route",now);
        repository.markPending(revision, current.id());
    }

    private void decide(String action,Correction current,CorrectionAction payload,Employee employee,UserAccount actor) {
        requireState(current,Set.of("PENDING"));
        if (!"APPROVE".equals(action)) requireReason(payload.reason());
        var revision=repository.findSubmitted(current.revisionId()).getFirst();
        if ("APPROVE".equals(action)) {
            UUID effective=recording.currentEvidence(revision.occurrenceId()).map(EffectiveResult::publicId).orElse(null);
            if (!Objects.equals(effective,revision.baselineId())) {
                throw AttendanceRecordingService.conflict("EVIDENCE_CHANGED","Submitted evidence changed; return or withdraw");
            }
        }
        Instant now=Instant.now(clock);
        requireReviewer(current,employee,actor,now);
        Assignment assignment=repository.assignment(current.revisionId()).orElseThrow(ResourceNotFoundException::new);
        String status=switch(action) { case "APPROVE" -> "APPROVED"; case "REJECT" -> "REJECTED"; default -> "RETURNED"; };
        UUID decision=UUID.randomUUID();
        repository.insertDecision(decision, current.revisionId(), assignment.publicId(), actor.getId(), status, payload.reason(), now);
        if ("APPROVE".equals(action)) recording.appendEffective(revision.occurrenceId(),revision.baselineId(),
            revision.proposedIn(),revision.proposedOut(),decision,now);
        repository.changeState(current,status);
    }

    private void repair(String action,Correction current,CorrectionAction payload,Employee employee) {
        requireState(current,Set.of("PENDING"));
        requireReason(payload.reason());
        Assignment previous=repository.assignment(current.revisionId()).orElse(null);
        if ("ROUTE_RETRY".equals(action)) {
            if (previous!=null) throw AttendanceRecordingService.conflict("ASSIGNMENT_EXISTS","Use explicit reassignment");
            var route=reviewers.resolve(employee,Instant.now(clock));
            if (route.reviewerId()!=null) repository.assign(current.revisionId(),route.reviewerId(),null,payload.reason(),Instant.now(clock));
        } else {
            UserAccount reviewer=access.findAccountByPublicId(payload.reviewerId()).orElseThrow(ResourceNotFoundException::new);
            Instant now=Instant.now(clock);
            if (!reviewers.eligible(reviewer,employee,now,false) && !reviewers.eligible(reviewer,employee,now,true)) {
                throw AttendanceRecordingService.conflict("REVIEWER_INELIGIBLE","Replacement reviewer is ineligible");
            }
            repository.assign(current.revisionId(),reviewer.getId(),previous==null?null:previous.publicId(),payload.reason(),now);
        }
        repository.incrementVersion(current.id());
    }

    @Transactional
    public CorrectionResult get(UUID id,Authentication caller) {
        enter();
        Correction correction=repository.find(id);
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
        Correction current=repository.find(correctionId);
        Employee employee=employees.requireInternalByPublicId(current.employeePublicId());
        Instant now=Instant.now(clock);
        UserAccount actor=access.requireActive(caller,now);
        if (type.endsWith("_REASSIGN") || type.endsWith("_ROUTE_RETRY")) {
            access.requireHrScope(caller,employees.currentOrgUnit(employee.getId(),now).orElse(null),now);return;
        }
        if (type.endsWith("_APPROVE") || type.endsWith("_RETURN") || type.endsWith("_REJECT")) {
            Assignment assignment=repository.assignment(current.revisionId()).orElseThrow(ResourceNotFoundException::new);
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
            || ChronoUnit.DAYS.between(from,to)>=366) {
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
        var matching = repository.list(employee.getId(), from, to, status, owner, hr, actor.getId(), page, size);
        long count = matching.total();
        List<CorrectionResult> rows = matching.rows().stream().filter(Objects::nonNull)
            .map(correction -> result(correction, employee, Instant.now(clock))).toList();
        Instant fresh=Instant.now(clock);
        UserAccount freshActor=access.requireActive(caller,fresh);
        if (owner || hr) access.requireEmployeeRead(freshActor,employeePublicId,
            employees.currentOrgUnit(employee.getId(),fresh).orElse(null),fresh);
        else if (!reviewers.eligible(freshActor,employee,fresh,false)
            && !reviewers.eligible(freshActor,employee,fresh,true)) throw new ResourceNotFoundException();
        return new CorrectionPage(rows, page, size, count);
    }

    @Transactional
    public HistoryPage history(UUID id,String kind,UUID after,int size,Authentication caller) {
        if (size<1 || size>100) throw new IllegalArgumentException("History size must be between 1 and 100");
        enter();
        Correction correction=repository.find(id);
        Employee employee=employees.requireInternalByPublicId(correction.employeePublicId());
        requireRead(correction,employee,caller,Instant.now(clock));
        if (kind == null || !Set.of("REVISIONS", "DECISIONS", "ASSIGNMENTS").contains(kind)) {
            throw new IllegalArgumentException("History kind must be REVISIONS, DECISIONS or ASSIGNMENTS");
        }
        List<CorrectionHistory> rows = repository.history(kind, correction.id(), after, size).stream()
            .map(row -> new CorrectionHistory(row.publicId(), row.kind(), row.proposedIn(), row.proposedOut(),
                row.reason(), row.decision(), row.createdAt())).toList();
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
        Assignment assignment=repository.assignment(current.revisionId()).orElseThrow(ResourceNotFoundException::new);
        if (!"PENDING".equals(current.status()) || !assignment.reviewerId().equals(actor.getId())
            || (!reviewers.eligible(actor,employee,now,false) && !reviewers.eligible(actor,employee,now,true))) {
            throw new ResourceNotFoundException();
        }
    }

    private void validateProposal(Employee employee,LocalDate date,Instant in,Instant out) {
        AttendanceRecordingService.requireEmployment(employee,date);
        var pinned=resolver.findPinned(employee.getId(),date);
        Instant start;
        Instant end;
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
            Optional<Assignment> assignment=repository.assignment(correction.revisionId());
            if (assignment.isEmpty()) blocked="REVIEWER_UNAVAILABLE";
            else {
                var reviewer=access.findAccountById(assignment.get().reviewerId());
                if (reviewer.isEmpty() || (!reviewers.eligible(reviewer.get(),employee,now,false)
                    && !reviewers.eligible(reviewer.get(),employee,now,true))) blocked="REVIEWER_INELIGIBLE";
                UUID baseline=repository.findBaseline(correction.revisionId());
                var pin=resolver.findPinned(employee.getId(),correction.workDate());
                if (pin.isEmpty() || !Objects.equals(baseline,
                    recording.currentEvidence(pin.get().getId()).map(EffectiveResult::publicId).orElse(null))) {
                    blocked="EVIDENCE_CHANGED";
                }
            }
        }
        return new CorrectionResult(correction.publicId(),employee.getPublicId(),correction.workDate(),
            correction.status(),correction.version(),correction.revisionId(),blocked);
    }





    private void enter() {
        admission.acquire(AdmissionGuard.Mode.SHARED);entities.flush();entities.clear();
    }






    private static void requireReason(String reason) {
        if (!StringUtils.hasText(reason) || reason.length()>2000) throw new IllegalArgumentException("A bounded reason is required");
    }
    private static void requireState(Correction current,Set<String> allowed) {
        if (!allowed.contains(current.status())) throw AttendanceRecordingService.conflict("INVALID_STATE","Invalid correction state");
    }




}
