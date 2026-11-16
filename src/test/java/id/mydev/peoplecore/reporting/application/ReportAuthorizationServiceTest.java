package id.mydev.peoplecore.reporting.application;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.application.service.ReportAuthorizationService;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.identity.domain.repository.ReportAuthorizationRepository;
import id.mydev.peoplecore.support.JwtAuthFixture;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportAuthorizationServiceTest {
    private static final Instant NOW=Instant.parse("2026-11-13T00:00:00Z");
    private final AccountAccessService access=mock(AccountAccessService.class);
    private final ReportAuthorizationRepository targets=mock(ReportAuthorizationRepository.class);
    private final Authentication caller=JwtAuthFixture.token("https://issuer.example","employee");
    private final UserAccount account=new UserAccount(UUID.randomUUID(),"https://issuer.example","employee",NOW);
    private final ReportAuthorizationService service=new ReportAuthorizationService(access,targets);

    private void roles(String... roles) {
        ReflectionTestUtils.setField(account,"id",1L);
        when(access.requireActive(caller,NOW)).thenReturn(account);
        when(access.activeRoles(1L,NOW)).thenReturn(List.of(roles));
    }

    @Test
    void anEmployeeBindingAllowsSelfReportsButNoAuditSearch() {
        roles("EMPLOYEE");
        when(access.ownEmployeePublicId(account)).thenReturn(Optional.of(UUID.randomUUID()));
        assertEquals(1,service.authorize(caller,"ATTENDANCE",null,null,NOW,1));
        assertThrows(AccessDeniedException.class,()->service.authorize(caller,"AUDIT",null,null,NOW,1));
    }

    @Test
    void targetedQueriesDenyForeignEmployeesBeforeExposingTheirExistence() {
        roles("MANAGER");
        UUID target=UUID.randomUUID();
        assertThrows(ResourceNotFoundException.class,()->service.authorize(caller,"ATTENDANCE",target,"FIN",NOW,1));
        when(targets.containsTarget(1,"ATTENDANCE",target,"ENG",NOW,1)).thenReturn(true);
        assertEquals(1,service.authorize(caller,"ATTENDANCE",target,"ENG",NOW,1));
    }

    @Test
    void financeHasOnlyApprovedReportFamiliesAndOperationsNeedsGlobalAuthority() {
        roles("FINANCE_PAYROLL");
        assertEquals(1,service.authorize(caller,"REIMBURSEMENTS",null,null,NOW,1));
        assertEquals(1,service.authorize(caller,"PAYSLIP_STATUS",null,null,NOW,1));
        assertEquals(1,service.authorize(caller,"AUDIT",null,null,NOW,1));
        assertThrows(AccessDeniedException.class,()->service.authorize(caller,"ATTENDANCE",null,null,NOW,1));
        roles("SYSTEM_ADMIN");
        assertEquals(1,service.authorize(caller,"AUDIT",null,null,NOW,1));
        doThrow(new AccessDeniedException("Scoped administrator")).when(access).requireIdentityAuthority(1L,NOW);
        assertThrows(AccessDeniedException.class,()->service.authorize(caller,"AUDIT",null,null,NOW,1));
    }

    @Test
    void missingEffectiveRolesOrInactiveAccountNeverGrantReportAccess() {
        roles();
        assertThrows(AccessDeniedException.class,()->service.authorize(caller,"ATTENDANCE",null,null,NOW,1));
        when(access.requireActive(caller,NOW)).thenThrow(new AccessDeniedException("Access ended"));
        assertThrows(AccessDeniedException.class,()->service.authorize(caller,"ATTENDANCE",null,null,NOW,1));
    }

    @ParameterizedTest
    @EnumSource(ReportType.class)
    void inactiveAccountIsRejectedForEveryCatalogReport(ReportType reportType) {
        when(access.requireActive(caller,NOW)).thenThrow(new AccessDeniedException("Account inactive"));
        assertThrows(AccessDeniedException.class,()->service.authorize(caller,reportType.name(),null,null,NOW,1));
    }
}
