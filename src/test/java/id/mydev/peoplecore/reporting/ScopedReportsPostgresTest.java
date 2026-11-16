package id.mydev.peoplecore.reporting;

import id.mydev.peoplecore.attendance.infrastructure.persistence.JdbcAttendanceReportQuery;
import id.mydev.peoplecore.common.audit.infrastructure.persistence.JdbcAuditReportQuery;
import id.mydev.peoplecore.common.infrastructure.persistence.ReportSql;
import id.mydev.peoplecore.common.infrastructure.persistence.JdbcReportSnapshotClock;
import id.mydev.peoplecore.common.reporting.ReportFilter;
import id.mydev.peoplecore.common.reporting.ReportLimitException;
import id.mydev.peoplecore.identity.infrastructure.persistence.JdbcReportAuthorizationRepository;
import id.mydev.peoplecore.leave.infrastructure.persistence.JdbcLeaveReportQuery;
import id.mydev.peoplecore.organization.infrastructure.persistence.JdbcProfileReportQuery;
import id.mydev.peoplecore.payslip.infrastructure.persistence.JdbcPayslipReportQuery;
import id.mydev.peoplecore.reimbursement.infrastructure.persistence.JdbcReimbursementReportQuery;
import id.mydev.peoplecore.common.reporting.ReportTable;
import id.mydev.peoplecore.reporting.api.ReportDtoMapper;
import id.mydev.peoplecore.reporting.api.ReportParameters;
import id.mydev.peoplecore.reporting.application.ReportSnapshot;
import id.mydev.peoplecore.reporting.application.ReportType;
import id.mydev.peoplecore.support.PostgresFixture;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("postgres")
class ScopedReportsPostgresTest {
    private static final Instant NOW=Instant.parse("2026-11-13T00:00:00Z");
    private static final LocalDate FROM=LocalDate.of(2026,11,1);
    private static final LocalDate TO=LocalDate.of(2026,12,1);
    private PostgresFixture fixture;
    private DataSource source;
    private long employeeA,employeeB,managerEmployee,owner,manager,hr,finance;
    private UUID publicA,publicB;

    @BeforeEach
    void prepare() {
        fixture=new PostgresFixture(); fixture.flyway().migrate(); source=fixture.jdbc.getDataSource();
        employeeA=employee("A"); employeeB=employee("B"); managerEmployee=employee("M");
        publicA=employeePublicId(employeeA); publicB=employeePublicId(employeeB);
        owner=account("owner","EMPLOYEE",null); manager=account("manager","MANAGER","ENG");
        hr=account("hr","HR_ADMIN","ENG"); finance=account("finance","FINANCE_PAYROLL","FIN");
        bind(owner,employeeA); bind(manager,managerEmployee);
        assignment(employeeA,"ENG",managerEmployee); assignment(employeeB,"FIN",null);
        claim(employeeA); claim(employeeB);
    }

    @AfterEach
    void close() { if(fixture!=null) fixture.close(); }

    @Test
    void currentOwnerTeamAndFinanceScopesApplyToRowsAndSelectedTargets() {
        var reports=new JdbcReimbursementReportQuery(source);
        assertEquals(1,reports.query(filter("REIMBURSEMENTS",owner,null)).rows().size());
        assertEquals(1,reports.query(filter("REIMBURSEMENTS",manager,null)).rows().size());
        assertEquals(1,reports.query(filter("REIMBURSEMENTS",finance,null)).rows().size());
        assertEquals(0,reports.query(filter("REIMBURSEMENTS",hr,null)).rows().size());
        var authorization=new JdbcReportAuthorizationRepository(source);
        assertTrue(authorization.containsTarget(manager,"REIMBURSEMENTS",publicA,null,NOW,deadline()));
        assertFalse(authorization.containsTarget(manager,"REIMBURSEMENTS",publicB,null,NOW,deadline()));
        fixture.jdbc.update("UPDATE role_assignments SET effective_to=? WHERE account_id=?",Timestamp.from(NOW),manager);
        assertTrue(reports.query(filter("REIMBURSEMENTS",manager,null)).rows().isEmpty());
    }

    @Test
    void everyCatalogReportAppliesItsDeclaredTargetAudiencesAndCurrentScope() {
        var authorization=new JdbcReportAuthorizationRepository(source);
        seedCatalogRows();
        Map<String,Map<Long,UUID>> allowed=Map.of(
            "ATTENDANCE",Map.of(owner,publicA,manager,publicA,hr,publicA),
            "LEAVE_BALANCES",Map.of(owner,publicA,manager,publicA,hr,publicA),
            "REQUEST_STATUS",Map.of(owner,publicA,manager,publicA,hr,publicA,finance,publicB),
            "REIMBURSEMENTS",Map.of(owner,publicA,manager,publicA,finance,publicB),
            "PAYSLIP_STATUS",Map.of(owner,publicA,hr,publicA,finance,publicB),
            "AUDIT",Map.of(hr,publicA,finance,publicB));

        for (var report : allowed.entrySet()) {
            for (var audience : report.getValue().entrySet()) {
                assertTrue(authorization.containsTarget(audience.getKey(),report.getKey(),audience.getValue(),null,NOW,deadline()),
                    () -> report.getKey()+" should allow account "+audience.getKey()+" to target "+audience.getValue());
                var rows=query(report.getKey(),audience.getKey(),audience.getValue()).rows();
                assertFalse(rows.isEmpty(),report.getKey()+" should return its seeded in-scope row");
                if (!"AUDIT".equals(report.getKey())) {
                    int employeeColumn=List.of("REQUEST_STATUS","REIMBURSEMENTS").contains(report.getKey())?1:0;
                    assertTrue(rows.stream().allMatch(row->audience.getValue().toString().equals(row.get(employeeColumn))),
                        report.getKey()+" returned a row outside the requested employee filter");
                }
                assertEquals(rows.size(),query(report.getKey(),audience.getKey(),audience.getValue()).rows().size(),
                    report.getKey()+" row count must be stable for the same bounded snapshot");
                var snapshot=new ReportSnapshot(ReportType.valueOf(report.getKey()),1,"matrix",query(report.getKey(),audience.getKey(),audience.getValue()).columns(),rows,NOW,1,null);
                var response=new ReportDtoMapper().page(snapshot,new ReportParameters(FROM,TO,2026,null,audience.getValue(),null,null,null,null,null,null,null,null,100,null));
                assertEquals(rows.size(),((ReportDtoMapper.Metadata)response.metadata()).totalRows(),
                    report.getKey()+" API totalRows must match the complete filtered result");
            }
        }

        for (String report : List.of("ATTENDANCE","LEAVE_BALANCES","PAYSLIP_STATUS","AUDIT")) {
            assertFalse(authorization.containsTarget(finance,report,publicA,null,NOW,deadline()),
                () -> report+" must not let Finance cross into ENG");
        }
        for (String report : List.of("ATTENDANCE","LEAVE_BALANCES","REIMBURSEMENTS","PAYSLIP_STATUS","AUDIT")) {
            assertFalse(authorization.containsTarget(manager,report,publicB,null,NOW,deadline()),
                () -> report+" must not let a manager target an unrelated employee");
        }
        assertFalse(authorization.containsTarget(hr,"REIMBURSEMENTS",publicA,null,NOW,deadline()));
        assertFalse(authorization.containsTarget(owner,"AUDIT",publicA,null,NOW,deadline()));
        for (var report : allowed.entrySet()) {
            for (long audience : List.of(owner,manager,hr,finance)) {
                UUID unrelated=publicA.equals(report.getValue().get(audience))?publicB:publicA;
                assertFalse(authorization.containsTarget(audience,report.getKey(),unrelated,null,NOW,deadline()),
                    report.getKey()+" must deny an unrelated employee target for account "+audience);
                assertTrue(query(report.getKey(),audience,unrelated).rows().isEmpty(),
                    report.getKey()+" query must return no rows for an unrelated target");
            }
        }

        fixture.jdbc.update("UPDATE role_assignments SET scope='REMOVED' WHERE account_id IN (?,?)",hr,finance);
        assertFalse(authorization.containsTarget(hr,"ATTENDANCE",publicA,null,NOW,deadline()));
        assertFalse(authorization.containsTarget(hr,"AUDIT",publicA,null,NOW,deadline()));
        assertFalse(authorization.containsTarget(finance,"REIMBURSEMENTS",publicB,null,NOW,deadline()));
        assertFalse(authorization.containsTarget(finance,"PAYSLIP_STATUS",publicB,null,NOW,deadline()));
        assertFalse(authorization.containsTarget(finance,"AUDIT",publicB,null,NOW,deadline()));
        for (String report : List.of("ATTENDANCE","LEAVE_BALANCES","REQUEST_STATUS","PAYSLIP_STATUS","AUDIT")) {
            assertTrue(query(report,hr,publicA).rows().isEmpty(),report+" must have no rows after HR scope removal");
        }
        for (String report : List.of("REQUEST_STATUS","REIMBURSEMENTS","PAYSLIP_STATUS","AUDIT")) {
            assertTrue(query(report,finance,publicB).rows().isEmpty(),report+" must have no rows after Finance scope removal");
        }

        fixture.jdbc.update("UPDATE role_assignments SET effective_to=? WHERE account_id=?",Timestamp.from(NOW),manager);
        assertFalse(authorization.containsTarget(manager,"ATTENDANCE",publicA,null,NOW,deadline()));
        assertTrue(query("ATTENDANCE",manager,publicA).rows().isEmpty());

        fixture.jdbc.update("UPDATE role_assignments SET effective_to=? WHERE account_id=?",Timestamp.from(NOW),owner);
        for (String report : List.of("ATTENDANCE","LEAVE_BALANCES","REQUEST_STATUS","REIMBURSEMENTS","PAYSLIP_STATUS","AUDIT")) {
            assertFalse(authorization.containsTarget(owner,report,publicA,null,NOW,deadline()),
                report+" must deny authorization when role is expired or inactive");
            assertTrue(query(report,owner,publicA).rows().isEmpty(),
                report+" must return zero rows when role is expired or inactive");
        }
    }

    @Test
    void domainRequestQueriesExposeOnlyCatalogFieldsUnderCurrentScope() {
        proposal(employeeA); proposal(employeeB);
        correction(employeeA); correction(employeeB);
        leave(employeeA); leave(employeeB);
        var f=filter("REQUEST_STATUS",hr,null);
        var profile=new JdbcProfileReportQuery(source).query(f);
        assertEquals(1,profile.rows().size());
        assertEquals(1,new JdbcAttendanceReportQuery(source).query(f).rows().size());
        assertEquals(1,new JdbcLeaveReportQuery(source).query(f).rows().size());
        assertFalse(profile.columns().contains("reason"));
        assertFalse(profile.columns().contains("proposedValue"));
        assertEquals("",profile.rows().getFirst().get(3));
        assertTrue(new JdbcPayslipReportQuery(source).query(filter("PAYSLIP_STATUS",manager,null)).rows().isEmpty());
        assertTrue(new JdbcAttendanceReportQuery(source).query(filter("ATTENDANCE",hr,null)).rows().isEmpty());
    }

    @Test
    void balanceAggregationPreservesLedgerAndReservationArithmetic() {
        fixture.jdbc.update("INSERT INTO leave_account(employee_id,created_at) VALUES (?,?)",employeeA,Timestamp.from(NOW));
        Long period=fixture.jdbc.queryForObject("INSERT INTO leave_period(employee_id,period_year) VALUES (?,2026) RETURNING id",Long.class,employeeA);
        fixture.jdbc.update("INSERT INTO leave_ledger(public_id,period_id,entry_type,business_reference,source_reference,half_units,source,reason,created_at) "
            +"VALUES (?,?,'OPENING','OPENING','source',24,'HR','opening',?)",UUID.randomUUID(),period,Timestamp.from(NOW));
        fixture.jdbc.update("INSERT INTO leave_reservation(period_id,request_public_id,request_revision,half_units,created_at) VALUES (?,?,1,4,?)",period,UUID.randomUUID(),Timestamp.from(NOW));
        var result=new JdbcLeaveReportQuery(source).query(filter("LEAVE_BALANCES",owner,null));
        assertEquals(1,result.rows().size());
        assertEquals("24",result.rows().getFirst().get(4));
        assertEquals("4",result.rows().getFirst().get(6));
        assertEquals("20",result.rows().getFirst().get(8));
        assertEquals(1,fixture.jdbc.queryForObject("SELECT COUNT(id) FROM leave_ledger",Integer.class));
    }

    @Test
    void auditSearchFiltersKnownOwnershipAndNeverReturnsSensitivePayloads() {
        audit(publicA,"EMPLOYEE"); audit(publicB,"EMPLOYEE"); audit(publicA,"UNCLASSIFIED");
        var result=new JdbcAuditReportQuery(source).query(filter("AUDIT",hr,null));
        assertEquals(1,result.rows().size());
        assertFalse(result.columns().contains("details"));
        assertFalse(result.rows().toString().contains("sensitive"));
        assertTrue(new JdbcAuditReportQuery(source).query(filter("AUDIT",owner,null)).rows().isEmpty());
    }

    @Test
    void aMultiQueryReadOnlySnapshotDoesNotMixAConcurrentCommittedClaimRevision() {
        var manager=new DataSourceTransactionManager(source);
        var snapshot=new TransactionTemplate(manager);
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var writer=new TransactionTemplate(manager);
        writer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        var reports=new JdbcReimbursementReportQuery(source);
        snapshot.execute(status->{
            assertEquals("100",reports.query(filter("REIMBURSEMENTS",owner,null)).rows().getFirst().get(6));
            writer.execute(write->{
                fixture.jdbc.update("UPDATE reimbursement_claim SET amount=200,version=version+1 WHERE employee_id=?",employeeA);
                return null;
            });
            assertEquals("100",reports.query(filter("REIMBURSEMENTS",owner,null)).rows().getFirst().get(6));
            return null;
        });
        assertEquals("200",reports.query(filter("REIMBURSEMENTS",owner,null)).rows().getFirst().get(6));
    }

    @Test
    void auditOperationsAuthorityMustBeOrganizationWideAndStillSkipsUnknownScope() {
        audit(publicA,"EMPLOYEE"); audit(publicB,"EMPLOYEE"); audit(publicA,"UNKNOWN");
        long scopedOperator=account("scoped-operations","SYSTEM_ADMIN","ENG");
        long globalOperator=account("global-operations","SYSTEM_ADMIN",null);
        var audits=new JdbcAuditReportQuery(source);
        assertTrue(audits.query(filter("AUDIT",scopedOperator,null)).rows().isEmpty());
        assertEquals(2,audits.query(filter("AUDIT",globalOperator,null)).rows().size());
        var authorization=new JdbcReportAuthorizationRepository(source);
        assertFalse(authorization.containsTarget(scopedOperator,"AUDIT",publicA,null,NOW,deadline()));
        assertTrue(authorization.containsTarget(globalOperator,"AUDIT",publicB,null,NOW,deadline()));
    }

    @Test
    void explicitForeignEmployeeAndUnitFiltersNeverExpandMonetaryRows() {
        var reports=new JdbcReimbursementReportQuery(source);
        var own=filter("REIMBURSEMENTS",owner,null);
        var foreign=new ReportFilter(own.reportType(),own.from(),own.to(),own.year(),publicB,"FIN",null,null,
            null,null,null,null,null,owner,NOW,100,deadline());
        assertTrue(reports.query(foreign).rows().isEmpty());
        var authorization=new JdbcReportAuthorizationRepository(source);
        assertFalse(authorization.containsTarget(owner,"REIMBURSEMENTS",publicB,"FIN",NOW,deadline()));
        assertFalse(authorization.containsTarget(hr,"REIMBURSEMENTS",publicA,"ENG",NOW,deadline()));
    }

    @Test
    void snapshotTimeComesFromTheDatabaseAndExpiredReadsFailClosed() {
        var snapshots=new JdbcReportSnapshotClock(source);
        assertTrue(snapshots.snapshotTime(deadline()).isAfter(Instant.EPOCH));
        assertThrows(ReportLimitException.class,()->snapshots.snapshotTime(0));
        assertThrows(IllegalArgumentException.class,()->ReportSql.role("ARBITRARY"));
        assertThrows(IllegalArgumentException.class,()->ReportSql.eligibleReviewerById("1","ARBITRARY"));
    }

    @Test
    void expiredBudgetStopsBeforeExecutingEvenAValidQuery() {
        var f=filter("REIMBURSEMENTS",owner,null);
        var expired=new ReportFilter(f.reportType(),f.from(),f.to(),f.year(),f.employeeId(),f.unit(),f.status(),
            f.workflowType(),f.resourceId(),f.actorId(),f.action(),f.correlationId(),f.category(),f.accountId(),f.now(),f.rowLimit(),0);
        assertThrows(ReportLimitException.class,()->ReportSql.query(source,"SELECT 1 value",expired,List.of("value")));
    }

    private ReportFilter filter(String type,long account,String workflow) {
        return filter(type,account,workflow,null);
    }
    private ReportFilter filter(String type,long account,String workflow,UUID employee) {
        return new ReportFilter(type,FROM,TO,2026,employee,null,null,workflow,null,null,null,null,null,account,NOW,100,deadline());
    }
    private ReportTable query(String type,long account,UUID employee) {
        var reportFilter=filter(type,account,null,employee);
        return switch(type) {
            case "ATTENDANCE" -> new JdbcAttendanceReportQuery(source).query(reportFilter);
            case "LEAVE_BALANCES" -> new JdbcLeaveReportQuery(source).query(reportFilter);
            case "REQUEST_STATUS" -> {
                List<ReportTable> tables=List.of(new JdbcProfileReportQuery(source).query(reportFilter),
                    new JdbcAttendanceReportQuery(source).query(reportFilter),new JdbcLeaveReportQuery(source).query(reportFilter),
                    new JdbcReimbursementReportQuery(source).query(reportFilter));
                yield new ReportTable(tables.getFirst().columns(),tables.stream().flatMap(table->table.rows().stream()).toList());
            }
            case "REIMBURSEMENTS" -> new JdbcReimbursementReportQuery(source).query(reportFilter);
            case "PAYSLIP_STATUS" -> new JdbcPayslipReportQuery(source).query(reportFilter);
            case "AUDIT" -> new JdbcAuditReportQuery(source).query(reportFilter);
            default -> throw new IllegalArgumentException("Unknown report type: "+type);
        };
    }
    private static boolean isAllowed(Map<Long,UUID> audiences,long account,UUID target) {
        return target.equals(audiences.get(account));
    }
    private void seedCatalogRows() {
        for (long employee : List.of(employeeA,employeeB)) {
            proposal(employee); correction(employee); leave(employee);
            fixture.jdbc.update("INSERT INTO leave_account(employee_id,created_at) VALUES (?,?)",employee,Timestamp.from(NOW));
            fixture.jdbc.update("INSERT INTO leave_period(employee_id,period_year) VALUES (?,2026)",employee);
            seedAttendanceOccurrence(employee);
        }
        seedPayslip(employeeA); seedPayslip(employeeB);
        audit(publicA,"EMPLOYEE"); audit(publicB,"EMPLOYEE");
        audit(claim(employeeA),"REIMBURSEMENT"); audit(claim(employeeB),"REIMBURSEMENT");
    }
    private void seedAttendanceOccurrence(long employee) {
        long schedule=fixture.jdbc.queryForObject("INSERT INTO work_schedule_revision(public_id,created_at) VALUES (gen_random_uuid(),?) RETURNING id",
            Long.class,Timestamp.from(NOW));
        fixture.jdbc.update("INSERT INTO work_schedule_day(revision_id,weekday,start_time,end_time) VALUES (?,5,'09:00','17:00')",schedule);
        fixture.jdbc.update("UPDATE work_schedule_revision SET status='PUBLISHED',published_at=? WHERE id=?",Timestamp.from(NOW),schedule);
        long assignment=fixture.jdbc.queryForObject("INSERT INTO employee_schedule_assignment(public_id,employee_id,revision_id,from_date,created_at) VALUES (gen_random_uuid(),?,?,'2026-01-01',?) RETURNING id",
            Long.class,employee,schedule,Timestamp.from(NOW));
        fixture.jdbc.update("INSERT INTO attendance_occurrence(public_id,employee_id,work_date,start_at,end_at,timezone,schedule_revision_id,schedule_assignment_id,created_at) VALUES (gen_random_uuid(),?,'2026-11-13',?,?,'Asia/Jakarta',?,?,?)",
            employee,Timestamp.from(NOW),Timestamp.from(NOW.plusSeconds(3600)),schedule,assignment,Timestamp.from(NOW));
    }
    private void seedPayslip(long employee) {
        var transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.executeWithoutResult(status->seedPayslipInTransaction(employee));
    }
    private void seedPayslipInTransaction(long employee) {
        var document=verifiedPayslipDocument(employee);
        long batch=fixture.jdbc.queryForObject("INSERT INTO payslip_batch(public_id,created_by,created_at,updated_at) VALUES (?,?,?,?) RETURNING id",
            Long.class,UUID.randomUUID(),finance,Timestamp.from(NOW),Timestamp.from(NOW));
        long revision=fixture.jdbc.queryForObject("INSERT INTO payslip_batch_revision(public_id,batch_id,revision_number,created_at) VALUES (?,?,1,?) RETURNING id",
            Long.class,UUID.randomUUID(),batch,Timestamp.from(NOW));
        fixture.jdbc.update("UPDATE payslip_batch SET current_revision_id=? WHERE id=?",revision,batch);
        long item=fixture.jdbc.queryForObject("INSERT INTO payslip_batch_item(public_id,revision_id,employee_id,period_start,document_id,document_version_id) VALUES (?,?,?,'2026-11-01',?,?) RETURNING id",
            Long.class,UUID.randomUUID(),revision,employee,document[0],document[1]);
        fixture.jdbc.update("UPDATE payslip_batch_revision SET status='REVIEWED',reviewed_by=?,reviewed_at=? WHERE id=?",finance,Timestamp.from(NOW),revision);
        fixture.jdbc.update("UPDATE payslip_batch SET status='REVIEWED' WHERE id=?",batch);
        long publication=fixture.jdbc.queryForObject("INSERT INTO payslip_publication(public_id,revision_id,item_id,employee_id,period_start,published_by,published_at) VALUES (?,?,?,?,'2026-11-01',?,?) RETURNING id",
            Long.class,UUID.randomUUID(),revision,item,employee,finance,Timestamp.from(NOW));
        fixture.jdbc.update("INSERT INTO payslip_current(employee_id,period_start,publication_id) VALUES (?,'2026-11-01',?)",employee,publication);
        fixture.jdbc.update("UPDATE payslip_batch SET status='PUBLISHED' WHERE id=?",batch);
    }
    private long[] verifiedPayslipDocument(long employee) {
        long document=fixture.jdbc.queryForObject("INSERT INTO private_document(public_id,employee_id,purpose,status,file_name,declared_media_type,declared_size,current_generation,created_at,updated_at) VALUES (?,?, 'PAYSLIP','VERIFYING','payslip.pdf','application/pdf',1,1,?,?) RETURNING id",
            Long.class,UUID.randomUUID(),employee,Timestamp.from(NOW),Timestamp.from(NOW));
        UUID uploadId=UUID.randomUUID();
        long upload=fixture.jdbc.queryForObject("INSERT INTO document_upload_attempt(public_id,document_id,generation,staging_key,byte_size,status,created_at) VALUES (?,?,1,?,1,'RESERVED',?) RETURNING id",
            Long.class,uploadId,document,"staging/"+uploadId,Timestamp.from(NOW));
        fixture.jdbc.update("UPDATE document_upload_attempt SET status='COMPLETED',object_etag='etag',completed_at=? WHERE id=?",Timestamp.from(NOW),upload);
        fixture.jdbc.update("UPDATE private_document SET current_upload_id=?,current_upload_generation=1 WHERE id=?",upload,document);
        UUID attemptId=UUID.randomUUID();
        long attempt=fixture.jdbc.queryForObject("INSERT INTO document_verification_attempt(public_id,document_id,upload_attempt_id,generation,candidate_key,status,created_at) VALUES (?,?,?,1,?,'QUEUED',?) RETURNING id",
            Long.class,attemptId,document,upload,"candidates/"+attemptId,Timestamp.from(NOW));
        UUID token=UUID.randomUUID();
        fixture.jdbc.update("UPDATE document_verification_attempt SET status='CLAIMED',lease_token=?,lease_expires_at=clock_timestamp()+interval '5 minutes' WHERE id=?",token,attempt);
        fixture.jdbc.update("UPDATE document_verification_attempt SET copy_started_at=clock_timestamp() WHERE id=?",attempt);
        fixture.jdbc.update("UPDATE document_verification_attempt SET copy_completed_at=clock_timestamp() WHERE id=?",attempt);
        fixture.jdbc.update("UPDATE document_verification_attempt SET status='CLEAN',completed_at=clock_timestamp() WHERE id=?",attempt);
        long version=fixture.jdbc.queryForObject("INSERT INTO document_version(public_id,document_id,attempt_id,lease_token,media_type,byte_size,sha256,scanner_version,verified_at) VALUES (?,?,?,?,'application/pdf',1,repeat('a',64),'test',?) RETURNING id",
            Long.class,UUID.randomUUID(),document,attempt,token,Timestamp.from(NOW));
        fixture.jdbc.update("UPDATE private_document SET status='VERIFIED',current_version_id=? WHERE id=?",version,document);
        return new long[]{document,version};
    }
    private static long deadline() { return System.nanoTime()+Duration.ofSeconds(30).toNanos(); }
    private long employee(String number) {
        return fixture.jdbc.queryForObject("INSERT INTO employees(public_id,employee_number,created_at) VALUES (?,?,?) RETURNING id",
            Long.class,UUID.randomUUID(),number,Timestamp.from(NOW.minusSeconds(86400)));
    }
    private UUID employeePublicId(long id) { return fixture.jdbc.queryForObject("SELECT public_id FROM employees WHERE id=?",UUID.class,id); }
    private long account(String subject,String role,String scope) {
        Long id=fixture.jdbc.queryForObject("INSERT INTO user_accounts(public_id,oidc_issuer,oidc_subject,created_at) VALUES (?,'https://issuer.example',?,?) RETURNING id",
            Long.class,UUID.randomUUID(),subject,Timestamp.from(NOW.minusSeconds(86400)));
        fixture.jdbc.update("INSERT INTO role_assignments(account_id,role,scope,effective_from,created_at) VALUES (?,?,?,?,?)",
            id,role,scope,Timestamp.from(NOW.minusSeconds(86400)),Timestamp.from(NOW.minusSeconds(86400)));
        return id;
    }
    private void bind(long account,long employee) {
        String subject=fixture.jdbc.queryForObject("SELECT oidc_subject FROM user_accounts WHERE id=?",String.class,account);
        fixture.jdbc.update("INSERT INTO account_bindings(employee_id,employee_public_id,account_id,oidc_issuer,oidc_subject,created_at) VALUES (?,?,?,'https://issuer.example',?,?)",
            employee,employeePublicId(employee),account,subject,Timestamp.from(NOW.minusSeconds(86400)));
    }
    private void assignment(long employee,String unit,Long manager) {
        fixture.jdbc.update("INSERT INTO employee_assignments(public_id,employee_id,org_unit,manager_employee_id,job_level,valid_from,created_at) VALUES (?,?,?,?,'L1',?,?)",
            UUID.randomUUID(),employee,unit,manager,Timestamp.from(NOW.minusSeconds(86400)),Timestamp.from(NOW.minusSeconds(86400)));
    }
    private UUID claim(long employee) {
        UUID publicId=UUID.randomUUID();
        fixture.jdbc.update("INSERT INTO reimbursement_claim(public_id,employee_id,status,category_code,transaction_date,amount,description,created_at) VALUES (?,?,'DRAFT','TRAVEL',?,100,'sensitive',?)",
            publicId,employee,FROM,Timestamp.from(NOW.minusSeconds(3600)));
        return publicId;
    }
    private void proposal(long employee) {
        fixture.jdbc.update("INSERT INTO profile_proposal(public_id,employee_id,created_at,updated_at) VALUES (?,?,?,?)",
            UUID.randomUUID(),employee,Timestamp.from(NOW.minusSeconds(3600)),Timestamp.from(NOW));
    }
    private void correction(long employee) {
        fixture.jdbc.update("INSERT INTO attendance_correction(public_id,employee_id,employee_public_id,work_date,status,proposed_in,reason,created_at) VALUES (?,?,?,?,'DRAFT',?,'sensitive',?)",
            UUID.randomUUID(),employee,employeePublicId(employee),FROM,Timestamp.from(NOW.minusSeconds(3600)),Timestamp.from(NOW.minusSeconds(3600)));
    }
    private void leave(long employee) {
        fixture.jdbc.update("INSERT INTO leave_request(public_id,employee_id,status,from_date,to_date,portion,reason,created_at) VALUES (?,?,'DRAFT',?,?,'FULL','sensitive',?)",
            UUID.randomUUID(),employee,FROM,FROM,Timestamp.from(NOW.minusSeconds(3600)));
    }
    private void audit(UUID resource,String type) {
        fixture.jdbc.update("INSERT INTO audit_events(event_id,actor_id,action,aggregate_type,aggregate_public_id,reason,details,created_at) VALUES (?,'hr','READ',?,?,'sensitive','sensitive',?)",
            UUID.randomUUID(),type,resource,Timestamp.from(NOW));
    }
}
