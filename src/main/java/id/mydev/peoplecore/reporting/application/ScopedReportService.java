package id.mydev.peoplecore.reporting.application;

import id.mydev.peoplecore.common.reporting.ReportFilter;
import id.mydev.peoplecore.common.reporting.ReportLimitException;
import id.mydev.peoplecore.common.reporting.ReportSnapshotClock;
import id.mydev.peoplecore.common.reporting.ReportTable;
import id.mydev.peoplecore.common.reporting.ScopedReportQuery;
import id.mydev.peoplecore.identity.application.service.AuthorizationFenceService;
import id.mydev.peoplecore.identity.application.service.ReportAuthorizationService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

@Service
public class ScopedReportService {
    public static final int INTERACTIVE_MAX_ROWS=10_000;
    public static final int EXPORT_MAX_ROWS=50_000;
    private static final Set<String> WORKFLOWS=Set.of("PROFILE","ATTENDANCE","LEAVE","LEAVE_CANCELLATION","REIMBURSEMENT");
    private final List<ScopedReportQuery> queries;
    private final ReportAuthorizationService access;
    private final AuthorizationFenceService fences;
    private final ReportSnapshotClock snapshots;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate readSnapshot;

    public ScopedReportService(List<ScopedReportQuery> queries,ReportAuthorizationService access,
                               AuthorizationFenceService fences,ReportSnapshotClock snapshots,ObjectMapper mapper,Clock clock,
                               PlatformTransactionManager transactions) {
        this.queries=List.copyOf(queries); this.access=access;this.fences=fences;this.snapshots=snapshots;this.mapper=mapper;this.clock=clock;
        this.readSnapshot=new TransactionTemplate(transactions);
        this.readSnapshot.setReadOnly(true);
        this.readSnapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.readSnapshot.setTimeout(30);
    }

    public ReportSnapshot snapshot(Authentication caller,ReportRequest request,int maxRows) {
        if(maxRows<1||maxRows>EXPORT_MAX_ROWS) throw new IllegalArgumentException("Invalid report row limit");
        long deadline=System.nanoTime()+Duration.ofSeconds(maxRows<=INTERACTIVE_MAX_ROWS?30:120).toNanos();
        validate(request);
        if(TransactionSynchronizationManager.isActualTransactionActive()
            && (!Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),TransactionDefinition.ISOLATION_REPEATABLE_READ)
                ||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()))
            throw new IllegalStateException("Report snapshots require a read-only Repeatable Read transaction");
        return readSnapshot.execute(status->readSnapshot(caller,request,maxRows,deadline));
    }

    private ReportSnapshot readSnapshot(Authentication caller,ReportRequest request,int maxRows,long deadline) {
        checkDeadline(deadline);
        Instant at=snapshots.snapshotTime(deadline);
        long account=access.authorize(caller,request.reportType().name(),request.employeeId(),request.unit(),at,deadline);
        var window=fences.currentBoundary(at);
        ReportFilter filter=ReportRequestMapper.filter(request,account,at,maxRows,deadline);
        List<String> columns=null;
        List<List<String>> rows=new ArrayList<>();
        for(ScopedReportQuery query:queries) {
            if(!query.supports(filter.reportType(),filter.workflowType())) continue;
            ReportTable table=query.query(filter);
            if(columns==null) columns=table.columns();
            else if(!columns.equals(table.columns())) throw new IllegalStateException("Incompatible report columns");
            rows.addAll(table.rows());
            if(rows.size()>maxRows) throw new ReportLimitException("Report row limit exceeded; narrow the selected filters");
            checkDeadline(deadline);
        }
        if(columns==null) throw new IllegalArgumentException("Unsupported report");
        rows.sort(rowOrder(request.reportType()));
        checkDeadline(deadline);
        return new ReportSnapshot(request.reportType(),request.reportVersion(),filterHash(request),columns,rows,at,
            window.generation(),window.nextBoundary());
    }

    @Transactional(readOnly=true,timeout=2)
    public void authorize(Authentication caller,ReportRequest request) {
        validate(request);
        access.authorize(caller,request.reportType().name(),request.employeeId(),request.unit(),clock.instant(),
            System.nanoTime()+Duration.ofSeconds(2).toNanos());
    }

    public String filterHash(ReportRequest request) {
        try {
            byte[] canonical=mapper.writeValueAsString(request).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static void validate(ReportRequest request) {
        if(request==null||request.reportType()==null||request.reportVersion()!=1) throw new IllegalArgumentException("Unknown report/version");
        if(request.reportType()==ReportType.LEAVE_BALANCES) {
            if(request.year()==null||request.year()<1900||request.year()>9998||request.from()!=null||request.to()!=null||request.month()!=null)
                throw new IllegalArgumentException("Balances require one entitlement year");
        } else if(request.reportType()==ReportType.PAYSLIP_STATUS) {
            if(request.month()==null||request.month().getYear()<1900||request.month().getYear()>9998
                ||request.from()!=null||request.to()!=null||request.year()!=null)
                throw new IllegalArgumentException("Payslip status requires one month");
        } else {
            if(request.from()==null||request.to()==null||!request.from().isBefore(request.to())
                ||request.from().getYear()<1900||request.to().getYear()>9998
                ||ChronoUnit.DAYS.between(request.from(),request.to())>31||request.year()!=null||request.month()!=null)
                throw new IllegalArgumentException("Reports require an explicit range of at most 31 days, end exclusive");
        }
        for(String text:List.of(value(request.unit()),value(request.status()),value(request.workflowType()),
            value(request.actorId()),value(request.action()),value(request.correlationId()),value(request.category()))) {
            if(text.length()>128) throw new IllegalArgumentException("Report filter exceeds maximum length");
        }
        if(request.workflowType()!=null&&request.reportType()!=ReportType.AUDIT
            &&(request.reportType()!=ReportType.REQUEST_STATUS||!WORKFLOWS.contains(request.workflowType())))
            throw new IllegalArgumentException("Unsupported workflow filter");
        if(request.category()!=null&&request.reportType()!=ReportType.REIMBURSEMENTS)
            throw new IllegalArgumentException("Category filter applies only to reimbursement reports");
        if(request.reportType()!=ReportType.AUDIT&&(request.resourceId()!=null||request.actorId()!=null||request.action()!=null||request.correlationId()!=null))
            throw new IllegalArgumentException("Audit filters apply only to audit search");
        if(request.actorId()!=null) {
            try { UUID.fromString(request.actorId()); }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Actor filter requires a public UUID"); }
        }
        if(request.status()!=null && !statuses(request.reportType()).contains(request.status()))
            throw new IllegalArgumentException("Unknown report status filter");
        if(request.unit()!=null&&!StringUtils.hasText(request.unit())) throw new IllegalArgumentException("Unit must not be blank");
        if(request.status()!=null&&(request.reportType()==ReportType.LEAVE_BALANCES||request.reportType()==ReportType.AUDIT))
            throw new IllegalArgumentException("Status is not a filter for this report");
    }

    private static Comparator<List<String>> rowOrder(ReportType type) {
        int dateColumn=switch(type) {
            case ATTENDANCE,PAYSLIP_STATUS -> 3;
            case REQUEST_STATUS,REIMBURSEMENTS,AUDIT -> 5;
            case LEAVE_BALANCES -> 0;
        };
        return Comparator.<List<String>,String>comparing(row->row.get(dateColumn))
            .thenComparing(row->String.join("\u0000",row));
    }

    private static Set<String> statuses(ReportType type) {
        return switch(type) {
            case ATTENDANCE -> Set.of("NONE","NOT_RECORDED","IN_PROGRESS","COMPLETE","DRAFT","PENDING","RETURNED","DISCARDED","WITHDRAWN","APPROVED","REJECTED");
            case REIMBURSEMENTS -> Set.of("DRAFT","PENDING","RETURNED","REJECTED","WITHDRAWN","APPROVED","PAID","RECORDED","UNRECORDED");
            case PAYSLIP_STATUS -> Set.of("DRAFT","REVIEWED","PUBLISHED","REPLACED");
            case REQUEST_STATUS -> Set.of("DRAFT","PENDING","RETURNED","REJECTED","WITHDRAWN","DISCARDED","APPROVED","CANCELLED","PAID");
            case LEAVE_BALANCES,AUDIT -> Set.of();
        };
    }

    private static String value(String value) { return Objects.requireNonNullElse(value,""); }
    private static void checkDeadline(long deadline) {
        if(System.nanoTime()>deadline) throw new ReportLimitException("Report execution deadline exceeded");
    }
}
