package id.mydev.peoplecore.reportexport.application;

import id.mydev.peoplecore.notification.domain.NotificationResolver;
import id.mydev.peoplecore.notification.domain.NotificationModels.Intent;
import id.mydev.peoplecore.notification.domain.NotificationModels.Recipient;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ExportNotificationResolver implements NotificationResolver {
    private static final Set<String> TYPES=Set.of("EXPORT_READY","EXPORT_FAILED","EXPORT_EXPIRED","EXPORT_INVALIDATED");
    private final ReportExportService exports;
    public ExportNotificationResolver(ReportExportService exports) { this.exports=exports; }
    @Override
    public boolean supports(String type) { return TYPES.contains(type); }
    @Override
    @Transactional(readOnly=true)
    public List<Recipient> resolve(String type,UUID id,Instant occurredAt) {
        var employee=exports.notificationEmployee(id);
        var account=exports.notificationAccount(id);
        if(employee.isEmpty() || account.isEmpty()) return List.of();
        return List.of(new Recipient(employee.get(),account.get(),"EXPORT_STATUS","EXPORT",id));
    }
    @Override
    @Transactional(readOnly=true)
    public boolean eligible(Intent intent,Instant now) {
        return "EXPORT".equals(intent.eligibilityType()) && intent.assignmentId()!=null
            && exports.notificationEligible(intent.assignmentId(),intent.accountId());
    }
}
