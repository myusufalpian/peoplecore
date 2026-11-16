package id.mydev.peoplecore.identity.application.service;

import id.mydev.peoplecore.identity.domain.InvitationCipher;
import id.mydev.peoplecore.identity.domain.repository.NotificationIdentityRepository;
import id.mydev.peoplecore.notification.domain.NotificationModels.Destination;
import id.mydev.peoplecore.notification.domain.NotificationModels.Intent;
import id.mydev.peoplecore.notification.domain.NotificationModels.Recipient;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name="peoplecore.notifications.enabled",havingValue="true")
public class IdentityNotificationAccess {
    private final NotificationIdentityRepository repository;
    private final AccountAccessService access;
    private final InvitationCipher cipher;
    private final Clock clock;
    private final EmployeeService employees;
    public IdentityNotificationAccess(NotificationIdentityRepository repository,AccountAccessService access,
                                       InvitationCipher cipher,Clock clock,EmployeeService employees) {
        this.repository=repository; this.access=access; this.cipher=cipher; this.clock=clock; this.employees=employees;
    }
    @Transactional(readOnly=true)
    public Optional<Destination> destination(UUID employee) { return repository.currentDestination(employee).filter(d->"VERIFIED".equals(d.status())); }
    @Transactional(readOnly=true)
    public boolean accountEligible(Intent intent,Instant now) {
        if("INVITATION".equals(intent.eligibilityType())) return true;
        if(intent.accountId()==null) return false;
        if("HR_RECOVERY".equals(intent.eligibilityType()))
            return recoveryRecipient(intent.assignmentId(),intent.accountId(),now).isPresent();
        return access.findAccountByPublicId(intent.accountId()).filter(a->a.isActiveAt(now))
            .filter(a->!access.activeRoles(a.getId(),now).isEmpty())
            .filter(a->access.ownEmployeePublicId(a).filter(intent.employeeId()::equals).isPresent()).isPresent();
    }
    @Transactional(readOnly=true)
    public Optional<Recipient> recoveryRecipient(UUID targetEmployee,UUID accountId,Instant now) {
        var target=employees.requireInternalByPublicId(targetEmployee);
        String unit=employees.currentOrgUnit(target.getId(),now).orElse(null);
        return access.findAccountByPublicId(accountId).filter(a->a.isActiveAt(now))
            .filter(a->access.hasScopedRole(a.getId(),"HR_ADMIN",unit,now))
            .flatMap(a->access.ownEmployeePublicId(a).map(employee->new Recipient(employee,a.getPublicId(),
                "REVIEW_BLOCKED","HR_RECOVERY",targetEmployee)));
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Optional<InvitationContent> invitation(UUID invitation,UUID destination,long destinationVersion) {
        return repository.lockInvitation(invitation,clock.instant())
            .filter(row->row.destination().id().equals(destination)&&row.destination().version()==destinationVersion)
            .map(row->new InvitationContent(row.destination().address(),
                cipher.decrypt(invitation,destination,row.ciphertext(),row.keyId())));
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean invitationEligible(UUID invitation,UUID destination,long destinationVersion) {
        return repository.lockInvitation(invitation,clock.instant())
            .filter(row->row.destination().id().equals(destination)&&row.destination().version()==destinationVersion).isPresent();
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean lockAcceptance(UUID invitation,UUID employee,UUID destination,long destinationVersion) {
        return repository.lockInvitationAcceptance(invitation,employee,destination,destinationVersion);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void accepted(UUID invitation) { repository.markInvitationAccepted(invitation); }
    @Transactional(propagation=Propagation.MANDATORY)
    public void suppress(UUID destination) { repository.suppressDestination(destination); }
    @Transactional(propagation=Propagation.MANDATORY)
    public void cleanup() { repository.cleanupInvalidInvitations(clock.instant()); }
    public record InvitationContent(String address,String secret) {
        @Override public String toString() { return "InvitationContent[redacted]"; }
    }
}
