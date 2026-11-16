package id.mydev.peoplecore.reporting.application;

import id.mydev.peoplecore.common.reporting.ReportLimitException;
import id.mydev.peoplecore.common.reporting.ReportSnapshotClock;
import id.mydev.peoplecore.common.reporting.ReportTable;
import id.mydev.peoplecore.common.reporting.ScopedReportQuery;
import id.mydev.peoplecore.identity.application.service.AuthorizationFenceService;
import id.mydev.peoplecore.identity.application.service.AuthorizationFenceService.AuthorizationWindow;
import id.mydev.peoplecore.identity.application.service.ReportAuthorizationService;
import id.mydev.peoplecore.support.JwtAuthFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScopedReportServiceTest {
    private static final Instant NOW=Instant.parse("2026-11-13T00:00:00Z");
    private final Authentication caller=JwtAuthFixture.token("https://issuer.example","employee");
    private final ReportAuthorizationService access=mock(ReportAuthorizationService.class);
    private final AuthorizationFenceService fences=mock(AuthorizationFenceService.class);
    private final ReportSnapshotClock snapshots=mock(ReportSnapshotClock.class);
    private final ScopedReportQuery query=mock(ScopedReportQuery.class);
    private final ReportRequest request=ReportRequest.of(ReportType.ATTENDANCE,LocalDate.of(2026,11,1),LocalDate.of(2026,12,1));

    @Test
    void snapshotPreservesScopeFenceAndRejectsRowOverflowInsteadOfTruncating() {
        stubSnapshot();
        when(query.query(any())).thenReturn(new ReportTable(List.of("employeeId","employeeNumber","employeeName","workday"),List.of(List.of("b","B","","2026-11-02"),List.of("a","A","","2026-11-01"))));
        var snapshot=service().snapshot(caller,request,2);
        assertEquals(List.of("a","b"),snapshot.rows().stream().map(row->row.getFirst()).toList());
        assertEquals(7,snapshot.authorizationGeneration());
        assertEquals(NOW.plusSeconds(60),snapshot.nextBoundary());
        assertEquals(NOW,snapshot.dataAsOf());
        assertThrows(ReportLimitException.class,()->service().snapshot(caller,request,1));
    }

    @Test
    void deniedCallerCannotObtainSnapshot() {
        when(snapshots.snapshotTime(anyLong())).thenReturn(NOW);
        when(access.authorize(eq(caller),eq("ATTENDANCE"),eq(null),eq(null),eq(NOW),anyLong()))
            .thenThrow(new AccessDeniedException("No report authority"));
        assertThrows(AccessDeniedException.class,()->service().snapshot(caller,request,10));
    }

    @Test
    void catalogRequiresBoundedRangesAndSeparateYearAndMonthSelections() {
        assertThrows(IllegalArgumentException.class,()->ScopedReportService.validate(
            ReportRequest.of(ReportType.ATTENDANCE,LocalDate.of(2026,1,1),LocalDate.of(2026,3,1))));
        assertThrows(IllegalArgumentException.class,()->ScopedReportService.validate(ReportRequest.of(ReportType.LEAVE_BALANCES,null,null)));
        assertThrows(IllegalArgumentException.class,()->ScopedReportService.validate(ReportRequest.of(ReportType.PAYSLIP_STATUS,null,null)));
        var yearly=new ReportRequest(ReportType.LEAVE_BALANCES,1,null,null,2026,null,null,null,null,null,null,null,null,null,null);
        ScopedReportService.validate(yearly);
        var monthly=new ReportRequest(ReportType.PAYSLIP_STATUS,1,null,null,null,YearMonth.of(2026,11),null,null,null,null,null,null,null,null,null);
        ScopedReportService.validate(monthly);
        assertThrows(IllegalArgumentException.class,()->service().snapshot(caller,request,50_001));
    }

    @Test
    void filterIdentityChangesWithSelectedReportScope() {
        assertEquals(service().filterHash(request),service().filterHash(request));
        assertNotEquals(service().filterHash(request),service().filterHash(
            ReportRequest.of(ReportType.REIMBURSEMENTS,request.from(),request.to())));
    }

    @Test
    void multiDomainRequestStatusMergesConsistentRowsAndCountsBeforeApplyingTheLimit() {
        when(snapshots.snapshotTime(anyLong())).thenReturn(NOW);
        when(access.authorize(eq(caller),eq("REQUEST_STATUS"),eq(null),eq(null),eq(NOW),anyLong())).thenReturn(1L);
        when(fences.currentBoundary(NOW)).thenReturn(new AuthorizationWindow(7,null));
        ScopedReportQuery profile=mock(ScopedReportQuery.class);
        ScopedReportQuery leave=mock(ScopedReportQuery.class);
        ScopedReportQuery excluded=mock(ScopedReportQuery.class);
        when(profile.supports("REQUEST_STATUS",null)).thenReturn(true);
        when(leave.supports("REQUEST_STATUS",null)).thenReturn(true);
        List<String> columns=List.of("requestId","employeeId","employeeNumber","employeeName","workflow","submittedAt");
        when(profile.query(any())).thenReturn(new ReportTable(columns,List.of(List.of("p","e","E","","PROFILE","2026-11-02"))));
        when(leave.query(any())).thenReturn(new ReportTable(columns,List.of(List.of("l","e","E","","LEAVE","2026-11-01"))));
        ScopedReportService combined=new ScopedReportService(List.of(profile,excluded,leave),access,fences,snapshots,
            JsonMapper.builder().build(),Clock.fixed(NOW,ZoneOffset.UTC),mock(PlatformTransactionManager.class));
        var request=ReportRequest.of(ReportType.REQUEST_STATUS,LocalDate.of(2026,11,1),LocalDate.of(2026,12,1));
        assertEquals(List.of("l","p"),combined.snapshot(caller,request,50_000).rows().stream().map(List::getFirst).toList());
        assertThrows(ReportLimitException.class,()->combined.snapshot(caller,request,1));
        when(leave.query(any())).thenReturn(new ReportTable(List.of("invalid"),List.of()));
        assertThrows(IllegalStateException.class,()->combined.snapshot(caller,request,10));
    }

    @Test
    void unsupportedCatalogAndZeroRowBudgetAreRejectedExplicitly() {
        stubSnapshot();
        when(query.supports("ATTENDANCE",null)).thenReturn(false);
        assertThrows(IllegalArgumentException.class,()->service().snapshot(caller,request,10));
        assertThrows(IllegalArgumentException.class,()->service().snapshot(caller,request,0));
    }

    @Test
    void annualAndMonthlyFiltersMapToTheirCanonicalPeriodWithoutInventingRangeValues() {
        var monthly=new ReportRequest(ReportType.PAYSLIP_STATUS,1,null,null,null,YearMonth.of(2026,11),null,null,null,null,null,null,null,null,null);
        var filter=ReportRequestMapper.filter(monthly,1,NOW,10,1);
        assertEquals(LocalDate.of(2026,11,1),filter.from());
        assertEquals(LocalDate.of(2026,12,1),filter.to());
        var annual=new ReportRequest(ReportType.LEAVE_BALANCES,1,null,null,2026,null,null,null,null,null,null,null,null,null,null);
        assertEquals(2026,ReportRequestMapper.filter(annual,1,NOW,10,1).year());
    }

    @Test
    void anExistingWeakerOrWritableTransactionCannotSilentlyWeakenReportConsistency() {
        try {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
            TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            assertThrows(IllegalStateException.class,()->service().snapshot(caller,request,10));
            TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
            assertThrows(IllegalStateException.class,()->service().snapshot(caller,request,10));
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
            stubSnapshot();
            when(query.query(any())).thenReturn(new ReportTable(List.of("employeeId","number","name","date"),List.of()));
            assertEquals(0,service().snapshot(caller,request,10).rows().size());
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    private void stubSnapshot() {
        when(snapshots.snapshotTime(anyLong())).thenReturn(NOW);
        when(access.authorize(eq(caller),eq("ATTENDANCE"),eq(null),eq(null),eq(NOW),anyLong())).thenReturn(1L);
        when(fences.currentBoundary(NOW)).thenReturn(new AuthorizationWindow(7,NOW.plusSeconds(60)));
        when(query.supports("ATTENDANCE",null)).thenReturn(true);
    }
    private ScopedReportService service() {
        return new ScopedReportService(List.of(query),access,fences,snapshots,JsonMapper.builder().build(),Clock.fixed(NOW,ZoneOffset.UTC),mock(PlatformTransactionManager.class));
    }
}
