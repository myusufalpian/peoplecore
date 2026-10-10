package id.mydev.peoplecore.identity.api.mapper;

import id.mydev.peoplecore.identity.application.service.AccountAccessService;

import id.mydev.peoplecore.identity.api.dto.AccessDto;
public final class AccessDtoMapper {
    private AccessDtoMapper() { }

    public static AccessDto.AccessResponse response(AccountAccessService.AccessSnapshot snapshot) {
        return new AccessDto.AccessResponse(
            snapshot.accountId(),
            snapshot.status(),
            snapshot.accessEndsAt(),
            snapshot.roles(),
            snapshot.employeeId(),
            snapshot.authorizationGeneration());
    }
}
