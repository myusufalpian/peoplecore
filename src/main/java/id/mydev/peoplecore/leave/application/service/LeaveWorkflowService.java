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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private static final String COLUMNS="id,public_id,employee_id,status,revision,version,from_date,to_date,portion,reason";
    private static final ZoneId ZONE=ZoneId.of("Asia/Jakarta");
    private final JdbcTemplate jdbc;
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

    public LeaveWorkflowService(JdbcTemplate jdbc,AccountAccessService access,EmployeeService employees,
                                AttendanceAccountGuard attendance,AttendanceOccurrenceResolver resolver,
                                LeaveEntitlementService entitlement,LeaveRoutingService routing,
                                AdmissionGuard admission,EntityManager entities,Clock clock,ObjectMapper mapper) {
        this.jdbc=jdbc; this.access=access; this.employees=employees; this.attendance=attendance;
        this.resolver=resolver; this.entitlement=entitlement; this.routing=routing;
        this.admission=admission; this.entities=entities; this.clock=clock; this.mapper=mapper;
    }

    private record Request(Long id,UUID publicId,Long employeeId,String status,int revision,
                           long version,LocalDate from,LocalDate to,Portion portion,String reason) { }
    private record Reviewer(UUID account,UUID employee,boolean fallback) { }
    private record Captured(LocalDate date,int half,Instant start,Instant end,Long occurrence,Instant occurrenceStart) { }
    private record Cancellation(Long id,UUID publicId,Long requestId,String status,long version,String reason,String unit) { }

    @Transactional(propagation=Propagation.MANDATORY)
    public RequestView create(UUID id,Draft payload,Authentication caller) {
        Employee employee=lockEmployee(payload.employeePublicId());
        requireOwner(employee,caller);
        validateDraft(payload);
        jdbc.update("INSERT INTO leave_request(public_id,employee_id,status,from_date,to_date,portion,reason,created_at) VALUES (?,?,'DRAFT',?,?,?,?,?)",
            id,employee.getId(),payload.fromDate(),payload.toDate(),payload.portion().name(),payload.reason(),Timestamp.from(clock.instant()));
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
        jdbc.update("UPDATE leave_request SET from_date=?,to_date=?,portion=?,reason=?,version=version+1 WHERE id=?",
            payload.fromDate(),payload.toDate(),payload.portion().name(),payload.reason(),request.id());
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
        Long config=route.configurationPublicId()==null ? null : jdbc.queryForObject("SELECT id FROM leave_rule_set WHERE public_id=?",Long.class,route.configurationPublicId());
        if(portions.stream().anyMatch(portion->!clock.instant().isBefore(portion.occurrenceStart()))) throw new IllegalArgumentException("Leave submission deadline has passed");
        Long policy=jdbc.queryForObject("SELECT policy_revision_id FROM leave_period WHERE employee_id=? AND period_year=?",Long.class,employee.getId(),request.from().getYear());
        jdbc.update("INSERT INTO leave_revision(request_id,revision,year,policy_revision_id,half_units,org_unit,manager_employee_public_id,from_date,to_date,portion,reason,submitted_at,rule_set_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
            request.id(),revision,request.from().getYear(),policy,portions.size(),unit,employees.currentManagerPublicId(employee.getId(),now).orElse(null),request.from(),request.to(),request.portion().name(),request.reason(),Timestamp.from(now),config);
        for(Captured portion:portions) insertPortion(request,employee,revision,portion);
        insertAssignment(request.id(),revision,1,route.managerAccountPublicId(),false);
        insertAssignment(request.id(),revision,2,route.hrAccountPublicId(),false);
        recordRoute(request.id(),revision,null,route);
        requireOwner(employee,caller);
        jdbc.update("UPDATE leave_request SET status='PENDING',revision=?,version=version+1 WHERE id=?",revision,request.id());
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
                    if(request.portion()==Portion.FULL) continue;
                    throw new IllegalArgumentException("Partial leave requires working date");
                }
                occurrence=pinned.isPresent()?pinned.get():resolver.resolveOrPin(employee.getId(),date);
            }
            catch(WorkContextException ex) {
                if("NON_WORKING_DATE".equals(ex.getReason()) && request.portion()==Portion.FULL) continue;
                throw ex;
            }
            if(occurrence.isHoliday()) {
                if(request.portion()==Portion.FULL) continue;
                throw new IllegalArgumentException("Partial leave requires working date");
            }
            if(!now.isBefore(occurrence.getStartAt())) throw new IllegalArgumentException("Leave submission deadline has passed");
            Instant middle=occurrence.getStartAt().plus(Duration.between(occurrence.getStartAt(),occurrence.getEndAt()).dividedBy(2));
            if(request.portion()!=Portion.SECOND_HALF) result.add(new Captured(date,1,occurrence.getStartAt(),middle,occurrence.getId(),occurrence.getStartAt()));
            if(request.portion()!=Portion.FIRST_HALF) result.add(new Captured(date,2,middle,occurrence.getEndAt(),occurrence.getId(),occurrence.getStartAt()));
        }
        if(result.isEmpty()) throw new IllegalArgumentException("Leave requires at least one working portion");
        return result;
    }

    private void rejectOverlap(Long employee,Captured portion) {
        Integer overlap=jdbc.queryForObject("SELECT count(id) FROM leave_occupancy WHERE employee_id=? AND active=TRUE AND start_at<? AND end_at>?",
            Integer.class,employee,Timestamp.from(portion.end()),Timestamp.from(portion.start()));
        if(overlap!=null && overlap>0) throw new LeaveConflictException("LEAVE_OVERLAP");
    }

    private void insertPortion(Request request,Employee employee,int revision,Captured portion) {
        jdbc.update("INSERT INTO leave_portion(request_id,revision,employee_id,occurrence_id,work_date,half,start_at,end_at) VALUES (?,?,?,?,?,?,?,?)",
            request.id(),revision,employee.getId(),portion.occurrence(),portion.date(),portion.half(),Timestamp.from(portion.start()),Timestamp.from(portion.end()));
        Long id=jdbc.queryForObject("SELECT id FROM leave_portion WHERE request_id=? AND revision=? AND work_date=? AND half=?",Long.class,
            request.id(),revision,portion.date(),portion.half());
        jdbc.update("INSERT INTO leave_occupancy(portion_id,request_id,revision,employee_id,work_date,half,start_at,end_at) VALUES (?,?,?,?,?,?,?,?)",
            id,request.id(),revision,employee.getId(),portion.date(),portion.half(),Timestamp.from(portion.start()),Timestamp.from(portion.end()));
    }

    private void decide(Request request,Employee employee,String outcome,String reason,Authentication caller) {
        requireStatus(request,"PENDING");
        if(blocked(request,employee)!=null) throw new LeaveConflictException("ROUTE_BLOCKED");
        int step=activeStep(request);
        Reviewer reviewer=reviewer(request,step);
        UserAccount actor=access.requireActive(caller,clock.instant());
        requireReviewer(reviewer,actor,employee,revisionUnit(request),step);
        if(!"APPROVE".equals(outcome)) requireReason(reason);
        if("APPROVE".equals(outcome) && step==2) {
            List<UUID> manager=jdbc.query("SELECT reviewer_employee_public_id FROM leave_decision WHERE request_id=? AND revision=? AND step=1 AND outcome='APPROVE'",(rs,n)->rs.getObject(1,UUID.class),request.id(),request.revision());
            if(manager.isEmpty() || manager.getFirst().equals(reviewer.employee())) throw new LeaveConflictException("DISTINCT_REVIEWERS_REQUIRED");
            int expected=jdbc.queryForObject("SELECT half_units FROM leave_revision WHERE request_id=? AND revision=?",Integer.class,request.id(),request.revision());
            int occupied=jdbc.queryForObject("SELECT count(id) FROM leave_occupancy WHERE request_id=? AND revision=? AND active=TRUE",Integer.class,request.id(),request.revision());
            if(expected!=occupied) throw new LeaveConflictException("RESERVATION_CHANGED");
            entitlement.consume(employee.getId(),request.from().getYear(),request.publicId(),request.revision());
        }
        requireReviewer(reviewer,access.requireActive(caller,clock.instant()),employee,revisionUnit(request),step);
        jdbc.update("INSERT INTO leave_decision(request_id,revision,step,reviewer_account_public_id,reviewer_employee_public_id,outcome,reason,created_at) VALUES (?,?,?,?,?,?,?,?)",
            request.id(),request.revision(),step,actor.getPublicId(),reviewer.employee(),outcome,reason,Timestamp.from(clock.instant()));
        if("APPROVE".equals(outcome)) {
            if(step==2) transition(request,"APPROVED");
            else jdbc.update("UPDATE leave_request SET version=version+1 WHERE id=?",request.id());
        } else { release(request); transition(request,"RETURN".equals(outcome)?"RETURNED":"REJECTED"); }
    }

    private void release(Request request) {
        if(request.revision()==0) return;
        entitlement.release(request.employeeId(),request.from().getYear(),request.publicId(),request.revision());
        jdbc.update("UPDATE leave_occupancy SET active=FALSE WHERE request_id=? AND revision=? AND active=TRUE",request.id(),request.revision());
    }

    private void reassign(Request request,Employee employee,Action action,Authentication caller) {
        requireStatus(request,"PENDING"); requireReason(action.reason()); requireHr(employee,caller);
        int step=activeStep(request);
        if(action.step()==null || action.step()!=step || action.reviewerAccountPublicId()==null) throw new IllegalArgumentException("Only active step may be reassigned");
        UUID candidate=action.reviewerAccountPublicId();
        if(!routing.eligible(candidate,employee.getPublicId(),currentUnit(employee),step,action.fallback(),clock.instant())) throw new IllegalArgumentException("Ineligible reviewer");
        if(step==2) {
            List<UUID> completed=jdbc.query("SELECT reviewer_employee_public_id FROM leave_decision WHERE request_id=? AND revision=? AND step=1",(rs,n)->rs.getObject(1,UUID.class),request.id(),request.revision());
            if(!completed.isEmpty() && completed.getFirst().equals(routing.reviewerEmployee(candidate))) throw new IllegalArgumentException("Distinct reviewers required");
        } else if(routing.samePerson(candidate,reviewer(request,2).account())) throw new IllegalArgumentException("Distinct reviewers required");
        jdbc.update("UPDATE leave_review_assignment SET active=FALSE WHERE request_id=? AND revision=? AND step=? AND active=TRUE",request.id(),request.revision(),step);
        insertAssignment(request.id(),request.revision(),step,candidate,action.fallback());
        recovery(request,"REASSIGN",action.reason());
        jdbc.update("UPDATE leave_request SET version=version+1 WHERE id=?",request.id());
    }

    private void retryRoute(Request request,Employee employee,String reason,Authentication caller) {
        requireStatus(request,"PENDING"); requireReason(reason); requireHr(employee,caller);
        if(activeStep(request)!=1) throw new LeaveConflictException("ROUTE_ALREADY_DECIDED");
        if(blocked(request,employee)==null) throw new LeaveConflictException("ROUTE_NOT_BLOCKED");
        UUID manager=jdbc.queryForObject("SELECT manager_employee_public_id FROM leave_revision WHERE request_id=? AND revision=?",UUID.class,request.id(),request.revision());
        Route route=routing.resolveWithManager(employee.getPublicId(),revisionUnit(request),manager,clock.instant());
        jdbc.update("UPDATE leave_review_assignment SET active=FALSE WHERE request_id=? AND revision=? AND active=TRUE",request.id(),request.revision());
        insertAssignment(request.id(),request.revision(),1,route.managerAccountPublicId(),false);
        insertAssignment(request.id(),request.revision(),2,route.hrAccountPublicId(),false);
        recordRoute(request.id(),request.revision(),null,route);
        recovery(request,"RETRY_ROUTE",reason);
        jdbc.update("UPDATE leave_request SET version=version+1 WHERE id=?",request.id());
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public CancellationView createCancellation(UUID requestId,UUID id,Action payload,Authentication caller) {
        Employee employee=lockEmployee(payload.employeePublicId()); requireOwner(employee,caller); requireReason(payload.reason());
        Request request=ownedRequest(requestId,employee,true); checkVersion(request,payload.expectedVersion()); requireStatus(request,"APPROVED");
        if(!jdbc.query("SELECT id FROM leave_cancellation WHERE request_id=? AND status='PENDING'",(rs,n)->rs.getLong(1),request.id()).isEmpty()) throw new LeaveConflictException("CANCELLATION_PENDING");
        String unit=currentUnit(employee);
        Route route=routing.resolve(employee.getId(),employee.getPublicId(),unit,clock.instant());
        Long config=route.configurationPublicId()==null?null:jdbc.queryForObject("SELECT id FROM leave_rule_set WHERE public_id=?",Long.class,route.configurationPublicId());
        jdbc.update("INSERT INTO leave_cancellation(public_id,request_id,status,reason,org_unit,rule_set_id,created_at) VALUES (?,?,'PENDING',?,?,?,?)",
            id,request.id(),payload.reason(),unit,config,Timestamp.from(clock.instant()));
        Cancellation cancellation=cancellation(id,false);
        insertCancellationAssignment(cancellation,route.cancellationAccountPublicId());
        recordRoute(request.id(),request.revision(),id,route);
        jdbc.update("UPDATE leave_request SET version=version+1 WHERE id=?",request.id());
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
            jdbc.update("UPDATE leave_cancellation_assignment SET active=FALSE WHERE cancellation_id=? AND active=TRUE",cancel.id());
            insertCancellationAssignment(cancel,target);
            recovery(request,"CANCEL_"+action,payload.reason());
            jdbc.update("UPDATE leave_cancellation SET version=version+1 WHERE id=?",cancel.id());
        } else if("APPROVE".equals(action) || "REJECT".equals(action)) {
            Reviewer reviewer=cancellationReviewer(cancel);
            UserAccount actor=access.requireActive(caller,clock.instant());
            requireReviewer(reviewer,actor,employee,currentUnit(employee),2);
            if("REJECT".equals(action)) requireReason(payload.reason());
            if("APPROVE".equals(action)) {
                entitlement.reverse(employee.getId(),request.from().getYear(),request.publicId(),request.revision());
                jdbc.update("UPDATE leave_occupancy SET active=FALSE WHERE request_id=? AND revision=? AND active=TRUE",request.id(),request.revision());
                transition(request,"CANCELLED");
            }
            requireReviewer(reviewer,access.requireActive(caller,clock.instant()),employee,currentUnit(employee),2);
            jdbc.update("INSERT INTO leave_cancellation_decision(cancellation_id,reviewer_account_public_id,outcome,reason,created_at) VALUES (?,?,?,?,?)",
                cancel.id(),actor.getPublicId(),action,payload.reason(),Timestamp.from(clock.instant()));
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
        List<Request> rows=jdbc.query("SELECT r.id,r.public_id,r.employee_id,r.status,r.revision,r.version,r.from_date,r.to_date,r.portion,r.reason FROM leave_request r WHERE r.employee_id=? AND r.id>? AND (? OR (r.status='PENDING' AND EXISTS (SELECT a.id FROM leave_review_assignment a WHERE a.request_id=r.id AND a.revision=r.revision AND a.step=1 AND a.active=TRUE AND a.reviewer_account_public_id=? AND a.reviewer_employee_public_id=?) AND NOT EXISTS (SELECT d.id FROM leave_decision d WHERE d.request_id=r.id AND d.revision=r.revision AND d.step=1 AND d.outcome='APPROVE'))) ORDER BY r.id LIMIT ?",
            this::mapRequest,employee.getId(),cursor,scope.complete(),scope.reviewer(),scope.employee(),limit+1);
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

    private record RouteSnapshot(int step,UUID reviewerAccountPublicId,UUID reviewerEmployeePublicId,boolean fallback,boolean active) { }
    private record HistorySnapshot(RequestView request,List<RouteSnapshot> route,List<PortionView> portions,CancellationView cancellation) { }
    @Transactional(propagation=Propagation.MANDATORY)
    public void recordHistory(UUID requestId,UUID cancellationId,String action) {
        Request request=request(requestId,false);
        Employee employee=employees.findInternalById(request.employeeId()).orElseThrow(ResourceNotFoundException::new);
        List<RouteSnapshot> route=jdbc.query("SELECT step,reviewer_account_public_id,reviewer_employee_public_id,fallback,active FROM leave_review_assignment WHERE request_id=? AND revision=? AND active=TRUE ORDER BY id",
            (rs,n)->new RouteSnapshot(rs.getInt(1),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getBoolean(4),rs.getBoolean(5)),request.id(),request.revision());
        List<PortionView> captured=jdbc.query("SELECT work_date,half,start_at,end_at FROM leave_portion WHERE request_id=? AND revision=? ORDER BY work_date,half",
            (rs,n)->new PortionView(rs.getObject(1,LocalDate.class),rs.getInt(2),rs.getTimestamp(3).toInstant(),rs.getTimestamp(4).toInstant()),request.id(),request.revision());
        CancellationView cancellation=cancellationId==null?null:cancellationView(cancellation(cancellationId,false),request,employee);
        String payload=mapper.writeValueAsString(new HistorySnapshot(view(request,employee),route,captured,cancellation));
        jdbc.update("INSERT INTO leave_history(public_id,request_id,cancellation_public_id,revision,action,payload,created_at) VALUES (?,?,?,?,?,?,?)",
            UUID.randomUUID(),request.id(),cancellationId,request.revision(),action,payload,Timestamp.from(clock.instant()));
    }

    @Transactional
    public TimelinePage timeline(UUID requestId,UUID cancellationId,UUID after,int limit,Authentication caller) {
        requireLimit(limit); get(requestId,caller); Request request=request(requestId,false);
        if(cancellationId!=null) getCancellation(requestId,cancellationId,caller);
        long cursor=0;
        if(after!=null) cursor=jdbc.query("SELECT id FROM leave_history WHERE public_id=? AND request_id=? AND (CAST(? AS UUID) IS NULL OR cancellation_public_id=?)",
            (rs,n)->rs.getLong(1),after,request.id(),cancellationId,cancellationId).stream().findFirst().orElseThrow(ResourceNotFoundException::new);
        List<TimelineEntry> rows=jdbc.query("SELECT public_id,action,revision,cancellation_public_id,payload,created_at FROM leave_history WHERE request_id=? AND id>? AND (CAST(? AS UUID) IS NULL OR cancellation_public_id=?) ORDER BY id LIMIT ?",
            (rs,n)->new TimelineEntry(rs.getObject(1,UUID.class),rs.getString(2),rs.getInt(3),rs.getObject(4,UUID.class),mapper.readTree(rs.getString(5)),rs.getTimestamp(6).toInstant()),request.id(),cursor,cancellationId,cancellationId,limit+1);
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
        if(after!=null) cursor=jdbc.query("SELECT p.id FROM leave_portion p JOIN leave_request r ON r.id=p.request_id WHERE p.public_id=? AND r.employee_id=? AND p.work_date>=? AND p.work_date<? AND r.status IN ('APPROVED','CANCELLED')",
            (rs,n)->rs.getLong(1),after,employee.getId(),from,to).stream().findFirst().orElseThrow(ResourceNotFoundException::new);
        List<Summary> rows=jdbc.query("SELECT p.public_id,r.public_id,p.work_date,p.half,p.start_at,p.end_at,r.status FROM leave_portion p JOIN leave_request r ON r.id=p.request_id AND r.revision=p.revision WHERE r.employee_id=? AND p.work_date>=? AND p.work_date<? AND r.status IN ('APPROVED','CANCELLED') AND p.id>? ORDER BY p.id LIMIT ?",
            (rs,n)->new Summary(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,LocalDate.class),rs.getInt(4),rs.getTimestamp(5).toInstant(),rs.getTimestamp(6).toInstant(),rs.getString(7)),employee.getId(),from,to,cursor,limit+1);
        boolean more=rows.size()>limit; List<Summary> result=more?rows.subList(0,limit):rows;
        listScope(employee,caller); return new SummaryPage(result,more?result.getLast().publicId():null);
    }

    @Transactional
    public List<History> history(UUID id,Authentication caller) {
        get(id,caller); Request request=request(id,false);
        List<History> result=jdbc.query("SELECT revision,step,reviewer_account_public_id,outcome,reason,created_at FROM leave_decision WHERE request_id=? ORDER BY id",
            (rs,n)->new History(rs.getInt(1),rs.getInt(2),rs.getObject(3,UUID.class),rs.getString(4),rs.getString(5),rs.getTimestamp(6).toInstant()),request.id());
        get(id,caller); return result;
    }

    @Transactional
    public List<PortionView> portions(UUID id,Authentication caller) {
        get(id,caller); Request request=request(id,false);
        List<PortionView> result=jdbc.query("SELECT work_date,half,start_at,end_at FROM leave_portion WHERE request_id=? AND revision=? ORDER BY work_date,half",
            (rs,n)->new PortionView(rs.getObject(1,LocalDate.class),rs.getInt(2),rs.getTimestamp(3).toInstant(),rs.getTimestamp(4).toInstant()),request.id(),request.revision());
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
        List<History> result=jdbc.query("SELECT reviewer_account_public_id,outcome,reason,created_at FROM leave_cancellation_decision WHERE cancellation_id=? ORDER BY id",
            (rs,n)->new History(1,1,rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getTimestamp(4).toInstant()),cancel.id());
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
            requireReviewer(selected,access.requireActive(caller,clock.instant()),employee,currentUnit(employee),step);
            return;
        }
        UserAccount account=access.requireActive(caller,clock.instant());
        for(int candidate=1;candidate<=2;candidate++) {
            Reviewer assigned=reviewer(request,candidate);
            if(Objects.equals(assigned.account(),account.getPublicId())) {
                requireReviewer(assigned,account,employee,currentUnit(employee),candidate); return;
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
            requireReviewer(cancellationReviewer(cancellation),account,employee,currentUnit(employee),2); return;
        }
        for(int step=1;step<=2;step++) {
            Reviewer selected=reviewer(request,step);
            if(Objects.equals(selected.account(),account.getPublicId())) {
                requireReviewer(selected,account,employee,currentUnit(employee),step); return;
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
    private void requireReviewer(Reviewer reviewer,UserAccount actor,Employee employee,String unit,int step) {
        if(reviewer.account()==null || !reviewer.account().equals(actor.getPublicId())
            || !Objects.equals(reviewer.employee(),access.ownEmployeePublicId(actor).orElse(null))
            || !routing.eligible(reviewer.account(),employee.getPublicId(),currentUnit(employee),step,reviewer.fallback(),clock.instant())) throw new AccessDeniedException("Current assigned eligible reviewer required");
    }
    private String currentUnit(Employee employee) { return employees.currentOrgUnit(employee.getId(),clock.instant()).orElse(null); }
    private String revisionUnit(Request request) { return jdbc.queryForObject("SELECT org_unit FROM leave_revision WHERE request_id=? AND revision=?",String.class,request.id(),request.revision()); }
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
        return jdbc.query("SELECT "+COLUMNS+" FROM leave_request WHERE public_id=?"+(lock?" FOR UPDATE":""),this::mapRequest,id)
            .stream().findFirst().orElseThrow(ResourceNotFoundException::new);
    }
    private Request mapRequest(ResultSet rs,int n) throws SQLException {
        return new Request(rs.getLong("id"),rs.getObject("public_id",UUID.class),rs.getLong("employee_id"),rs.getString("status"),rs.getInt("revision"),rs.getLong("version"),rs.getObject("from_date",LocalDate.class),rs.getObject("to_date",LocalDate.class),Portion.valueOf(rs.getString("portion")),rs.getString("reason"));
    }
    private RequestView view(Request request,Employee employee) {
        return new RequestView(request.publicId(),employee.getPublicId(),request.status(),request.revision(),request.version(),request.from(),request.to(),request.portion(),request.reason(),blocked(request,employee));
    }
    private String blocked(Request request,Employee employee) {
        if(!"PENDING".equals(request.status())) return null;
        int step=activeStep(request); Reviewer current=reviewer(request,step);
        if(current.account()==null) {
            List<String> reasons=jdbc.query("SELECT blocked_reason FROM leave_route_evaluation WHERE request_id=? AND revision=? AND cancellation_public_id IS NULL ORDER BY id DESC LIMIT 1",(rs,n)->rs.getString(1),request.id(),request.revision());
            return reasons.isEmpty() || reasons.getFirst()==null?"NO_DESIGNATED_REVIEWER":reasons.getFirst();
        }
        if(!Objects.equals(current.employee(),boundEmployee(current.account()))) return "REVIEWER_INELIGIBLE";
        if(!routing.eligible(current.account(),employee.getPublicId(),currentUnit(employee),step,current.fallback(),clock.instant())) return "REVIEWER_INELIGIBLE";
        if(step==1 && routing.samePerson(current.account(),reviewer(request,2).account())) return "DISTINCT_REVIEWERS_REQUIRED";
        return null;
    }
    private int activeStep(Request request) {
        Integer first=jdbc.queryForObject("SELECT count(id) FROM leave_decision WHERE request_id=? AND revision=? AND step=1 AND outcome='APPROVE'",Integer.class,request.id(),request.revision()); return first!=null && first>0?2:1;
    }
    private Reviewer reviewer(Request request,int step) {
        return jdbc.query("SELECT reviewer_account_public_id,reviewer_employee_public_id,fallback FROM leave_review_assignment WHERE request_id=? AND revision=? AND step=? AND active=TRUE",
            (rs,n)->new Reviewer(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getBoolean(3)),request.id(),request.revision(),step).stream().findFirst().orElse(new Reviewer(null,null,false));
    }
    private void insertAssignment(Long request,int revision,int step,UUID account,boolean fallback) {
        UUID employee=boundEmployee(account);
        jdbc.update("INSERT INTO leave_review_assignment(request_id,revision,step,reviewer_account_public_id,reviewer_employee_public_id,fallback,created_at) VALUES (?,?,?,?,?,?,?)",request,revision,step,account,employee,fallback,Timestamp.from(clock.instant()));
    }
    private void recovery(Request request,String action,String reason) {
        jdbc.update("INSERT INTO leave_recovery(request_id,revision,action,reason,created_at) VALUES (?,?,?,?,?)",request.id(),request.revision(),action,reason,Timestamp.from(clock.instant()));
    }
    private void transition(Request request,String status) { jdbc.update("UPDATE leave_request SET status=?,version=version+1 WHERE id=?",status,request.id()); }
    private void checkVersion(Request request,long version) { if(request.version()!=version) throw new LeaveConflictException("VERSION_CHANGED"); }
    private void requireStatus(Request request,String... allowed) { if(!List.of(allowed).contains(request.status())) throw new LeaveConflictException("REQUEST_STALE"); }
    private void requireReason(String reason) { if(!StringUtils.hasText(reason) || reason.length()>2000) throw new IllegalArgumentException("Reason is required and limited to 2000 characters"); }
    private void validateDraft(Draft draft) {
        requireReason(draft.reason());
        if(draft.fromDate()==null || draft.toDate()==null || draft.portion()==null || draft.fromDate().isAfter(draft.toDate())
            || draft.fromDate().getYear()!=draft.toDate().getYear() || draft.portion()!=Portion.FULL && !draft.fromDate().equals(draft.toDate())) throw new IllegalArgumentException("Invalid annual leave dates");
    }
    private Cancellation cancellation(UUID id,boolean lock) {
        return jdbc.query("SELECT id,public_id,request_id,status,version,reason,org_unit FROM leave_cancellation WHERE public_id=?"+(lock?" FOR UPDATE":""),
            (rs,n)->new Cancellation(rs.getLong(1),rs.getObject(2,UUID.class),rs.getLong(3),rs.getString(4),rs.getLong(5),rs.getString(6),rs.getString(7)),id).stream().findFirst().orElseThrow(ResourceNotFoundException::new);
    }
    private Reviewer cancellationReviewer(Cancellation cancel) {
        return jdbc.query("SELECT reviewer_account_public_id,reviewer_employee_public_id FROM leave_cancellation_assignment WHERE cancellation_id=? AND active=TRUE",
            (rs,n)->new Reviewer(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),false),cancel.id()).stream().findFirst().orElse(new Reviewer(null,null,false));
    }
    private void insertCancellationAssignment(Cancellation cancel,UUID account) {
        jdbc.update("INSERT INTO leave_cancellation_assignment(cancellation_id,reviewer_account_public_id,reviewer_employee_public_id,created_at) VALUES (?,?,?,?)",cancel.id(),account,boundEmployee(account),Timestamp.from(clock.instant()));
    }
    private CancellationView cancellationView(Cancellation cancel,Request request,Employee employee) {
        Reviewer reviewer=cancellationReviewer(cancel);
        String blocked="PENDING".equals(cancel.status()) && (reviewer.account()==null || !Objects.equals(reviewer.employee(),boundEmployee(reviewer.account())) || !routing.eligible(reviewer.account(),employee.getPublicId(),currentUnit(employee),2,false,clock.instant()))?"REVIEWER_INELIGIBLE":null;
        return new CancellationView(cancel.publicId(),request.publicId(),cancel.status(),cancel.version(),cancel.reason(),blocked);
    }
    private void transitionCancellation(Cancellation cancel,String status) { jdbc.update("UPDATE leave_cancellation SET status=?,version=version+1 WHERE id=?",status,cancel.id()); }
    private void recordRoute(Long request,int revision,UUID cancellation,Route route) {
        jdbc.update("INSERT INTO leave_route_evaluation(request_id,revision,cancellation_public_id,configuration_public_id,manager_account_public_id,hr_account_public_id,cancellation_account_public_id,blocked_reason,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
            request,revision,cancellation,route.configurationPublicId(),route.managerAccountPublicId(),route.hrAccountPublicId(),route.cancellationAccountPublicId(),route.blockedReason(),Timestamp.from(clock.instant()));
    }
    private boolean lockRead(Employee employee) { boolean locked=entitlement.lockExisting(employee.getId()); entities.clear(); return locked; }
    private UUID boundEmployee(UUID account) { return account==null?null:access.findAccountByPublicId(account).flatMap(access::ownEmployeePublicId).orElse(null); }
}
