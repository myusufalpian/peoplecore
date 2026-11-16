package id.mydev.peoplecore.identity.application.service;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.identity.domain.repository.ReportAuthorizationRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class ReportAuthorizationService {
    private final AccountAccessService access;
    private final ReportAuthorizationRepository repository;
    public ReportAuthorizationService(AccountAccessService access,ReportAuthorizationRepository repository) {
        this.access=access; this.repository=repository;
    }

    public long authorize(Authentication caller,String type,UUID employee,String unit,Instant now,long deadline) {
        UserAccount account=access.requireActive(caller,now);
        List<String> roles=access.activeRoles(account.getId(),now);
        boolean audit="AUDIT".equals(type);
        boolean finance=List.of("REIMBURSEMENTS","PAYSLIP_STATUS","AUDIT","REQUEST_STATUS").contains(type);
        boolean manager=!List.of("PAYSLIP_STATUS","AUDIT").contains(type);
        boolean operations=false;
        if (audit && roles.contains(HrisRole.SYSTEM_ADMIN)) {
            try { access.requireIdentityAuthority(account.getId(),now); operations=true; }
            catch (AccessDeniedException denied) { operations=false; }
        }
        boolean allowed=operations || roles.contains(HrisRole.HR_ADMIN) || (finance&&roles.contains(HrisRole.FINANCE_PAYROLL))
            || (manager&&roles.contains(HrisRole.MANAGER))
            || (!audit&&!roles.isEmpty()&&access.ownEmployeePublicId(account).isPresent());
        if (!allowed) throw new AccessDeniedException("No report authority");
        if ((employee!=null||unit!=null)&&!repository.containsTarget(account.getId(),type,employee,unit,now,deadline))
            throw new ResourceNotFoundException();
        return account.getId();
    }
}
