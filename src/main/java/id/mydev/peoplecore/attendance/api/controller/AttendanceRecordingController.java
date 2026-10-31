package id.mydev.peoplecore.attendance.api.controller;

import id.mydev.peoplecore.attendance.api.dto.AttendanceRecordingDto.ClockRequest;
import id.mydev.peoplecore.attendance.api.dto.AttendanceRecordingDto.CreateCorrectionRequest;
import id.mydev.peoplecore.attendance.api.dto.AttendanceRecordingDto.CorrectionActionRequest;
import id.mydev.peoplecore.attendance.api.mapper.AttendanceRecordingDtoMapper;
import id.mydev.peoplecore.attendance.application.command.AttendanceCommands;
import id.mydev.peoplecore.attendance.application.command.AttendanceResults.ClockResult;
import id.mydev.peoplecore.attendance.application.command.AttendanceResults.CorrectionResult;
import id.mydev.peoplecore.attendance.application.service.AttendanceCorrectionService;
import id.mydev.peoplecore.common.api.ApiData;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/attendance")
public class AttendanceRecordingController {
    private static final Map<String,String> ACTIONS=Map.ofEntries(
        Map.entry("edit","EDIT"),Map.entry("discard","DISCARD"),Map.entry("submit","SUBMIT"),
        Map.entry("resubmit","RESUBMIT"),Map.entry("withdraw","WITHDRAW"),Map.entry("approve","APPROVE"),
        Map.entry("reject","REJECT"),Map.entry("return","RETURN"),Map.entry("reassign","REASSIGN"),
        Map.entry("retry-route","ROUTE_RETRY"));
    private final AttendanceCommands commands;
    private final AttendanceCorrectionService corrections;
    private final AttendanceRecordingDtoMapper mapper;
    private final Clock clock;
    public AttendanceRecordingController(AttendanceCommands commands,AttendanceCorrectionService corrections,
        AttendanceRecordingDtoMapper mapper,Clock clock) {
        this.commands=commands;this.corrections=corrections;this.mapper=mapper;this.clock=clock;
    }
    @PostMapping("/clock-actions")
    public ApiData<ClockResult> clock(@Valid @RequestBody ClockRequest request,
        @RequestHeader("Idempotency-Key") String key,Authentication caller) {
        Instant receivedAt=Instant.now(clock);
        return ApiData.of(commands.clock(caller,key,mapper.clock(request),receivedAt));
    }
    @PostMapping("/corrections")
    public ApiData<CorrectionResult> create(@Valid @RequestBody CreateCorrectionRequest request,
        @RequestHeader("Idempotency-Key") String key,Authentication caller) {
        return ApiData.of(commands.create(caller,key,mapper.create(request)));
    }
    @GetMapping("/corrections/{id}")
    public ApiData<CorrectionResult> get(@PathVariable UUID id,Authentication caller) {
        return ApiData.of(corrections.get(id,caller));
    }
    @GetMapping("/corrections")
    public ApiData<AttendanceCorrectionService.CorrectionPage> list(
        @org.springframework.web.bind.annotation.RequestParam UUID employeeId,
        @org.springframework.web.bind.annotation.RequestParam java.time.LocalDate from,
        @org.springframework.web.bind.annotation.RequestParam java.time.LocalDate to,
        @org.springframework.web.bind.annotation.RequestParam(required=false) String status,
        @org.springframework.web.bind.annotation.RequestParam(defaultValue="0") int page,
        @org.springframework.web.bind.annotation.RequestParam(defaultValue="20") int size,Authentication caller) {
        return ApiData.of(corrections.list(employeeId,from,to,status,page,size,caller));
    }
    @GetMapping("/corrections/{id}/history")
    public ApiData<AttendanceCorrectionService.HistoryPage> history(@PathVariable UUID id,
        @org.springframework.web.bind.annotation.RequestParam(defaultValue="REVISIONS") String kind,
        @org.springframework.web.bind.annotation.RequestParam(required=false) UUID after,
        @org.springframework.web.bind.annotation.RequestParam(defaultValue="20") int size,Authentication caller) {
        return ApiData.of(corrections.history(id,kind,after,size,caller));
    }
    @PostMapping("/corrections/{id}/{action}")
    public ApiData<CorrectionResult> action(@PathVariable UUID id,@PathVariable String action,
        @Valid @RequestBody CorrectionActionRequest request,@RequestHeader("Idempotency-Key") String key,
        Authentication caller) {
        String operation=ACTIONS.get(action);
        if (operation==null) throw new id.mydev.peoplecore.common.api.ResourceNotFoundException();
        return ApiData.of(commands.act(caller,key,operation,mapper.action(id,request)));
    }
}
