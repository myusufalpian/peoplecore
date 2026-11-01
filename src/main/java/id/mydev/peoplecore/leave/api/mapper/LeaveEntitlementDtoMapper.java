package id.mydev.peoplecore.leave.api.mapper;

import id.mydev.peoplecore.leave.api.dto.LeaveEntitlementDto.GrantRequest;
import id.mydev.peoplecore.leave.application.service.LeaveEntitlementService.GrantPayload;
import org.springframework.stereotype.Component;

@Component
public class LeaveEntitlementDtoMapper {
    public GrantPayload toPayload(GrantRequest request) {
        return new GrantPayload(request.employeePublicId(), request.year(), request.halfUnits(),
            request.businessReference(), request.source(), request.reason());
    }
}
