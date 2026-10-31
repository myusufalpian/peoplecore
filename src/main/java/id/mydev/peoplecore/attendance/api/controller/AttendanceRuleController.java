package id.mydev.peoplecore.attendance.api.controller;

import id.mydev.peoplecore.attendance.application.command.AttendanceCommands;
import id.mydev.peoplecore.attendance.application.service.AttendanceReviewerService.PublishRules;
import id.mydev.peoplecore.attendance.application.service.AttendanceReviewerService.ActivateRules;
import id.mydev.peoplecore.attendance.application.service.AttendanceReviewerService.RuleResult;
import id.mydev.peoplecore.common.api.ApiData;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/approval/attendance-rule-sets")
public class AttendanceRuleController {
    private final AttendanceCommands commands;
    public AttendanceRuleController(AttendanceCommands commands) { this.commands=commands; }
    @PostMapping
    public ApiData<RuleResult> publish(@RequestBody PublishRules payload,
        @RequestHeader("Idempotency-Key") String key,Authentication caller) {
        return ApiData.of(commands.publish(caller,key,payload));
    }
    @PostMapping("/activate")
    public ApiData<RuleResult> activate(@RequestBody ActivateRules payload,
        @RequestHeader("Idempotency-Key") String key,Authentication caller) {
        return ApiData.of(commands.activate(caller,key,payload));
    }
}
