package id.mydev.peoplecore.identity.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AccessDto {
    private AccessDto() { }

    public record AccessResponse(
        UUID accountId,
        String status,
        Instant accessEndsAt,
        List<String> roles,
        UUID employeeId,
        long authorizationGeneration
    ) { }
}
