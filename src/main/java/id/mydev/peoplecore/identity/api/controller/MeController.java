package id.mydev.peoplecore.identity.api.controller;

import id.mydev.peoplecore.common.api.ApiData;
import id.mydev.peoplecore.common.security.CurrentCaller;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import id.mydev.peoplecore.identity.api.dto.AccessDto;
import id.mydev.peoplecore.identity.api.mapper.AccessDtoMapper;
@RestController
@RequestMapping("/api/v1/me")
public class MeController {
    private final AccountAccessService access;

    public MeController(AccountAccessService access) {
        this.access = access;
    }

    @GetMapping("/access")
    public ResponseEntity<ApiData<AccessDto.AccessResponse>> access() {
        var snapshot = access.describeAccess(CurrentCaller.require());
        return ResponseEntity.ok(ApiData.of(AccessDtoMapper.response(snapshot)));
    }
}
