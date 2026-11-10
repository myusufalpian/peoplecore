package id.mydev.peoplecore.payslip;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.document.application.service.VerifiedDocumentAccess;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.organization.domain.model.Employee;
import id.mydev.peoplecore.payslip.application.PayslipModels.Draft;
import id.mydev.peoplecore.payslip.application.PayslipModels.Item;
import id.mydev.peoplecore.payslip.application.PayslipModels.Mutation;
import id.mydev.peoplecore.payslip.application.PayslipModels.Published;
import id.mydev.peoplecore.payslip.application.PayslipModels.PublishedVersion;
import id.mydev.peoplecore.payslip.application.PayslipService;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.sql.ResultSet;
import java.util.List;
import java.util.stream.IntStream;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PayslipServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final EmployeeService employees = mock(EmployeeService.class);
    private final AccountAccessService access = mock(AccountAccessService.class);
    private final VerifiedDocumentAccess documents = mock(VerifiedDocumentAccess.class);
    private final ObjectMapper mapper = mock(ObjectMapper.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC);
    private final PayslipService service = new PayslipService(jdbc, employees, access, documents, clock, mapper,
        mock(EntityManager.class), mock(AdmissionGuard.class));

    @Test
    void rejectsDuplicateAndInvalidMappingsBeforePersistence() {
        Item item = new Item(UUID.randomUUID(), YearMonth.of(2026, 11), UUID.randomUUID());
        Item invalidMonth = new Item(item.employeePublicId(), null, item.documentPublicId());
        Authentication caller = mock(Authentication.class);

        assertThrows(IllegalArgumentException.class, () -> service.create(UUID.randomUUID(), new Draft(List.of(item, item)), caller));
        assertThrows(IllegalArgumentException.class, () -> service.create(UUID.randomUUID(),
            new Draft(List.of(item, new Item(item.employeePublicId(), item.period(), UUID.randomUUID()))), caller));
        assertThrows(IllegalArgumentException.class, () -> service.create(UUID.randomUUID(), new Draft(List.of(invalidMonth)), caller));
        assertThrows(IllegalArgumentException.class, () -> service.create(UUID.randomUUID(), new Draft(List.of()), caller));
        assertThrows(IllegalArgumentException.class, () -> service.create(UUID.randomUUID(), null, caller));
        assertThrows(IllegalArgumentException.class, () -> service.create(UUID.randomUUID(), new Draft(null), caller));
        assertThrows(IllegalArgumentException.class, () -> service.create(UUID.randomUUID(),
            new Draft(java.util.Collections.singletonList(null)), caller));
        assertThrows(IllegalArgumentException.class, () -> service.create(UUID.randomUUID(),
            new Draft(List.of(new Item(null, item.period(), item.documentPublicId()))), caller));
        assertThrows(IllegalArgumentException.class, () -> service.create(UUID.randomUUID(),
            new Draft(List.of(new Item(item.employeePublicId(), item.period(), null))), caller));
        verifyNoInteractions(jdbc, documents);
    }

    @Test
    void acceptsBatchSizeLimitForAuthorizationAndRejectsMoreThanOneHundred() {
        UUID employeeId = UUID.randomUUID();
        Authentication caller = mock(Authentication.class);
        UserAccount actor = mock(UserAccount.class);
        Employee employee = mock(Employee.class);
        when(actor.getId()).thenReturn(10L);
        when(employee.getId()).thenReturn(20L);
        when(access.requireActive(caller, clock.instant())).thenReturn(actor);
        when(employees.requireInternalByPublicId(employeeId)).thenReturn(employee);
        when(employees.currentOrgUnit(20L, clock.instant())).thenReturn(Optional.of("outside-scope"));
        List<Item> items = IntStream.range(0, 101)
            .mapToObj(index -> new Item(employeeId, YearMonth.of(2020, 1).plusMonths(index), UUID.randomUUID()))
            .toList();

        assertThrows(ResourceNotFoundException.class,
            () -> service.create(UUID.randomUUID(), new Draft(items.subList(0, 100)), caller));
        assertThrows(IllegalArgumentException.class,
            () -> service.create(UUID.randomUUID(), new Draft(items), caller));
        verifyNoInteractions(jdbc, documents);
    }

    @Test
    void rejectsMissingIdentityBeforeLookingUpBatch() {
        JsonNode payload = mock(JsonNode.class);
        when(payload.toString()).thenReturn("{}");
        when(mapper.readValue(any(String.class), org.mockito.ArgumentMatchers.eq(Mutation.class)))
            .thenReturn(new Mutation(null, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> service.verifyCommand("PAYSLIP_BATCH_REVIEW", payload, mock(Authentication.class)));
        verifyNoInteractions(jdbc, documents);
    }

    @Test
    void employeeListRejectsOutOfRangePageSizes() {
        Authentication caller = mock(Authentication.class);
        assertThrows(IllegalArgumentException.class, () -> service.published(caller, 0, null));
        assertThrows(IllegalArgumentException.class, () -> service.published(caller, 101, null));
        verifyNoInteractions(jdbc, documents);
    }

    @Test
    void rejectsCreateForAnEmployeeOutsideCurrentScope() {
        UUID employeeId = UUID.randomUUID();
        Item item = new Item(employeeId, YearMonth.of(2026, 11), UUID.randomUUID());
        JsonNode payload = mock(JsonNode.class);
        when(payload.toString()).thenReturn("{}");
        when(mapper.readValue(any(String.class), org.mockito.ArgumentMatchers.eq(Mutation.class)))
            .thenReturn(new Mutation(UUID.randomUUID(), new Draft(List.of(item)), null));
        Authentication caller = mock(Authentication.class);
        UserAccount actor = mock(UserAccount.class);
        Employee employee = mock(Employee.class);
        when(actor.getId()).thenReturn(10L);
        when(employee.getId()).thenReturn(20L);
        when(access.requireActive(caller, clock.instant())).thenReturn(actor);
        when(employees.requireInternalByPublicId(employeeId)).thenReturn(employee);
        when(employees.currentOrgUnit(20L, clock.instant())).thenReturn(Optional.of("other-unit"));

        assertThrows(ResourceNotFoundException.class, () -> service.verifyCommand("PAYSLIP_BATCH_CREATE", payload, caller));
        verifyNoInteractions(jdbc, documents);
    }

    @Test
    void scopedHrCanAuthorizePayslipDraft() {
        UUID employeeId = UUID.randomUUID();
        JsonNode payload = mock(JsonNode.class);
        when(payload.toString()).thenReturn("{}");
        when(mapper.readValue(any(String.class), org.mockito.ArgumentMatchers.eq(Mutation.class)))
            .thenReturn(new Mutation(UUID.randomUUID(), new Draft(List.of(
                new Item(employeeId, YearMonth.of(2026, 11), UUID.randomUUID()))), null));
        Authentication caller = mock(Authentication.class);
        UserAccount actor = mock(UserAccount.class);
        Employee employee = mock(Employee.class);
        when(actor.getId()).thenReturn(10L);
        when(employee.getId()).thenReturn(20L);
        when(access.requireActive(caller, clock.instant())).thenReturn(actor);
        when(employees.requireInternalByPublicId(employeeId)).thenReturn(employee);
        when(employees.currentOrgUnit(20L, clock.instant())).thenReturn(Optional.of("unit"));
        when(access.hasScopedRole(10L, HrisRole.HR_ADMIN, "unit", clock.instant())).thenReturn(true);

        service.verifyCommand("PAYSLIP_BATCH_CREATE", payload, caller);
        verifyNoInteractions(jdbc, documents);
    }

    @Test
    void rejectsMissingPublicationIdentityBeforeLookingUpOwner() {
        assertThrows(IllegalArgumentException.class,
            () -> service.requirePublishedVersion(null, mock(Authentication.class)));
        verifyNoInteractions(jdbc, access, employees, documents);
    }

    @Test
    void editingBatchCannotIntroduceAnEmployeeOutsideCurrentScope() {
        UUID batchId = UUID.randomUUID();
        UUID originalId = UUID.randomUUID();
        UUID outsideId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        JsonNode payload = mock(JsonNode.class);
        when(payload.toString()).thenReturn("{}");
        when(mapper.readValue(any(String.class), org.mockito.ArgumentMatchers.eq(Mutation.class)))
            .thenReturn(new Mutation(batchId, new Draft(List.of(new Item(outsideId, YearMonth.of(2026, 11), UUID.randomUUID()))), 1L));
        Authentication caller = mock(Authentication.class);
        UserAccount actor = mock(UserAccount.class);
        Employee original = mock(Employee.class);
        Employee outside = mock(Employee.class);
        when(actor.getId()).thenReturn(10L);
        when(original.getId()).thenReturn(20L);
        when(outside.getId()).thenReturn(30L);
        when(access.requireActive(caller, clock.instant())).thenReturn(actor);
        when(employees.requireInternalByPublicId(originalId)).thenReturn(original);
        when(employees.requireInternalByPublicId(outsideId)).thenReturn(outside);
        when(employees.currentOrgUnit(20L, clock.instant())).thenReturn(Optional.of("UNIT-A"));
        when(employees.currentOrgUnit(30L, clock.instant())).thenReturn(Optional.of("UNIT-B"));
        when(access.hasScopedRole(10L, HrisRole.FINANCE_PAYROLL, "UNIT-A", clock.instant())).thenReturn(true);
        doAnswer(call -> {
            ResultSet row = mock(ResultSet.class);
            when(row.getLong(1)).thenReturn(1L);
            when(row.getString(2)).thenReturn("DRAFT");
            when(row.getLong(3)).thenReturn(2L);
            when(row.getLong(4)).thenReturn(1L);
            RowMapper<?> mapping = call.getArgument(1);
            return List.of(mapping.mapRow(row, 0));
        }).when(jdbc).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), org.mockito.ArgumentMatchers.eq(batchId));
        doAnswer(call -> {
            ResultSet row = mock(ResultSet.class);
            when(row.getObject(1, UUID.class)).thenReturn(originalId);
            when(row.getDate(2)).thenReturn(java.sql.Date.valueOf("2026-11-01"));
            when(row.getObject(3, UUID.class)).thenReturn(documentId);
            RowMapper<?> mapping = call.getArgument(1);
            return List.of(mapping.mapRow(row, 0));
        }).when(jdbc).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), org.mockito.ArgumentMatchers.eq(2L));

        assertThrows(ResourceNotFoundException.class,
            () -> service.verifyCommand("PAYSLIP_BATCH_EDIT", payload, caller));
        verifyNoInteractions(documents);
    }

    @Test
    void employeeListRechecksAccountBeforeReturningResults() {
        UUID employeeId = UUID.randomUUID();
        Authentication caller = mock(Authentication.class);
        UserAccount actor = mock(UserAccount.class);
        Employee employee = mock(Employee.class);
        when(actor.getId()).thenReturn(10L);
        when(employee.getId()).thenReturn(20L);
        when(access.requireActive(caller, clock.instant())).thenReturn(actor)
            .thenThrow(new AccessDeniedException("cutoff"));
        when(access.ownEmployeePublicId(actor)).thenReturn(Optional.of(employeeId));
        when(access.activeRoles(10L, clock.instant())).thenReturn(List.of("EMPLOYEE"));
        when(employees.requireInternalByPublicId(employeeId)).thenReturn(employee);
        when(employees.currentOrgUnit(20L, clock.instant())).thenReturn(Optional.of("unit"));
        doReturn(List.of(new Published(UUID.randomUUID(), YearMonth.of(2026, 11))))
            .when(jdbc).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Published>>any(),
                any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(), any());

        assertThrows(AccessDeniedException.class, () -> service.published(caller, 50, null));
    }

    @Test
    void employeeListReturnsCursorOnlyWhenAnotherPageExists() {
        UUID employeeId = UUID.randomUUID();
        Authentication caller = mock(Authentication.class);
        UserAccount actor = mock(UserAccount.class);
        Employee employee = mock(Employee.class);
        when(actor.getId()).thenReturn(10L);
        when(employee.getId()).thenReturn(20L);
        when(access.requireActive(caller, clock.instant())).thenReturn(actor);
        when(access.ownEmployeePublicId(actor)).thenReturn(Optional.of(employeeId));
        when(access.activeRoles(10L, clock.instant())).thenReturn(List.of("EMPLOYEE"));
        when(employees.requireInternalByPublicId(employeeId)).thenReturn(employee);
        Published first = new Published(UUID.randomUUID(), YearMonth.of(2026, 11));
        Published second = new Published(UUID.randomUUID(), YearMonth.of(2026, 10));
        doReturn(List.of(first, second), List.of(second))
            .when(jdbc).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Published>>any(),
                any(), any(), any(), any());

        var page = service.published(caller, 1, null);
        assertEquals(List.of(first), page.items());
        assertEquals(first.publicId(), page.nextCursor());
        var last = service.published(caller, 1, page.nextCursor());
        assertEquals(List.of(second), last.items());
        assertEquals(null, last.nextCursor());
    }

    @Test
    void employeeListRejectsAccountWithNoEffectiveRole() {
        UUID employeeId = UUID.randomUUID();
        Authentication caller = mock(Authentication.class);
        UserAccount actor = mock(UserAccount.class);
        Employee employee = mock(Employee.class);
        when(actor.getId()).thenReturn(10L);
        when(employee.getId()).thenReturn(20L);
        when(access.requireActive(caller, clock.instant())).thenReturn(actor);
        when(access.ownEmployeePublicId(actor)).thenReturn(Optional.of(employeeId));
        when(access.activeRoles(10L, clock.instant())).thenReturn(List.of());
        when(employees.requireInternalByPublicId(employeeId)).thenReturn(employee);

        assertThrows(ResourceNotFoundException.class, () -> service.published(caller, 10, null));
        verifyNoInteractions(jdbc);
    }

    @Test
    void employeeListRejectsChangedAccountBindingBeforeReturning() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        Authentication caller = mock(Authentication.class);
        UserAccount actor = mock(UserAccount.class);
        Employee employee = mock(Employee.class);
        when(actor.getId()).thenReturn(10L);
        when(employee.getId()).thenReturn(20L);
        when(access.requireActive(caller, clock.instant())).thenReturn(actor);
        when(access.ownEmployeePublicId(actor)).thenReturn(Optional.of(firstId), Optional.of(secondId));
        when(access.activeRoles(10L, clock.instant())).thenReturn(List.of("EMPLOYEE"));
        when(employees.requireInternalByPublicId(firstId)).thenReturn(employee);
        when(employees.requireInternalByPublicId(secondId)).thenReturn(employee);
        doReturn(List.of()).when(jdbc).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Published>>any(),
            any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(), any());

        assertThrows(ResourceNotFoundException.class, () -> service.published(caller, 10, null));
    }

    @Test
    void publishedVersionRejectsChangedAccountBindingBeforeReturning() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        UUID publicationId = UUID.randomUUID();
        Authentication caller = mock(Authentication.class);
        UserAccount actor = mock(UserAccount.class);
        Employee employee = mock(Employee.class);
        when(actor.getId()).thenReturn(10L);
        when(employee.getId()).thenReturn(20L);
        when(access.requireActive(caller, clock.instant())).thenReturn(actor);
        when(access.ownEmployeePublicId(actor)).thenReturn(Optional.of(firstId), Optional.of(secondId));
        when(access.activeRoles(10L, clock.instant())).thenReturn(List.of("EMPLOYEE"));
        when(employees.requireInternalByPublicId(firstId)).thenReturn(employee);
        when(employees.requireInternalByPublicId(secondId)).thenReturn(employee);
        doReturn(List.of(new PublishedVersion(firstId, UUID.randomUUID(), UUID.randomUUID())))
            .when(jdbc).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<PublishedVersion>>any(),
                org.mockito.ArgumentMatchers.eq(publicationId), org.mockito.ArgumentMatchers.eq(firstId));

        assertThrows(ResourceNotFoundException.class, () -> service.requirePublishedVersion(publicationId, caller));
    }

    @Test
    void replayRequiresCurrentScopeOfTheRecordedBatch() {
        UUID batchId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        JsonNode payload = mock(JsonNode.class);
        when(payload.toString()).thenReturn("{}");
        when(mapper.readValue(any(String.class), org.mockito.ArgumentMatchers.eq(Mutation.class)))
            .thenReturn(new Mutation(batchId, null, 1L));
        Authentication caller = mock(Authentication.class);
        UserAccount actor = mock(UserAccount.class);
        Employee employee = mock(Employee.class);
        when(actor.getId()).thenReturn(10L);
        when(employee.getId()).thenReturn(20L);
        when(access.requireActive(caller, clock.instant())).thenReturn(actor);
        when(employees.requireInternalByPublicId(employeeId)).thenReturn(employee);
        when(employees.currentOrgUnit(20L, clock.instant())).thenReturn(Optional.of("outside-scope"));
        doAnswer(call -> {
            ResultSet row = mock(ResultSet.class);
            when(row.getLong(1)).thenReturn(1L);
            when(row.getString(2)).thenReturn("PUBLISHED");
            when(row.getLong(3)).thenReturn(2L);
            when(row.getLong(4)).thenReturn(1L);
            RowMapper<?> mapping = call.getArgument(1);
            return List.of(mapping.mapRow(row, 0));
        }).when(jdbc).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), org.mockito.ArgumentMatchers.eq(batchId));
        doAnswer(call -> {
            ResultSet row = mock(ResultSet.class);
            when(row.getObject(1, UUID.class)).thenReturn(employeeId);
            when(row.getDate(2)).thenReturn(java.sql.Date.valueOf("2026-11-01"));
            when(row.getObject(3, UUID.class)).thenReturn(documentId);
            RowMapper<?> mapping = call.getArgument(1);
            return List.of(mapping.mapRow(row, 0));
        }).when(jdbc).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), org.mockito.ArgumentMatchers.eq(2L));

        assertThrows(ResourceNotFoundException.class,
            () -> service.verifyReplay("PAYSLIP_BATCH_PUBLISH", payload, "{}", caller));
    }

    @Test
    void replayRejectsBatchWithoutEmployeeMappings() {
        UUID batchId = UUID.randomUUID();
        JsonNode payload = mock(JsonNode.class);
        when(payload.toString()).thenReturn("{}");
        when(mapper.readValue(any(String.class), org.mockito.ArgumentMatchers.eq(Mutation.class)))
            .thenReturn(new Mutation(batchId, null, 1L));
        doAnswer(call -> {
            ResultSet row = mock(ResultSet.class);
            when(row.getLong(1)).thenReturn(1L);
            when(row.getString(2)).thenReturn("PUBLISHED");
            when(row.getLong(3)).thenReturn(2L);
            when(row.getLong(4)).thenReturn(1L);
            RowMapper<?> mapping = call.getArgument(1);
            return List.of(mapping.mapRow(row, 0));
        }).when(jdbc).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), org.mockito.ArgumentMatchers.eq(batchId));
        doReturn(List.of()).when(jdbc).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
            org.mockito.ArgumentMatchers.eq(2L));

        assertThrows(ResourceNotFoundException.class,
            () -> service.verifyReplay("PAYSLIP_BATCH_PUBLISH", payload, "{}", mock(Authentication.class)));
        verifyNoInteractions(access, documents);
    }

    @Test
    void batchDetailRechecksAuthorityBeforeReturningItems() {
        UUID batchId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        Authentication caller = mock(Authentication.class);
        UserAccount actor = mock(UserAccount.class);
        Employee employee = mock(Employee.class);
        when(actor.getId()).thenReturn(10L);
        when(employee.getId()).thenReturn(20L);
        when(access.requireActive(caller, clock.instant())).thenReturn(actor, actor)
            .thenThrow(new AccessDeniedException("cutoff"));
        when(access.hasScopedRole(10L, "FINANCE_PAYROLL", "unit", clock.instant())).thenReturn(true);
        when(employees.requireInternalByPublicId(employeeId)).thenReturn(employee);
        when(employees.currentOrgUnit(20L, clock.instant())).thenReturn(Optional.of("unit"));
        doAnswer(call -> {
            ResultSet row = mock(ResultSet.class);
            when(row.getLong(1)).thenReturn(1L);
            when(row.getString(2)).thenReturn("DRAFT");
            when(row.getLong(3)).thenReturn(2L);
            when(row.getLong(4)).thenReturn(1L);
            RowMapper<?> mapping = call.getArgument(1);
            return List.of(mapping.mapRow(row, 0));
        }).when(jdbc).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), org.mockito.ArgumentMatchers.eq(batchId));
        doAnswer(call -> {
            ResultSet row = mock(ResultSet.class);
            when(row.getObject(1, UUID.class)).thenReturn(employeeId);
            when(row.getDate(2)).thenReturn(java.sql.Date.valueOf("2026-11-01"));
            when(row.getObject(3, UUID.class)).thenReturn(documentId);
            RowMapper<?> mapping = call.getArgument(1);
            return List.of(mapping.mapRow(row, 0));
        }).when(jdbc).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), org.mockito.ArgumentMatchers.eq(2L));

        assertThrows(AccessDeniedException.class, () -> service.detail(batchId, caller));
    }

    @Test
    void detailRechecksTheCurrentBatchAfterConcurrentScopeChange() {
        UUID batchId = UUID.randomUUID();
        UUID permittedId = UUID.randomUUID();
        UUID outsideId = UUID.randomUUID();
        Authentication caller = mock(Authentication.class);
        UserAccount actor = mock(UserAccount.class);
        Employee permitted = mock(Employee.class);
        Employee outside = mock(Employee.class);
        when(actor.getId()).thenReturn(10L);
        when(permitted.getId()).thenReturn(20L);
        when(outside.getId()).thenReturn(30L);
        when(access.requireActive(caller, clock.instant())).thenReturn(actor);
        when(employees.requireInternalByPublicId(permittedId)).thenReturn(permitted);
        when(employees.requireInternalByPublicId(outsideId)).thenReturn(outside);
        when(employees.currentOrgUnit(20L, clock.instant())).thenReturn(Optional.of("UNIT-A"));
        when(employees.currentOrgUnit(30L, clock.instant())).thenReturn(Optional.of("UNIT-B"));
        when(access.hasScopedRole(10L, HrisRole.FINANCE_PAYROLL, "UNIT-A", clock.instant())).thenReturn(true);
        AtomicInteger reads = new AtomicInteger();
        doAnswer(call -> {
            int revision = reads.incrementAndGet();
            ResultSet row = mock(ResultSet.class);
            when(row.getLong(1)).thenReturn(1L);
            when(row.getString(2)).thenReturn("DRAFT");
            when(row.getLong(3)).thenReturn((long) revision);
            when(row.getLong(4)).thenReturn((long) revision);
            RowMapper<?> mapping = call.getArgument(1);
            return List.of(mapping.mapRow(row, 0));
        }).when(jdbc).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), org.mockito.ArgumentMatchers.eq(batchId));
        doAnswer(call -> {
            long revision = call.getArgument(2);
            ResultSet row = mock(ResultSet.class);
            when(row.getObject(1, UUID.class)).thenReturn(revision == 1 ? permittedId : outsideId);
            when(row.getDate(2)).thenReturn(java.sql.Date.valueOf("2026-11-01"));
            when(row.getObject(3, UUID.class)).thenReturn(UUID.randomUUID());
            RowMapper<?> mapping = call.getArgument(1);
            return List.of(mapping.mapRow(row, 0));
        }).when(jdbc).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), org.mockito.ArgumentMatchers.anyLong());

        assertThrows(ResourceNotFoundException.class, () -> service.detail(batchId, caller));
    }
}
