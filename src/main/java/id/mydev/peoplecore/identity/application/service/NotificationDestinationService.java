package id.mydev.peoplecore.identity.application.service;

import id.mydev.peoplecore.common.audit.AuditEvent;
import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.common.command.CommandConflictException;
import id.mydev.peoplecore.common.command.IdempotencyKeys;
import tools.jackson.databind.ObjectMapper;
import id.mydev.peoplecore.common.audit.AuditEventWriter;
import id.mydev.peoplecore.identity.domain.repository.NotificationIdentityRepository;
import id.mydev.peoplecore.notification.domain.NotificationModels.Destination;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@ConditionalOnProperty(name="peoplecore.notifications.enabled", havingValue="true")
public class NotificationDestinationService {
    private final NotificationIdentityRepository repository;
    private final AccountAccessService access;
    private final EmployeeService employees;
    private final AuditEventWriter audit;
    private final Clock clock;
    private final AdmissionGuard admission;
    private final ObjectMapper mapper;
    public NotificationDestinationService(NotificationIdentityRepository repository, AccountAccessService access,
        EmployeeService employees, AuditEventWriter audit, Clock clock, AdmissionGuard admission, ObjectMapper mapper) {
        this.repository=repository; this.access=access; this.employees=employees; this.audit=audit; this.clock=clock; this.admission=admission; this.mapper=mapper;
    }
    @Transactional
    public Destination verify(UUID employeeId, String address, String evidence, String reason, String key, Authentication caller) {
        IdempotencyKeys.requireValid(key);
        admission.acquire(AdmissionGuard.Mode.EXCLUSIVE);
        if (!StringUtils.hasText(address) || address.length()>254 || address.contains("\r") || address.contains("\n")
            || !address.matches("[^@\\s]+@[^@\\s]+[.][^@\\s]+"))
            throw new IllegalArgumentException("A valid verified corporate address is required");
        if (!StringUtils.hasText(evidence) || evidence.length()>255 || !StringUtils.hasText(reason) || reason.length()>1000)
            throw new IllegalArgumentException("Verification evidence and reason are required");
        var employee=employees.requireInternalByPublicId(employeeId);
        employees.lockEmployee(employee.getId()).orElseThrow();
        Instant now=clock.instant();
        var operator=access.requireHrScope(caller, employees.currentOrgUnit(employee.getId(),now).orElse(null), now);
        access.requireIdentityAuthority(operator.getId(),now);
        String hash=EnrollmentService.sha256Hex(mapper.writeValueAsString(new Input(employeeId,address,evidence,reason)));
        var existing=repository.verificationOperation(caller.getName(),key);
        if(existing.isPresent()) {
            if(!hash.equals(existing.get().hash())) throw new CommandConflictException("Idempotency key has different content");
            return new Destination(existing.get().destinationId(),employeeId,"",existing.get().version(),"VERIFIED");
        }
        Destination result=repository.verifyDestination(employeeId,address.trim(),evidence.trim(),caller.getName(),now);
        repository.recordVerification(caller.getName(),key,hash,result,now);
        audit.append(new AuditEvent(UUID.randomUUID(),caller.getName(),"NOTIFICATION_DESTINATION_VERIFIED",
            "EMPLOYEE",null,employeeId,null,reason.trim(),"{\"destinationId\":\""+result.id()+"\"}",now));
        return result;
    }
    private record Input(UUID employeeId,String address,String evidence,String reason) { }
}
