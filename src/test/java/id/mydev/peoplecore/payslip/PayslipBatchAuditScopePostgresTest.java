package id.mydev.peoplecore.payslip;

import id.mydev.peoplecore.common.audit.infrastructure.persistence.JdbcAuditReportQuery;
import id.mydev.peoplecore.common.reporting.ReportFilter;
import id.mydev.peoplecore.common.reporting.ReportSnapshotClock;
import id.mydev.peoplecore.identity.application.service.AuthorizationFenceService;
import id.mydev.peoplecore.identity.application.service.AuthorizationFenceService.AuthorizationWindow;
import id.mydev.peoplecore.identity.application.service.ReportAuthorizationService;
import id.mydev.peoplecore.reportexport.application.ReportCsvWriter;
import id.mydev.peoplecore.reporting.application.ReportRequest;
import id.mydev.peoplecore.reporting.application.ReportType;
import id.mydev.peoplecore.reporting.application.ScopedReportService;
import id.mydev.peoplecore.support.JwtAuthFixture;
import id.mydev.peoplecore.support.PostgresFixture;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("postgres")
class PayslipBatchAuditScopePostgresTest {
    private static final Instant NOW=Instant.parse("2026-11-13T00:00:00Z");
    private static final Instant TRANSFER_AT=NOW.plusSeconds(60);
    private PostgresFixture fixture;
    private DataSource source;
    private JdbcAuditReportQuery audits;
    private long creator,hr,finance,operations,scopedOperations,employeeA,employeeB;
    private UUID employeePublicA,batchPublic,eventId;

    @BeforeEach
    void prepare() {
        fixture=new PostgresFixture();
        fixture.flyway().migrate();
        fixture.grantRuntime();
        source=fixture.runtime().getDataSource();
        audits=new JdbcAuditReportQuery(source);
        creator=account("creator","FINANCE_PAYROLL",null);
        hr=account("hr","HR_ADMIN","ENG");
        finance=account("finance","FINANCE_PAYROLL","ENG");
        operations=account("operations","SYSTEM_ADMIN",null);
        scopedOperations=account("scoped-operations","SYSTEM_ADMIN","ENG");
        employeeA=employee("A");
        employeeB=employee("B");
        employeePublicA=fixture.jdbc.queryForObject("SELECT public_id FROM employees WHERE id=?",UUID.class,employeeA);
        assignment(employeeA,"ENG",NOW.minusSeconds(86400),null);
        assignment(employeeB,"FIN",NOW.minusSeconds(86400),TRANSFER_AT);
        assignment(employeeB,"OTHER",TRANSFER_AT,null);
        batchPublic=UUID.randomUUID();
        long batch=batch(batchPublic);
        long revision=revision(batch,1);
        fixture.jdbc.update("UPDATE payslip_batch SET current_revision_id=? WHERE id=?",revision,batch);
        item(revision,employeeA);
        item(revision,employeeB);
        eventId=audit(batchPublic);
    }

    @AfterEach
    void close() { if(fixture!=null) fixture.close(); }

    @Test
    void oneAuthorizedItemCannotRevealAMixedBatchToScopedHrOrFinance() {
        assertTrue(audits.query(filter(hr,null,null,NOW)).rows().isEmpty());
        assertTrue(audits.query(filter(finance,null,null,NOW)).rows().isEmpty());
        assertTrue(audits.query(filter(scopedOperations,null,null,NOW)).rows().isEmpty());
        assertEquals(1,audits.query(filter(operations,null,null,NOW)).rows().size());
        assertEquals(1,audits.query(filter(creator,null,null,NOW)).rows().size());
    }

    @Test
    void employeeUnitAndResourceFiltersDoNotShrinkTheAuthorizationSubjectSet() {
        for(ReportFilter filter:List.of(filter(hr,employeePublicA,null,NOW),filter(hr,null,"ENG",NOW),
            filter(hr,employeePublicA,"ENG",NOW),filter(finance,employeePublicA,"ENG",NOW))) {
            assertTrue(audits.query(filter).rows().isEmpty());
        }
        assertEquals(1,audits.query(filter(operations,employeePublicA,"ENG",NOW)).rows().size());
    }

    @Test
    void eachCurrentSubjectCanBeAuthorizedByHrOrFinanceAndScopeRemovalHidesTheBatch() {
        long financeGrant=grant(hr,"FINANCE_PAYROLL","FIN");
        assertEquals(1,audits.query(filter(hr,null,null,NOW)).rows().size());
        grant(finance,"FINANCE_PAYROLL","FIN");
        assertEquals(1,audits.query(filter(finance,employeePublicA,"ENG",NOW)).rows().size());
        fixture.jdbc.update("UPDATE role_assignments SET effective_to=? WHERE id=?",Timestamp.from(NOW),financeGrant);
        assertTrue(audits.query(filter(hr,employeePublicA,"ENG",NOW)).rows().isEmpty());
        assertEquals(1,audits.query(filter(operations,null,null,NOW)).rows().size());
    }

    @Test
    void aScheduledSubjectTransferOutsideScopeRemovesTheWholeBatchEventAtItsBoundary() {
        grant(hr,"HR_ADMIN","FIN");
        assertEquals(1,audits.query(filter(hr,null,null,NOW)).rows().size());
        assertTrue(audits.query(filter(hr,employeePublicA,"ENG",TRANSFER_AT)).rows().isEmpty());
        assertEquals(1,audits.query(filter(operations,employeePublicA,"ENG",TRANSFER_AT)).rows().size());
    }

    @Test
    void aMissingCurrentAssignmentRequiresGlobalSubjectAuthority() {
        long batch=batch(UUID.randomUUID());
        long revision=revision(batch,1);
        fixture.jdbc.update("UPDATE payslip_batch SET current_revision_id=? WHERE id=?",revision,batch);
        long unassigned=employee("UNASSIGNED");
        item(revision,employeeA);
        item(revision,unassigned);
        UUID missingAssignmentBatch=fixture.jdbc.queryForObject("SELECT public_id FROM payslip_batch WHERE id=?",UUID.class,batch);
        UUID missingAssignmentEvent=audit(missingAssignmentBatch);
        var scoped=filterFor(hr,employeePublicA,"ENG",NOW,missingAssignmentBatch,100);
        assertTrue(audits.query(scoped).rows().isEmpty());
        var global=audits.query(filterFor(creator,null,null,NOW,missingAssignmentBatch,100));
        assertEquals(missingAssignmentEvent.toString(),global.rows().getFirst().getFirst());
        UUID empty=UUID.randomUUID();
        batch(empty);
        audit(empty);
        assertTrue(audits.query(filterFor(creator,null,null,NOW,empty,100)).rows().isEmpty());
    }

    @Test
    void theSharedExportSnapshotAndCsvPathApplyTheSameWholeBatchGuard() {
        var caller=JwtAuthFixture.token("https://issuer.example","hr");
        var request=new ReportRequest(ReportType.AUDIT,1,LocalDate.of(2026,11,1),LocalDate.of(2026,12,1),
            null,null,employeePublicA,"ENG",null,"PAYSLIP_BATCH",batchPublic,null,null,null,null);
        var snapshotClock=mock(ReportSnapshotClock.class);
        when(snapshotClock.snapshotTime(anyLong())).thenReturn(NOW);
        var access=mock(ReportAuthorizationService.class);
        when(access.authorize(eq(caller),eq("AUDIT"),eq(employeePublicA),eq("ENG"),eq(NOW),anyLong())).thenReturn(hr);
        var fences=mock(AuthorizationFenceService.class);
        when(fences.currentBoundary(NOW)).thenReturn(new AuthorizationWindow(1,TRANSFER_AT));
        var reports=new ScopedReportService(List.of(audits),access,fences,snapshotClock,JsonMapper.builder().build(),
            Clock.fixed(NOW,ZoneOffset.UTC),new DataSourceTransactionManager(source));
        var csv=new ReportCsvWriter();
        var denied=reports.snapshot(caller,request,ScopedReportService.EXPORT_MAX_ROWS);
        assertTrue(denied.rows().isEmpty());
        assertFalse(new String(csv.render(denied.columns(),denied.rows()),StandardCharsets.UTF_8).contains(eventId.toString()));
        grant(hr,"FINANCE_PAYROLL","FIN");
        var allowed=reports.snapshot(caller,request,ScopedReportService.EXPORT_MAX_ROWS);
        assertEquals(1,allowed.rows().size());
        String rendered=new String(csv.render(allowed.columns(),allowed.rows()),StandardCharsets.UTF_8);
        assertTrue(rendered.contains(eventId.toString()));
        assertFalse(rendered.contains("sensitive"));
    }

    private ReportFilter filter(long account,UUID employee,String unit,Instant at) {
        return filterFor(account,employee,unit,at,batchPublic,100);
    }

    private ReportFilter filterFor(long account,UUID employee,String unit,Instant at,UUID resource,int limit) {
        return new ReportFilter("AUDIT",LocalDate.of(2026,11,1),LocalDate.of(2026,12,1),null,employee,unit,null,
            "PAYSLIP_BATCH",resource,null,null,null,null,account,at,limit,System.nanoTime()+Duration.ofSeconds(30).toNanos());
    }

    private long account(String subject,String role,String scope) {
        long id=fixture.jdbc.queryForObject("INSERT INTO user_accounts(public_id,oidc_issuer,oidc_subject,created_at) "
            +"VALUES (?,'https://issuer.example',?,?) RETURNING id",Long.class,UUID.randomUUID(),subject,Timestamp.from(NOW.minusSeconds(86400)));
        grant(id,role,scope);
        return id;
    }

    private long grant(long account,String role,String scope) {
        return fixture.jdbc.queryForObject("INSERT INTO role_assignments(account_id,role,scope,effective_from,created_at) "
            +"VALUES (?,?,?,?,?) RETURNING id",Long.class,account,role,scope,Timestamp.from(NOW.minusSeconds(86400)),Timestamp.from(NOW.minusSeconds(86400)));
    }

    private long employee(String number) {
        return fixture.jdbc.queryForObject("INSERT INTO employees(public_id,employee_number,created_at) VALUES (?,?,?) RETURNING id",
            Long.class,UUID.randomUUID(),number,Timestamp.from(NOW.minusSeconds(86400)));
    }

    private void assignment(long employee,String unit,Instant from,Instant to) {
        fixture.jdbc.update("INSERT INTO employee_assignments(public_id,employee_id,org_unit,job_level,valid_from,valid_to,created_at) "
            +"VALUES (?,?,?,'L1',?,?,?)",UUID.randomUUID(),employee,unit,Timestamp.from(from),to==null?null:Timestamp.from(to),Timestamp.from(NOW.minusSeconds(86400)));
    }

    private long batch(UUID publicId) {
        return fixture.jdbc.queryForObject("INSERT INTO payslip_batch(public_id,created_by,created_at,updated_at) VALUES (?,?,?,?) RETURNING id",
            Long.class,publicId,creator,Timestamp.from(NOW),Timestamp.from(NOW));
    }

    private long revision(long batch,long number) {
        return fixture.jdbc.queryForObject("INSERT INTO payslip_batch_revision(public_id,batch_id,revision_number,created_at) VALUES (?,?,?,?) RETURNING id",
            Long.class,UUID.randomUUID(),batch,number,Timestamp.from(NOW));
    }

    private void item(long revision,long employee) {
        var document=PayslipPersistencePostgresTest.verifiedDocument(fixture.jdbc,employee);
        fixture.jdbc.update("INSERT INTO payslip_batch_item(public_id,revision_id,employee_id,period_start,document_id,document_version_id) "
            +"VALUES (?,?,?,'2026-11-01',?,?)",UUID.randomUUID(),revision,employee,document.id(),document.version());
    }

    private UUID audit(UUID batch) {
        UUID id=UUID.randomUUID();
        fixture.jdbc.update("INSERT INTO audit_events(event_id,actor_id,action,aggregate_type,aggregate_public_id,reason,details,created_at) "
            +"VALUES (?,'creator','PAYSLIP_BATCH_CREATE','PAYSLIP_BATCH',?,'sensitive','sensitive',?)",id,batch,Timestamp.from(NOW));
        return id;
    }
}
