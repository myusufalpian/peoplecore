package id.mydev.peoplecore.leave.application.service;

import id.mydev.peoplecore.attendance.application.service.AttendanceAccountGuard;
import id.mydev.peoplecore.attendance.application.service.AttendanceOccurrenceResolver;
import id.mydev.peoplecore.attendance.domain.exception.WorkContextException;
import id.mydev.peoplecore.attendance.domain.model.AttendanceOccurrence;
import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.Action;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.CancellationView;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.Draft;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.History;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.Portion;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.PortionView;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.RequestView;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.Route;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.RequestPage;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.TimelineEntry;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.TimelinePage;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.Summary;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.SummaryPage;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.organization.domain.model.Employee;
import jakarta.persistence.EntityManager;
import id.mydev.peoplecore.leave.domain.repository.LeaveWorkflowRepository;
import id.mydev.peoplecore.leave.domain.repository.LeaveWorkflowRepository.Request;
import id.mydev.peoplecore.leave.domain.repository.LeaveWorkflowRepository.Reviewer;
import id.mydev.peoplecore.leave.domain.repository.LeaveWorkflowRepository.Cancellation;
import id.mydev.peoplecore.leave.domain.repository.LeaveWorkflowRepository.RouteSnapshot;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class LeaveWorkflowService {
    private static final ZoneId ZONE=ZoneId.of("Asia/Jakarta");
    private final LeaveWorkflowRepository repository;
    private final AccountAccessService access;
    private final EmployeeService employees;
    private final AttendanceAccountGuard attendance;
    private final AttendanceOccurrenceResolver resolver;
    private final LeaveEntitlementService entitlement;
    private final LeaveRoutingService routing;
    private final AdmissionGuard admission;
    private final EntityManager entities;
    private final Clock clock;
    private final ObjectMapper mapper;

    public LeaveWorkflowService(LeaveWorkflowRepository repository,AccountAccessService access,EmployeeService employees,
                                AttendanceAccountGuard attendance,AttendanceOccurrenceResolver resolver,
                                LeaveEntitlementService entitlement,LeaveRoutingService routing,
                                AdmissionGuard admission,EntityManager entities,Clock clock,ObjectMapper mapper) {
        this.repository=repository; this.access=access; this.employees=employees; this.attendance=attendance;
        this.resolver=resolver; this.entitlement=entitlement; this.routing=routing;
        this.admission=admission; this.entities=entities; this.clock=clock; this.mapper=mapper;
    }



    private record Captured(LocalDate date,int half,Instant start,Instant end,Long occurrence,Instant occurrenceStart) { }


    @Transactional(propagation=Propagation.MANDATORY)
    public RequestView create(UUID id,Draft payload,Authentication caller) {
        Employee employee=lockEmployee(payload.employeePublicId());
        requireOwner(employee,caller);
        validateDraft(payload);
        repository.createDraft(id, employee.getId(), payload.fromDate(), payload.toDate(), payload.portion().name(), payload.reason(), clock.instant());
        return view(request(id,false),employee);
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public RequestView edit(UUID id,Draft payload,long expectedVersion,Authentication caller) {
        Employee employee=lockEmployee(payload.employeePublicId());
        requireOwner(employee,caller);
        Request request=ownedRequest(id,employee,true);
        checkVersion(request,expectedVersion);
        requireStatus(request,"DRAFT","RETURNED");
        validateDraft(payload);
        repository.editDraft(payload.fromDate(), payload.toDate(), payload.portion().name(), payload.reason(), request.id());
        return view(request(id,false),employee);
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public RequestView act(UUID id,String action,Action payload,Authentication caller) {
        Employee employee=lockEmployee(payload.employeePublicId());
        Request request=ownedRequest(id,employee,true);
        checkVersion(request,payload.expectedVersion());
        switch(action) {
            case "SUBMIT", "RESUBMIT" -> submit(request,employee,caller);
            case "DISCARD" -> { requireOwner(employee,caller); requireStatus(request,"DRAFT"); transition(request,"DISCARDED"); }
            case "WITHDRAW" -> { requireOwner(employee,caller); requireStatus(request,"PENDING","RETURNED"); release(request); transition(request,"WITHDRAWN"); }
            case "APPROVE", "REJECT", "RETURN" -> decide(request,employee,action,payload.reason(),caller);
            case "REASSIGN" -> reassign(request,employee,payload,caller);
            case "RETRY_ROUTE" -> retryRoute(request,employee,payload.reason(),caller);
            default -> throw new IllegalArgumentException("Unsupported leave action");
        }
        return view(request(id,false),employee);
    }

    private void submit(Request request,Employee employee,Authentication caller) {
        requireOwner(employee,caller);
        requireStatus(request,"DRAFT","RETURNED");
        Instant now=clock.instant();
        List<Captured> portions=capture(request,employee,now);
        String unit=employees.currentOrgUnit(employee.getId(),now).orElseThrow(()->new IllegalArgumentException("Organization assignment required"));
        int revision=request.revision()+1;
        Route route=routing.resolve(employee.getId(),employee.getPublicId(),unit,now);
        entitlement.reserve(employee.getId(),request.from().getYear(),request.publicId(),revision,portions.size());
        for(Captured portion:portions) rejectOverlap(employee.getId(),portion);
        Long config=route.configurationPublicId()==null ? null : repository.findRuleSetId(route.configurationPublicId());
        if(portions.stream().anyMatch(portion->!clock.instant().isBefore(portion.occurrenceStart()))) throw new IllegalArgumentException("Leave submission deadline has passed");
        Long policy=repository.findPeriodPolicyId(employee.getId(), request.from().getYear());
        repository.insertRevision(request.id(), revision, request.from().getYear(), policy, portions.size(), unit, employees.currentManagerPublicId(employee.getId(),now).orElse(null), request.from(), request.to(), Portion.valueOf(request.portion()).name(), request.reason(), now, config);
        for(Captured portion:portions) insertPortion(request,employee,revision,portion);
        insertAssignment(request.id(),revision,1,route.managerAccountPublicId(),false);
        insertAssignment(request.id(),revision,2,route.hrAccountPublicId(),false);
        recordRoute(request.id(),revision,null,route);
        requireOwner(employee,caller);
        repository.markPending(revision, request.id());
    }

    private List<Captured> capture(Request request,Employee employee,Instant now) {
        if(request.from().getYear()!=request.to().getYear() || request.from().isAfter(request.to())
            || request.to().isAfter(request.from().plusDays(365))
            || !now.isBefore(LocalDate.of(request.from().getYear()+1,1,1).atStartOfDay(ZONE).toInstant())) {
            throw new IllegalArgumentException("Leave dates must lie in one unexpired year");
        }
        List<Captured> result=new ArrayList<>();
        for(LocalDate date=request.from();!date.isAfter(request.to());date=date.plusDays(1)) {
            if(employee.getEmploymentStartDate()!=null && date.isBefore(employee.getEmploymentStartDate())
                || employee.getEmploymentEndDate()!=null && date.isAfter(employee.getEmploymentEndDate())) {
                throw new IllegalArgumentException("Leave date outside employment");
            }
            AttendanceOccurrence occurrence;
            try {
                var pinned=resolver.findPinned(employee.getId(),date);
                if(pinned.isEmpty() && resolver.computeEffective(employee.getId(),date).holidayName()!=null) {
                    if(Portion.valueOf(request.portion())==Portion.FULL) continue;
                    throw new IllegalArgumentException("Partial leave requires working date");
                }
                occurrence=pinned.isPresent()?pinned.get():resolver.resolveOrPin(employee.getId(),date);
            }
            catch(WorkContextException ex) {
                if("NON_WORKING_DATE".equals(ex.getReason()) && Portion.valueOf(request.portion())==Portion.FULL) continue;
                throw ex;
            }
            if(occurrence.isHoliday()) {
                if(Portion.valueOf(request.portion())==Portion.FULL) continue;
                throw new IllegalArgumentException("Partial leave requires working date");
            }
            if(!now.isBefore(occurrence.getStartAt())) throw new IllegalArgumentException("Leave submission deadline has passed");
            Instant middle=occurrence.getStartAt().plus(Duration.between(occurrence.getStartAt(),occurrence.getEndAt()).dividedBy(2));
            if(Portion.valueOf(request.portion())!=Portion.SECOND_HALF) result.add(new Captured(date,1,occurrence.getStartAt(),middle,occurrence.getId(),occurrence.getStartAt()));
            if(Portion.valueOf(request.portion())!=Portion.FIRST_HALF) result.add(new Captured(date,2,middle,occurrence.getEndAt(),occurrence.getId(),occurrence.getStartAt()));
        }
        if(result.isEmpty()) throw new IllegalArgumentException("Leave requires at least one working portion");
        return result;
    }

    private void rejectOverlap(Long employee,Captured portion) {
        Integer overlap=repository.countOverlappingOccupancy(employee, portion.end(), portion.start());
        if(overlap!=null && overlap>0) throw new LeaveConflictException("LEAVE_OVERLAP");
    }

    private void insertPortion(Request request,Employee employee,int revision,Captured portion) {
        repository.insertPortion(request.id(), revision, employee.getId(), portion.occurrence(), portion.date(), portion.half(), portion.start(), portion.end());
        Long id=repository.findPortionId(request.id(), revision, portion.date(), portion.half());
        repository.insertOccupancy(id, request.id(), revision, employee.getId(), portion.date(), portion.half(), portion.start(), portion.end());
    }

    private void decide(Request request,Employee employee,String outcome,String reason,Authentication caller) {
        requireStatus(request,"PENDING");
        if(blocked(request,employee)!=null) throw new LeaveConflictException("ROUTE_BLOCKED");
        int step=activeStep(request);
        Reviewer reviewer=reviewer(request,step);
        UserAccount actor=access.requireActive(caller,clock.instant());
        requireReviewer(reviewer,actor,employee,step);
        if(!"APPROVE".equals(outcome)) requireReason(reason);
        if("APPROVE".equals(outcome) && step==2) {
            List<UUID> manager=repository.findApprovingManagerEmployees(request.id(), request.revision());
            if(manager.isEmpty() || manager.getFirst().equals(reviewer.employee())) throw new LeaveConflictException("DISTINCT_REVIEWERS_REQUIRED");
            int expected=repository.findRevisionHalfUnits(request.id(), request.revision());
            int occupied=repository.countActiveOccupancy(request.id(), request.revision());
            if(expected!=occupied) throw new LeaveConflictException("RESERVATION_CHANGED");
            entitlement.consume(employee.getId(),request.from().getYear(),request.publicId(),request.revision());
        }
        requireReviewer(reviewer,access.requireActive(caller,clock.instant()),employee,step);
        repository.insertDecision(request.id(), request.revision(), step, actor.getPublicId(), reviewer.employee(), outcome, reason, clock.instant());
        if("APPROVE".equals(outcome)) {
            if(step==2) transition(request,"APPROVED");
            else repository.incrementRequestVersion(request.id());
        } else { release(request); transition(request,"RETURN".equals(outcome)?"RETURNED":"REJECTED"); }
    }

    private void release(Request request) {
        if(request.revision()==0) return;
        entitlement.release(request.employeeId(),request.from().getYear(),request.publicId(),request.revision());
        repository.releaseOccupancy(request.id(), request.revision());
    }

    private void reassign(Request request,Employee employee,Action action,Authentication caller) {
        requireStatus(request,"PENDING"); requireReason(action.reason()); requireHr(employee,caller);
        int step=activeStep(request);
        if(action.step()==null || action.step()!=step || action.reviewerAccountPublicId()==null) throw new IllegalArgumentException("Only active step may be reassigned");
        UUID candidate=action.reviewerAccountPublicId();
        if(!routing.eligible(candidate,employee.getPublicId(),currentUnit(employee),step,action.fallback(),clock.instant())) throw new IllegalArgumentException("Ineligible reviewer");
        if(step==2) {
            List<UUID> completed=repository.findManagerDecisionEmployees(request.id(), request.revision());
            if(!completed.isEmpty() && completed.getFirst().equals(routing.reviewerEmployee(candidate))) throw new IllegalArgumentException("Distinct reviewers required");
        } else if(routing.samePerson(candidate,reviewer(request,2).account())) throw new IllegalArgumentException("Distinct reviewers required");
        repository.deactivateStepAssignment(request.id(), request.revision(), step);
        insertAssignment(request.id(),request.revision(),step,candidate,action.fallback());
        recovery(request,"REASSIGN",action.reason());
        repository.incrementReassignedRequestVersion(request.id());
    }

    private void retryRoute(Request request,Employee employee,String reason,Authentication caller) {
        requireStatus(request,"PENDING"); requireReason(reason); requireHr(employee,caller);
        if(activeStep(request)!=1) throw new LeaveConflictException("ROUTE_ALREADY_DECIDED");
        if(blocked(request,employee)==null) throw new LeaveConflictException("ROUTE_NOT_BLOCKED");
        UUID manager=repository.findRevisionManager(request.id(), request.revision());
        Route route=routing.resolveWithManager(employee.getPublicId(),revisionUnit(request),manager,clock.instant());
        repository.deactivateAssignments(request.id(), request.revision());
        insertAssignment(request.id(),request.revision(),1,route.managerAccountPublicId(),false);
        insertAssignment(request.id(),request.revision(),2,route.hrAccountPublicId(),false);
        recordRoute(request.id(),request.revision(),null,route);
        recovery(request,"RETRY_ROUTE",reason);
        repository.incrementReroutedRequestVersion(request.id());
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public CancellationView createCancellation(UUID requestId,UUID id,Action payload,Authentication caller) {
        Employee employee=lockEmployee(payload.employeePublicId()); requireOwner(employee,caller); requireReason(payload.reason());
        Request request=ownedRequest(requestId,employee,true); checkVersion(request,payload.expectedVersion()); requireStatus(request,"APPROVED");
        if(!repository.findPendingCancellationIds(request.id()).isEmpty()) throw new LeaveConflictException("CANCELLATION_PENDING");
        String unit=currentUnit(employee);
        Route route=routing.resolve(employee.getId(),employee.getPublicId(),unit,clock.instant());
        Long config=route.configurationPublicId()==null?null:repository.findCancellationRuleSetId(route.configurationPublicId());
        repository.insertCancellation(id, request.id(), payload.reason(), unit, config, clock.instant());
        Cancellation cancellation=cancellation(id,false);
        insertCancellationAssignment(cancellation,route.cancellationAccountPublicId());
        recordRoute(request.id(),request.revision(),id,route);
        repository.incrementCancellationRequestVersion(request.id());
        return cancellationView(cancellation,request,employee);
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public CancellationView actCancellation(UUID requestId,UUID id,String action,Action payload,Authentication caller) {
        Employee employee=lockEmployee(payload.employeePublicId());
        Request request=ownedRequest(requestId,employee,true); requireStatus(request,"APPROVED");
        Cancellation cancel=cancellation(id,true);
        if(!cancel.requestId().equals(request.id())) throw new ResourceNotFoundException();
        if(cancel.version()!=payload.expectedVersion()) throw new LeaveConflictException("VERSION_CHANGED");
        if(!"PENDING".equals(cancel.status())) throw new LeaveConflictException("CANCELLATION_STALE");
        if("WITHDRAW".equals(action)) {
            requireOwner(employee,caller); transitionCancellation(cancel,"WITHDRAWN");
        } else if("REASSIGN".equals(action) || "RETRY_ROUTE".equals(action)) {
            requireHr(employee,caller); requireReason(payload.reason());
            UUID target=payload.reviewerAccountPublicId();
            if("RETRY_ROUTE".equals(action)) {
                Route route=routing.resolve(employee.getId(),employee.getPublicId(),cancel.unit(),clock.instant());
                target=route.cancellationAccountPublicId(); recordRoute(request.id(),request.revision(),id,route);
            }
            if(target==null || !routing.eligible(target,employee.getPublicId(),currentUnit(employee),2,false,clock.instant())) throw new IllegalArgumentException("Ineligible cancellation reviewer");
            repository.deactivateCancellationAssignments(cancel.id());
            insertCancellationAssignment(cancel,target);
            recovery(request,"CANCEL_"+action,payload.reason());
            repository.incrementCancellationVersion(cancel.id());
        } else if("APPROVE".equals(action) || "REJECT".equals(action)) {
            Reviewer reviewer=cancellationReviewer(cancel);
            UserAccount actor=access.requireActive(caller,clock.instant());
            requireReviewer(reviewer,actor,employee,2);
            if("REJECT".equals(action)) requireReason(payload.reason());
            if("APPROVE".equals(action)) {
                entitlement.reverse(employee.getId(),request.from().getYear(),request.publicId(),request.revision());
                repository.releaseCancelledOccupancy(request.id(), request.revision());
                transition(request,"CANCELLED");
            }
            requireReviewer(reviewer,access.requireActive(caller,clock.instant()),employee,2);
            repository.insertCancellationDecision(cancel.id(), actor.getPublicId(), action, payload.reason(), clock.instant());
            transitionCancellation(cancel,"APPROVE".equals(action)?"APPROVED":"REJECTED");
        } else throw new IllegalArgumentException("Unsupported cancellation action");
        return cancellationView(cancellation(id,false),request(requestId,false),employee);
    }

    @Transactional
    public RequestView get(UUID id,Authentication caller) {
        admit(); Request preliminary=request(id,false); Employee employee=employees.findInternalById(preliminary.employeeId()).orElseThrow(ResourceNotFoundException::new);
        requireRead(preliminary,employee,caller);
        if(!lockRead(employee)) throw new LeaveConflictException("ACCOUNT_CHANGED");
        Request request=request(id,false);
        requireRead(request,employee,caller); RequestView result=view(request,employee); requireRead(request,employee,caller); return result;
    }

    @Transactional
    public List<RequestView> list(UUID employeeId,Authentication caller) {
        return listPage(employeeId,null,100,caller).items();
    }

    private record ListScope(boolean complete,UUID reviewer,UUID employee) { }
    @Transactional
    public RequestPage listPage(UUID employeeId,UUID after,int limit,Authentication caller) {
        requireLimit(limit); admit(); Employee employee=employees.requireInternalByPublicId(employeeId);
        ListScope scope=listScope(employee,caller);
        boolean locked=lockRead(employee); scope=listScope(employee,caller);
        if(!locked) return new RequestPage(List.of(),null);
        long cursor=0;
        if(after!=null) {
            Request previous=ownedRequest(after,employee,false); requireRead(previous,employee,caller); cursor=previous.id();
        }
        List<Request> rows=repository.findVisibleRequests(employee.getId(), cursor, scope.complete(), scope.reviewer(), scope.employee(), limit+1);
        boolean more=rows.size()>limit; List<Request> visible=more?rows.subList(0,limit):rows;
        List<RequestView> result=visible.stream().map(row->view(row,employee)).toList();
        listScope(employee,caller);
        return new RequestPage(result,more?visible.getLast().publicId():null);
    }

    private ListScope listScope(Employee employee,Authentication caller) {
        UserAccount actor=access.requireActive(caller,clock.instant());
        UUID bound=access.ownEmployeePublicId(actor).orElse(null);
        if(access.coversUnit(actor.getId(),currentUnit(employee),clock.instant())) return new ListScope(true,actor.getPublicId(),bound);
        if(employee.getPublicId().equals(bound) && !access.activeRoles(actor.getId(),clock.instant()).isEmpty()) return new ListScope(true,actor.getPublicId(),bound);
        if(routing.eligible(actor.getPublicId(),employee.getPublicId(),currentUnit(employee),1,false,clock.instant())) return new ListScope(false,actor.getPublicId(),bound);
        throw new ResourceNotFoundException();
    }


    private record HistorySnapshot(RequestView request,List<RouteSnapshot> route,List<PortionView> portions,CancellationView cancellation) { }
    @Transactional(propagation=Propagation.MANDATORY)
    public void recordHistory(UUID requestId,UUID cancellationId,String action) {
        Request request=request(requestId,false);
        Employee employee=employees.findInternalById(request.employeeId()).orElseThrow(ResourceNotFoundException::new);
        List<RouteSnapshot> route=repository.findRouteSnapshot(request.id(), request.revision());
        List<PortionView> captured=repository.findCapturedPortions(request.id(), request.revision()).stream().map(LeaveWorkflowMapper::toPortionView).toList();
        CancellationView cancellation=cancellationId==null?null:cancellationView(cancellation(cancellationId,false),request,employee);
        String payload=mapper.writeValueAsString(new HistorySnapshot(view(request,employee),route,captured,cancellation));
        repository.insertHistory(UUID.randomUUID(), request.id(), cancellationId, request.revision(), action, payload, clock.instant());
    }

    @Transactional
    public TimelinePage timeline(UUID requestId,UUID cancellationId,UUID after,int limit,Authentication caller) {
        requireLimit(limit); get(requestId,caller); Request request=request(requestId,false);
        if(cancellationId!=null) getCancellation(requestId,cancellationId,caller);
        long cursor=0;
        if(after!=null) cursor=repository.findHistoryCursor(after, request.id(), cancellationId, cancellationId).stream().findFirst().orElseThrow(ResourceNotFoundException::new);
        List<TimelineEntry> rows=repository.findTimeline(request.id(), cursor, cancellationId, cancellationId, limit+1).stream().map(LeaveWorkflowMapper::toTimelineEntry).toList();
        boolean more=rows.size()>limit; List<TimelineEntry> result=more?rows.subList(0,limit):rows;
        get(requestId,caller); return new TimelinePage(result,more?result.getLast().publicId():null);
    }
    private void requireLimit(int limit) { if(limit<1 || limit>100) throw new IllegalArgumentException("Page limit must be 1..100"); }

    @Transactional
    public SummaryPage summary(UUID employeeId,LocalDate from,LocalDate to,UUID after,int limit,Authentication caller) {
        requireLimit(limit);
        if(from==null || to==null || !from.isBefore(to) || from.plusDays(366).isBefore(to)) throw new IllegalArgumentException("Summary range must be 1..366 days");
        admit(); Employee employee=employees.requireInternalByPublicId(employeeId); listScope(employee,caller);
        boolean locked=lockRead(employee); listScope(employee,caller);
        if(!locked) return new SummaryPage(List.of(),null);
        long cursor=0;
        if(after!=null) cursor=repository.findSummaryCursor(after, employee.getId(), from, to).stream().findFirst().orElseThrow(ResourceNotFoundException::new);
        List<Summary> rows=repository.findSummary(employee.getId(), from, to, cursor, limit+1).stream().map(LeaveWorkflowMapper::toSummary).toList();
        boolean more=rows.size()>limit; List<Summary> result=more?rows.subList(0,limit):rows;
        listScope(employee,caller); return new SummaryPage(result,more?result.getLast().publicId():null);
    }

    @Transactional
    public List<History> history(UUID id,Authentication caller) {
        get(id,caller); Request request=request(id,false);
        List<History> result=repository.findDecisionHistory(request.id()).stream().map(LeaveWorkflowMapper::toHistory).toList();
        get(id,caller); return result;
    }

    @Transactional
    public List<PortionView> portions(UUID id,Authentication caller) {
        get(id,caller); Request request=request(id,false);
        List<PortionView> result=repository.findPortions(request.id(), request.revision()).stream().map(LeaveWorkflowMapper::toPortionView).toList();
        get(id,caller); return result;
    }

    @Transactional
    public CancellationView getCancellation(UUID requestId,UUID id,Authentication caller) {
        get(requestId,caller); Request request=request(requestId,false); Cancellation cancel=cancellation(id,false);
        if(!cancel.requestId().equals(request.id())) throw new ResourceNotFoundException();
        Employee employee=employees.findInternalById(request.employeeId()).orElseThrow(ResourceNotFoundException::new);
        CancellationView result=cancellationView(cancel,request,employee);
       
        if(cancellation(id,false).version()!=cancel.version()) throw new LeaveConflictException("VERSION_CHANGED");
        return result;
    }

    @Transactional
    public List<History> cancellationHistory(UUID requestId,UUID id,Authentication caller) {
        getCancellation(requestId,id,caller);
        Cancellation cancel=cancellation(id,false);
        List<History> result=repository.findCancellationHistory(cancel.id()).stream().map(LeaveWorkflowMapper::toHistory).toList();
        getCancellation(requestId,id,caller); return result;
    }

    @Transactional
    public Route preview(UUID employeeId,Authentication caller) {
        admit(); Employee employee=employees.requireInternalByPublicId(employeeId); lockRead(employee); verifyReplay(employeeId,caller);
        Route route=routing.resolve(employee.getId(),employee.getPublicId(),currentUnit(employee),clock.instant()); verifyReplay(employeeId,caller); return route;
    }

    @Transactional
    public void verifyReplay(UUID employeePublicId,Authentication caller) {
        Employee employee=employees.requireInternalByPublicId(employeePublicId);
        UserAccount account=access.requireActive(caller,clock.instant());
        access.requireEmployeeRead(account,employeePublicId,currentUnit(employee),clock.instant());
    }

    @Transactional
    public void verifyCommand(String type,JsonNode payload,Authentication caller) {
        if(type.equals("LEAVE_RULE_PUBLISH") || type.equals("LEAVE_RULE_ACTIVATE")) {
            verifyConfiguration(type,payload,caller); return;
        }
        Employee employee=employees.requireInternalByPublicId(payloadUuid(payload,"employeePublicId"));
        if(type.equals("LEAVE_REQUEST_CREATE") || type.equals("LEAVE_REQUEST_EDIT")
            || type.equals("LEAVE_REQUEST_SUBMIT") || type.equals("LEAVE_REQUEST_RESUBMIT")
            || type.equals("LEAVE_REQUEST_DISCARD") || type.equals("LEAVE_REQUEST_WITHDRAW")
            || type.equals("LEAVE_CANCELLATION_CREATE") || type.equals("LEAVE_CANCELLATION_WITHDRAW")) {
            requireOwner(employee,caller); return;
        }
        Request request=ownedRequest(payloadUuid(payload,"requestPublicId"),employee,false);
        if(type.equals("LEAVE_REQUEST_REASSIGN") || type.equals("LEAVE_REQUEST_RETRY_ROUTE")
            || type.equals("LEAVE_CANCELLATION_REASSIGN") || type.equals("LEAVE_CANCELLATION_RETRY_ROUTE")) {
            requireHr(employee,caller); return;
        }
        Reviewer selected;
        int step;
        if(type.equals("LEAVE_CANCELLATION_APPROVE") || type.equals("LEAVE_CANCELLATION_REJECT")) {
            Cancellation cancellation=cancellation(payloadUuid(payload,"cancellationPublicId"),false);
            if(!cancellation.requestId().equals(request.id())) throw new ResourceNotFoundException();
            selected=cancellationReviewer(cancellation); step=2;
            requireReviewer(selected,access.requireActive(caller,clock.instant()),employee,step);
            return;
        }
        UserAccount account=access.requireActive(caller,clock.instant());
        for(int candidate=1;candidate<=2;candidate++) {
            Reviewer assigned=reviewer(request,candidate);
            if(Objects.equals(assigned.account(),account.getPublicId())) {
                requireReviewer(assigned,account,employee,candidate); return;
            }
        }
        throw new ResourceNotFoundException();
    }

    @Transactional
    public void verifyReplay(String type,JsonNode payload,JsonNode recordedResult,Authentication caller) {
        if(type.equals("LEAVE_RULE_PUBLISH") || type.equals("LEAVE_RULE_ACTIVATE")) {
            verifyConfiguration(type,payload,caller); return;
        }
        Employee employee=employees.requireInternalByPublicId(payloadUuid(payload,"employeePublicId"));
        UserAccount account=access.requireActive(caller,clock.instant());
        if(access.ownEmployeePublicId(account).filter(employee.getPublicId()::equals).isPresent()) {
            requireOwner(employee,caller); return;
        }
        if(access.coversUnit(account.getId(),currentUnit(employee),clock.instant())) return;
        Request request=ownedRequest(payloadUuid(payload,"requestPublicId"),employee,false);
        if(type.equals("LEAVE_CANCELLATION_APPROVE") || type.equals("LEAVE_CANCELLATION_REJECT")) {
            Cancellation cancellation=cancellation(payloadUuid(payload,"cancellationPublicId"),false);
            if(!cancellation.requestId().equals(request.id())) throw new ResourceNotFoundException();
            requireReviewer(cancellationReviewer(cancellation),account,employee,2); return;
        }
        for(int step=1;step<=2;step++) {
            Reviewer selected=reviewer(request,step);
            if(Objects.equals(selected.account(),account.getPublicId())) {
                requireReviewer(selected,account,employee,step); return;
            }
        }
        throw new ResourceNotFoundException();
    }

    private void verifyConfiguration(String type,JsonNode payload,Authentication caller) {
        UserAccount hr=access.requireHrOperation(caller,clock.instant());
        if(type.equals("LEAVE_RULE_ACTIVATE")) {
            if(!access.hrAuthority(hr.getId(),clock.instant()).organizationWide()) throw new AccessDeniedException("Global activation requires organization-wide HR");
            return;
        }
        JsonNode publication=payload.get("publication");
        JsonNode rules=publication==null?null:publication.get("rules");
        if(rules==null || !rules.isArray()) throw new IllegalArgumentException("Invalid leave rule snapshot");
        for(JsonNode rule:rules) {
            if(rule==null || rule.isNull() || !rule.isObject()) throw new IllegalArgumentException("Rule must be an object");
            JsonNode unit=rule.get("orgUnit");
            if(unit==null || !unit.isTextual()) throw new IllegalArgumentException("Rule unit required");
            access.requireTargetScope(hr.getId(),unit.asString(),clock.instant());
        }
    }

    private UUID payloadUuid(JsonNode payload,String field) {
        JsonNode value=payload.get(field);
        if(value==null || !value.isTextual()) throw new IllegalArgumentException("Missing command resource identity");
        return UUID.fromString(value.asString());
    }

    private Employee lockEmployee(UUID publicId) {
        entities.flush(); entities.clear();
        Employee employee=employees.requireInternalByPublicId(publicId);
        attendance.ensureLocked(employee.getId()); entitlement.lock(employee.getId()); return employee;
    }

    private void admit() { admission.acquire(AdmissionGuard.Mode.SHARED); entities.clear(); }
    private void requireOwner(Employee employee,Authentication caller) {
        UserAccount account=access.requireActive(caller,clock.instant());
        if(access.activeRoles(account.getId(),clock.instant()).isEmpty()
            || !access.ownEmployeePublicId(account).filter(employee.getPublicId()::equals).isPresent()) throw new ResourceNotFoundException();
    }
    private void requireHr(Employee employee,Authentication caller) {
        UserAccount hr=access.requireHrOperation(caller,clock.instant()); access.requireTargetScope(hr.getId(),currentUnit(employee),clock.instant());
    }
    private void requireRead(Request request,Employee employee,Authentication caller) {
        UserAccount actor=access.requireActive(caller,clock.instant());
        if("PENDING".equals(request.status())) {
            Reviewer reviewer=reviewer(request,activeStep(request));
            if(Objects.equals(actor.getPublicId(),reviewer.account())
                && routing.eligible(reviewer.account(),employee.getPublicId(),currentUnit(employee),activeStep(request),reviewer.fallback(),clock.instant())) return;
        }
        access.requireEmployeeRead(actor,employee.getPublicId(),currentUnit(employee),clock.instant());
    }
    private void requireReviewer(Reviewer reviewer,UserAccount actor,Employee employee,int step) {
        if(reviewer.account()==null || !reviewer.account().equals(actor.getPublicId())
            || !Objects.equals(reviewer.employee(),access.ownEmployeePublicId(actor).orElse(null))
            || !routing.eligible(reviewer.account(),employee.getPublicId(),currentUnit(employee),step,reviewer.fallback(),clock.instant())) throw new AccessDeniedException("Current assigned eligible reviewer required");
    }
    private String currentUnit(Employee employee) { return employees.currentOrgUnit(employee.getId(),clock.instant()).orElse(null); }
    private String revisionUnit(Request request) { return repository.findRevisionUnit(request.id(), request.revision()); }
    private Request ownedRequest(UUID id,Employee employee,boolean lock) {
        Request request=request(id,false);
        if(!request.employeeId().equals(employee.getId())) throw new ResourceNotFoundException();
        if(lock) {
            entitlement.lockPeriod(employee.getId(),request.from().getYear());
            request=request(id,true);
        }
        return request;
    }
    private Request request(UUID id,boolean lock) {
        return repository.findRequest(lock, id)
            .stream().findFirst().orElseThrow(ResourceNotFoundException::new);
    }

    private RequestView view(Request request,Employee employee) {
        return new RequestView(request.publicId(),employee.getPublicId(),request.status(),request.revision(),request.version(),request.from(),request.to(),Portion.valueOf(request.portion()),request.reason(),blocked(request,employee));
    }
    private String blocked(Request request,Employee employee) {
        if(!"PENDING".equals(request.status())) return null;
        int step=activeStep(request); Reviewer current=reviewer(request,step);
        if(current.account()==null) {
            List<String> reasons=repository.findBlockedReasons(request.id(), request.revision());
            return reasons.isEmpty() || reasons.getFirst()==null?"NO_DESIGNATED_REVIEWER":reasons.getFirst();
        }
        if(!Objects.equals(current.employee(),boundEmployee(current.account()))) return "REVIEWER_INELIGIBLE";
        if(!routing.eligible(current.account(),employee.getPublicId(),currentUnit(employee),step,current.fallback(),clock.instant())) return "REVIEWER_INELIGIBLE";
        if(step==1 && routing.samePerson(current.account(),reviewer(request,2).account())) return "DISTINCT_REVIEWERS_REQUIRED";
        return null;
    }
    private int activeStep(Request request) {
        Integer first=repository.countManagerApprovals(request.id(), request.revision()); return first!=null && first>0?2:1;
    }
    private Reviewer reviewer(Request request,int step) {
        return repository.findReviewer(request.id(), request.revision(), step).stream().findFirst().orElse(new Reviewer(null,null,false));
    }
    private void insertAssignment(Long request,int revision,int step,UUID account,boolean fallback) {
        UUID employee=boundEmployee(account);
        repository.insertAssignment(request, revision, step, account, employee, fallback, clock.instant());
    }
    private void recovery(Request request,String action,String reason) {
        repository.insertRecovery(request.id(), request.revision(), action, reason, clock.instant());
    }
    private void transition(Request request,String status) { repository.transitionRequest(status, request.id()); }
    private void checkVersion(Request request,long version) { if(request.version()!=version) throw new LeaveConflictException("VERSION_CHANGED"); }
    private void requireStatus(Request request,String... allowed) { if(!List.of(allowed).contains(request.status())) throw new LeaveConflictException("REQUEST_STALE"); }
    private void requireReason(String reason) { if(!StringUtils.hasText(reason) || reason.length()>2000) throw new IllegalArgumentException("Reason is required and limited to 2000 characters"); }
    private void validateDraft(Draft draft) {
        requireReason(draft.reason());
        if(draft.fromDate()==null || draft.toDate()==null || draft.portion()==null || draft.fromDate().isAfter(draft.toDate())
            || draft.fromDate().getYear()!=draft.toDate().getYear() || draft.portion()!=Portion.FULL && !draft.fromDate().equals(draft.toDate())) throw new IllegalArgumentException("Invalid annual leave dates");
    }
    private Cancellation cancellation(UUID id,boolean lock) {
        return repository.findCancellation(lock, id).stream().findFirst().orElseThrow(ResourceNotFoundException::new);
    }
    private Reviewer cancellationReviewer(Cancellation cancel) {
        return repository.findCancellationReviewer(cancel.id()).stream().findFirst().orElse(new Reviewer(null,null,false));
    }
    private void insertCancellationAssignment(Cancellation cancel,UUID account) {
        repository.insertCancellationAssignment(cancel.id(), account, boundEmployee(account), clock.instant());
    }
    private CancellationView cancellationView(Cancellation cancel,Request request,Employee employee) {
        Reviewer reviewer=cancellationReviewer(cancel);
        String blocked="PENDING".equals(cancel.status()) && (reviewer.account()==null || !Objects.equals(reviewer.employee(),boundEmployee(reviewer.account())) || !routing.eligible(reviewer.account(),employee.getPublicId(),currentUnit(employee),2,false,clock.instant()))?"REVIEWER_INELIGIBLE":null;
        return new CancellationView(cancel.publicId(),request.publicId(),cancel.status(),cancel.version(),cancel.reason(),blocked);
    }
    private void transitionCancellation(Cancellation cancel,String status) { repository.transitionCancellation(status, cancel.id()); }
    private void recordRoute(Long request,int revision,UUID cancellation,Route route) {
        repository.insertRouteEvaluation(request, revision, cancellation, route.configurationPublicId(), route.managerAccountPublicId(), route.hrAccountPublicId(), route.cancellationAccountPublicId(), route.blockedReason(), clock.instant());
    }
    private boolean lockRead(Employee employee) { boolean locked=entitlement.lockExisting(employee.getId()); entities.clear(); return locked; }
    private UUID boundEmployee(UUID account) { return account==null?null:access.findAccountByPublicId(account).flatMap(access::ownEmployeePublicId).orElse(null); }
}
