package id.mydev.peoplecore.identity.api.mapper;

import id.mydev.peoplecore.identity.api.dto.EnrollmentDto;
import id.mydev.peoplecore.identity.application.command.EnrollmentResults.BindingResult;
import id.mydev.peoplecore.identity.application.command.EnrollmentResults.IssuedInvitation;

public final class EnrollmentDtoMapper {
    private EnrollmentDtoMapper() { }

    public static EnrollmentDto.InvitationResponse response(IssuedInvitation issued) {
        return new EnrollmentDto.InvitationResponse(
            issued.result().invitationId(),
            issued.result().employeeId(),
            issued.result().intendedIdentityRef(),
            issued.result().status(),
            issued.result().deliveryStatus(),
            issued.result().expiresAt(),
            issued.secret());
    }

    public static EnrollmentDto.BindingResponse response(BindingResult result) {
        return new EnrollmentDto.BindingResponse(
            result.employeeId(),
            result.issuer(),
            result.subject(),
            result.createdAt());
    }
}
