package id.mydev.peoplecore.identity.api.controller;

import id.mydev.peoplecore.common.api.ApiData;
import id.mydev.peoplecore.common.security.CurrentCaller;
import id.mydev.peoplecore.identity.application.service.NotificationDestinationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/enrollments/employees/{id}/notification-destination")
@ConditionalOnProperty(name="peoplecore.notifications.enabled", havingValue="true")
public class NotificationDestinationController {
    private final NotificationDestinationService service;
    public NotificationDestinationController(NotificationDestinationService service) { this.service=service; }
    @PutMapping
    public ApiData<Verified> verify(@PathVariable("id") UUID employeeId, @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Request request) {
        var result=service.verify(employeeId, request.address(), request.evidenceRef(), request.reason(),key,CurrentCaller.require());
        return ApiData.of(new Verified(result.id(),result.version(),result.status()));
    }
    public record Request(@NotBlank @Email @Size(max=254) String address,
                          @NotBlank @Size(max=255) String evidenceRef, @NotBlank @Size(max=1000) String reason) { }
    public record Verified(UUID id, long version, String status) { }
}
