package id.mydev.peoplecore.payslip;

import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.document.application.service.VerifiedDocumentAccess;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.organization.domain.model.Employee;
import id.mydev.peoplecore.payslip.application.PayslipModels.Draft;
import id.mydev.peoplecore.payslip.application.PayslipModels.Item;
import id.mydev.peoplecore.payslip.application.PayslipService;
import id.mydev.peoplecore.support.PostgresFixture;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("postgres")
class PayslipServicePostgresTest {
    @Test
    void knownEmploymentDatesBoundPeriodsAtEveryTransition() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            JdbcTemplate jdbc = fixture.runtime();
            UUID owner = UUID.randomUUID();
            long ownerId = employee(jdbc, owner);
            long actorId = jdbc.queryForObject("insert into user_accounts(public_id,oidc_issuer,oidc_subject,created_at) "
                + "values(gen_random_uuid(),'https://identity.example',?,now()) returning id", Long.class, UUID.randomUUID().toString());
            UUID document = documentId(jdbc, PayslipPersistencePostgresTest.verifiedDocument(jdbc, ownerId).id());
            Authentication caller = mock(Authentication.class);
            UserAccount actor = mock(UserAccount.class);
            Employee employee = employeeMock(ownerId, owner);
            EmployeeService employees = mock(EmployeeService.class);
            AccountAccessService access = mock(AccountAccessService.class);
            when(actor.getId()).thenReturn(actorId);
            when(access.requireActive(eq(caller), any(Instant.class))).thenReturn(actor);
            when(access.hasScopedRole(eq(actorId), eq(HrisRole.FINANCE_PAYROLL), eq("UNIT-A"), any(Instant.class))).thenReturn(true);
            when(employees.requireInternalByPublicId(owner)).thenReturn(employee);
            when(employees.currentOrgUnit(eq(ownerId), any(Instant.class))).thenReturn(Optional.of("UNIT-A"));
            when(employee.getEmploymentStartDate()).thenReturn(LocalDate.of(2026, 11, 30));
            when(employee.getEmploymentEndDate()).thenReturn(LocalDate.of(2026, 12, 1));
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
            var service = new PayslipService(jdbc, employees, access, new VerifiedDocumentAccess(jdbc),
                Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC), new ObjectMapper(),
                mock(EntityManager.class), mock(AdmissionGuard.class));
            UUID batch = UUID.randomUUID();
            Draft november = new Draft(List.of(new Item(owner, YearMonth.of(2026, 11), document)));
            Draft december = new Draft(List.of(new Item(owner, YearMonth.of(2026, 12), document)));
            Draft october = new Draft(List.of(new Item(owner, YearMonth.of(2026, 10), document)));
            Draft january = new Draft(List.of(new Item(owner, YearMonth.of(2027, 1), document)));

            assertThrows(IllegalArgumentException.class, () -> transactions.execute(status -> service.create(batch, october, caller)));
            assertThrows(IllegalArgumentException.class, () -> transactions.execute(status -> service.create(batch, january, caller)));
            when(employee.getEmploymentStartDate()).thenReturn(null);
            transactions.executeWithoutResult(status -> service.create(batch, november, caller));
            when(employee.getEmploymentStartDate()).thenReturn(LocalDate.of(2026, 11, 30));
            assertThrows(IllegalArgumentException.class, () -> transactions.execute(status -> service.edit(batch, january, 1, caller)));
            transactions.executeWithoutResult(status -> service.edit(batch, december, 1, caller));
            when(employee.getEmploymentEndDate()).thenReturn(LocalDate.of(2026, 11, 30));
            assertThrows(IllegalArgumentException.class, () -> transactions.execute(status -> service.review(batch, 2, caller)));
            when(employee.getEmploymentEndDate()).thenReturn(LocalDate.of(2026, 12, 1));
            transactions.executeWithoutResult(status -> service.review(batch, 2, caller));
            when(employee.getEmploymentEndDate()).thenReturn(LocalDate.of(2026, 11, 30));
            assertThrows(IllegalArgumentException.class, () -> transactions.execute(status -> service.publish(batch, 2, caller)));
            when(employee.getEmploymentStartDate()).thenReturn(null);
            when(employee.getEmploymentEndDate()).thenReturn(null);
            assertThrows(IllegalArgumentException.class, () -> transactions.execute(status -> service.publish(batch, 2, caller)));
            when(employee.getEmploymentStartDate()).thenReturn(LocalDate.of(2026, 11, 30));
            transactions.executeWithoutResult(status -> service.publish(batch, 2, caller));
            assertEquals("PUBLISHED", jdbc.queryForObject("select status from payslip_batch where public_id=?", String.class, batch));
        }
    }

    @Test
    void editingReviewedBatchRequiresReviewOfTheNewMappingBeforePublication() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            JdbcTemplate jdbc = fixture.runtime();
            UUID owner = UUID.randomUUID();
            long ownerId = employee(jdbc, owner);
            long actorId = jdbc.queryForObject("insert into user_accounts(public_id,oidc_issuer,oidc_subject,created_at) "
                + "values(gen_random_uuid(),'https://identity.example',?,now()) returning id", Long.class, UUID.randomUUID().toString());
            var initial = PayslipPersistencePostgresTest.verifiedDocument(jdbc, ownerId);
            var replacement = PayslipPersistencePostgresTest.verifiedDocument(jdbc, ownerId);
            UUID initialId = documentId(jdbc, initial.id());
            UUID replacementId = documentId(jdbc, replacement.id());
            Authentication caller = mock(Authentication.class);
            UserAccount actor = mock(UserAccount.class);
            EmployeeService employees = mock(EmployeeService.class);
            AccountAccessService access = mock(AccountAccessService.class);
            when(actor.getId()).thenReturn(actorId);
            when(access.requireActive(eq(caller), any(Instant.class))).thenReturn(actor);
            when(access.hasScopedRole(eq(actorId), eq(HrisRole.FINANCE_PAYROLL), eq("UNIT-A"), any(Instant.class))).thenReturn(true);
            Employee ownerEmployee = employeeMock(ownerId, owner);
            when(ownerEmployee.getEmploymentStartDate()).thenReturn(LocalDate.of(2020, 1, 1));
            when(employees.requireInternalByPublicId(owner)).thenReturn(ownerEmployee);
            when(employees.currentOrgUnit(eq(ownerId), any(Instant.class))).thenReturn(Optional.of("UNIT-A"));
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
            var service = new PayslipService(jdbc, employees, access, new VerifiedDocumentAccess(jdbc),
                Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC), new ObjectMapper(),
                mock(EntityManager.class), mock(AdmissionGuard.class));
            UUID batch = UUID.randomUUID();
            var month = YearMonth.of(2026, 11);
            Draft initialDraft = new Draft(List.of(new Item(owner, month, initialId)));
            Draft replacementDraft = new Draft(List.of(new Item(owner, month, replacementId)));
            UUID pendingId = UUID.randomUUID();
            jdbc.update("insert into private_document(public_id,employee_id,purpose,status,file_name,declared_media_type,"
                    + "declared_size,created_at,updated_at) values (?,?,'PAYSLIP','PENDING_UPLOAD','draft.pdf','application/pdf',1,now(),now())",
                pendingId, ownerId);

            assertThrows(ResourceNotFoundException.class, () -> transactions.execute(status ->
                service.create(batch, new Draft(List.of(new Item(owner, month, pendingId))), caller)));
            assertEquals(0, jdbc.queryForObject("select count(id) from payslip_batch where public_id=?", Integer.class, batch));

            transactions.executeWithoutResult(status -> service.create(batch, initialDraft, caller));
            assertThrows(ResponseStatusException.class,
                () -> transactions.execute(status -> service.publish(batch, 1, caller)));
            transactions.executeWithoutResult(status -> service.review(batch, 1, caller));
            var edited = transactions.execute(status -> service.edit(batch, replacementDraft, 1, caller));
            assertEquals("DRAFT", edited.status());
            assertEquals(2, edited.revision());
            assertThrows(ResponseStatusException.class,
                () -> transactions.execute(status -> service.publish(batch, 1, caller)));
            assertThrows(ResponseStatusException.class,
                () -> transactions.execute(status -> service.publish(batch, 2, caller)));
            transactions.executeWithoutResult(status -> service.review(batch, 2, caller));
            transactions.executeWithoutResult(status -> service.publish(batch, 2, caller));
            assertEquals(replacementId, jdbc.queryForObject("select d.public_id from payslip_current c "
                + "join payslip_publication p on p.id=c.publication_id join payslip_batch_item i on i.id=p.item_id "
                + "join private_document d on d.id=i.document_id where c.employee_id=?", UUID.class, ownerId));
        }
    }

    @Test
    void publicationIsOwnedAndFailedReplacementPreservesPreviousVersion() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            JdbcTemplate jdbc = fixture.runtime();
            UUID owner = UUID.randomUUID();
            UUID other = UUID.randomUUID();
            long ownerId = employee(jdbc, owner);
            long otherId = employee(jdbc, other);
            long actorId = jdbc.queryForObject("insert into user_accounts(public_id,oidc_issuer,oidc_subject,created_at) "
                + "values(gen_random_uuid(),'https://identity.example',?,now()) returning id", Long.class, UUID.randomUUID().toString());
            var firstDocument = PayslipPersistencePostgresTest.verifiedDocument(jdbc, ownerId);
            var replacementDocument = PayslipPersistencePostgresTest.verifiedDocument(jdbc, ownerId);
            UUID firstId = documentId(jdbc, firstDocument.id());
            UUID replacementId = documentId(jdbc, replacementDocument.id());
            Authentication caller = mock(Authentication.class);
            UserAccount actor = mock(UserAccount.class);
            Employee ownerEmployee = employeeMock(ownerId, owner);
            when(ownerEmployee.getEmploymentStartDate()).thenReturn(LocalDate.of(2020, 1, 1));
            Employee otherEmployee = employeeMock(otherId, other);
            EmployeeService employees = mock(EmployeeService.class);
            AccountAccessService access = mock(AccountAccessService.class);
            when(actor.getId()).thenReturn(actorId);
            when(employees.requireInternalByPublicId(owner)).thenReturn(ownerEmployee);
            when(employees.requireInternalByPublicId(other)).thenReturn(otherEmployee);
            when(employees.currentOrgUnit(eq(ownerId), any(Instant.class))).thenReturn(Optional.of("UNIT-A"));
            when(employees.currentOrgUnit(eq(otherId), any(Instant.class))).thenReturn(Optional.of("UNIT-A"));
            when(access.hasScopedRole(eq(actorId), eq(HrisRole.FINANCE_PAYROLL), eq("UNIT-A"), any(Instant.class)))
                .thenReturn(true);
            when(access.activeRoles(eq(actorId), any(Instant.class))).thenReturn(List.of(HrisRole.EMPLOYEE));
            AtomicBoolean revokeDuringReplacement = new AtomicBoolean();
            UUID replacementBatch = UUID.randomUUID();
            when(access.requireActive(eq(caller), any(Instant.class))).thenAnswer(invocation -> {
                if (revokeDuringReplacement.get() && hasPublication(jdbc, replacementBatch)) {
                    throw new AccessDeniedException("Account access ended");
                }
                return actor;
            });
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
            var service = new PayslipService(jdbc, employees, access, new VerifiedDocumentAccess(jdbc),
                Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC), new ObjectMapper(),
                mock(EntityManager.class), mock(AdmissionGuard.class));
            UUID firstBatch = UUID.randomUUID();
            Draft first = new Draft(List.of(new Item(owner, YearMonth.of(2026, 11), firstId)));
            transactions.executeWithoutResult(status -> service.create(firstBatch, first, caller));
            transactions.executeWithoutResult(status -> service.review(firstBatch, 1, caller));
            transactions.executeWithoutResult(status -> service.publish(firstBatch, 1, caller));
            UUID published = jdbc.queryForObject("select p.public_id from payslip_current c "
                + "join payslip_publication p on p.id=c.publication_id where c.employee_id=?", UUID.class, ownerId);

            when(access.ownEmployeePublicId(actor)).thenReturn(Optional.of(owner));
            var ownerPage = transactions.execute(status -> service.published(caller, 10, null));
            assertEquals(List.of(published), ownerPage.items().stream().map(item -> item.publicId()).toList());
            var ownerVersion = transactions.execute(status -> service.requirePublishedVersion(published, caller));
            assertEquals(firstId, ownerVersion.documentPublicId());
            assertEquals(jdbc.queryForObject("select public_id from document_version where id=?", UUID.class, firstDocument.version()),
                ownerVersion.versionPublicId());
            when(access.ownEmployeePublicId(actor)).thenReturn(Optional.of(other));
            var strangerPage = transactions.execute(status -> service.published(caller, 10, null));
            assertEquals(0, strangerPage.items().size());
            assertThrows(ResourceNotFoundException.class,
                () -> transactions.execute(status -> service.requirePublishedVersion(published, caller)));

            Draft replacement = new Draft(List.of(new Item(owner, YearMonth.of(2026, 11), replacementId)));
            transactions.executeWithoutResult(status -> service.create(replacementBatch, replacement, caller));
            transactions.executeWithoutResult(status -> service.review(replacementBatch, 1, caller));
            revokeDuringReplacement.set(true);
            assertThrows(AccessDeniedException.class, () -> transactions.executeWithoutResult(
                status -> service.publish(replacementBatch, 1, caller)));
            assertEquals(published, jdbc.queryForObject("select p.public_id from payslip_current c "
                + "join payslip_publication p on p.id=c.publication_id where c.employee_id=?", UUID.class, ownerId));
            assertEquals(1, jdbc.queryForObject("select count(id) from payslip_publication where employee_id=?", Integer.class, ownerId));
            assertEquals("REVIEWED", jdbc.queryForObject("select status from payslip_batch where public_id=?", String.class, replacementBatch));

            revokeDuringReplacement.set(false);
            transactions.executeWithoutResult(status -> service.publish(replacementBatch, 1, caller));
            when(access.ownEmployeePublicId(actor)).thenReturn(Optional.of(owner));
            assertThrows(ResourceNotFoundException.class,
                () -> transactions.execute(status -> service.requirePublishedVersion(published, caller)));
            UUID currentPublication = jdbc.queryForObject("select p.public_id from payslip_current c "
                + "join payslip_publication p on p.id=c.publication_id where c.employee_id=?", UUID.class, ownerId);
            var currentVersion = transactions.execute(status -> service.requirePublishedVersion(currentPublication, caller));
            assertEquals(replacementId, currentVersion.documentPublicId());
            assertEquals(2, jdbc.queryForObject("select count(id) from payslip_publication where employee_id=?", Integer.class, ownerId));
        }
    }

    private static boolean hasPublication(JdbcTemplate jdbc, UUID batch) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from payslip_publication p "
            + "join payslip_batch_revision r on r.id=p.revision_id join payslip_batch b on b.id=r.batch_id "
            + "where b.public_id=?)", Boolean.class, batch));
    }

    private static long employee(JdbcTemplate jdbc, UUID publicId) {
        return jdbc.queryForObject("insert into employees(public_id,employee_number,created_at) "
            + "values(?,?,now()) returning id", Long.class, publicId, "PAY-" + UUID.randomUUID());
    }

    private static UUID documentId(JdbcTemplate jdbc, long id) {
        return jdbc.queryForObject("select public_id from private_document where id=?", UUID.class, id);
    }

    private static Employee employeeMock(long id, UUID publicId) {
        Employee employee = mock(Employee.class);
        when(employee.getId()).thenReturn(id);
        when(employee.getPublicId()).thenReturn(publicId);
        return employee;
    }
}
