package id.mydev.peoplecore.reporting.application;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReportValidationTest {
    private static final LocalDate FROM=LocalDate.of(2026,11,1);
    private static final LocalDate TO=LocalDate.of(2026,12,1);

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void invalidOrInapplicableFiltersFailAtTheBoundary(ReportRequest request) {
        assertThrows(IllegalArgumentException.class,()->ScopedReportService.validate(request));
    }

    static Stream<ReportRequest> invalidRequests() {
        return Stream.of(
            new ReportRequest(ReportType.ATTENDANCE,2,FROM,TO,null,null,null,null,null,null,null,null,null,null,null),
            ReportRequest.of(ReportType.ATTENDANCE,null,TO),
            ReportRequest.of(ReportType.ATTENDANCE,FROM,null),
            ReportRequest.of(ReportType.ATTENDANCE,TO,FROM),
            ReportRequest.of(ReportType.ATTENDANCE,FROM,FROM),
            ReportRequest.of(ReportType.ATTENDANCE,FROM,FROM.plusDays(32)),
            new ReportRequest(ReportType.LEAVE_BALANCES,1,null,null,1899,null,null,null,null,null,null,null,null,null,null),
            new ReportRequest(ReportType.LEAVE_BALANCES,1,null,null,9999,null,null,null,null,null,null,null,null,null,null),
            new ReportRequest(ReportType.LEAVE_BALANCES,1,FROM,TO,2026,null,null,null,null,null,null,null,null,null,null),
            new ReportRequest(ReportType.PAYSLIP_STATUS,1,FROM,TO,null,YearMonth.of(2026,11),null,null,null,null,null,null,null,null,null),
            new ReportRequest(ReportType.ATTENDANCE,1,FROM,TO,2026,null,null,null,null,null,null,null,null,null,null),
            new ReportRequest(ReportType.ATTENDANCE,1,FROM,TO,null,null,null," ",null,null,null,null,null,null,null),
            new ReportRequest(ReportType.ATTENDANCE,1,FROM,TO,null,null,null,"x".repeat(129),null,null,null,null,null,null,null),
            new ReportRequest(ReportType.ATTENDANCE,1,FROM,TO,null,null,null,null,"INVALID",null,null,null,null,null,null),
            new ReportRequest(ReportType.ATTENDANCE,1,FROM,TO,null,null,null,null,null,"LEAVE",null,null,null,null,null),
            new ReportRequest(ReportType.REQUEST_STATUS,1,FROM,TO,null,null,null,null,null,"UNKNOWN",null,null,null,null,null),
            new ReportRequest(ReportType.AUDIT,1,FROM,TO,null,null,null,null,null,null,null,"raw-subject",null,null,null),
            new ReportRequest(ReportType.ATTENDANCE,1,FROM,TO,null,null,null,null,null,null,UUID.randomUUID(),null,null,null,null),
            new ReportRequest(ReportType.ATTENDANCE,1,FROM,TO,null,null,null,null,null,null,null,null,null,null,"TRAVEL"),
            new ReportRequest(ReportType.LEAVE_BALANCES,1,null,null,2026,null,null,null,"PENDING",null,null,null,null,null,null));
    }

    @Test
    void supportedFiltersPreserveTheApprovedCatalogAndBoundaryDays() {
        List<ReportRequest> requests=List.of(
            ReportRequest.of(ReportType.ATTENDANCE,FROM,FROM.plusDays(31)),
            new ReportRequest(ReportType.REIMBURSEMENTS,1,FROM,TO,null,null,null,null,"RECORDED",null,null,null,null,null,"TRAVEL"),
            new ReportRequest(ReportType.REQUEST_STATUS,1,FROM,TO,null,null,null,null,"APPROVED","LEAVE",null,null,null,null,null),
            new ReportRequest(ReportType.AUDIT,1,FROM,TO,null,null,null,null,null,"EMPLOYEE",null,UUID.randomUUID().toString(),"READ","correlation",null),
            new ReportRequest(ReportType.PAYSLIP_STATUS,1,null,null,null,YearMonth.of(2026,11),null,null,"REPLACED",null,null,null,null,null,null),
            new ReportRequest(ReportType.LEAVE_BALANCES,1,null,null,2026,null,null,null,null,null,null,null,null,null,null));
        requests.forEach(request->assertDoesNotThrow(()->ScopedReportService.validate(request)));
        assertThrows(IllegalArgumentException.class,()->ScopedReportService.validate(null));
    }
}
