package id.mydev.peoplecore.attendance.application.command;

import id.mydev.peoplecore.attendance.application.command.AttendancePayloads.ClockPayload;
import id.mydev.peoplecore.attendance.application.command.AttendancePayloads.CorrectionAction;
import id.mydev.peoplecore.attendance.application.command.AttendancePayloads.CorrectionPayload;
import id.mydev.peoplecore.attendance.application.command.AttendanceResults.ClockResult;
import id.mydev.peoplecore.attendance.application.command.AttendanceResults.CorrectionResult;
import id.mydev.peoplecore.attendance.application.service.AttendanceCorrectionService;
import id.mydev.peoplecore.attendance.application.service.AttendanceRecordingService;
import id.mydev.peoplecore.attendance.application.service.AttendanceReviewerService;
import id.mydev.peoplecore.attendance.application.service.AttendanceReviewerService.PublishRules;
import id.mydev.peoplecore.attendance.application.service.AttendanceReviewerService.ActivateRules;
import id.mydev.peoplecore.attendance.application.service.AttendanceReviewerService.RuleResult;
import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.command.CommandExecutionService;
import id.mydev.peoplecore.common.command.CommandExecutionService.CommandContext;
import id.mydev.peoplecore.common.command.CommandExecutionService.AuditDescriptor;
import id.mydev.peoplecore.common.command.CommandExecutionService.OutboxDescriptor;
import id.mydev.peoplecore.common.command.CommandIds;
import id.mydev.peoplecore.common.command.CommandRetry;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

@Component
public class AttendanceCommands {
    private static final Duration RECEIPT_TTL=Duration.ofDays(7);
    private final AttendanceRecordingService recording;
    private final AttendanceCorrectionService corrections;
    private final AttendanceReviewerService reviewers;
    private final CommandExecutionService commands;
    private final ObjectMapper mapper;
    private final java.time.Clock clock;
    public AttendanceCommands(AttendanceRecordingService recording,AttendanceCorrectionService corrections,
        AttendanceReviewerService reviewers,CommandExecutionService commands,ObjectMapper mapper,java.time.Clock clock) {
        this.recording=recording;this.corrections=corrections;this.reviewers=reviewers;
        this.commands=commands;this.mapper=mapper;this.clock=clock;
    }

    public ClockResult clock(Authentication caller,String key,ClockPayload payload,Instant receivedAt) {
        return execute(caller,key,"ATTENDANCE_CLOCK",payload,()->recording.record(payload,caller,receivedAt),
            ClockResult.class,ClockResult::publicId);
    }
    public CorrectionResult create(Authentication caller,String key,CorrectionPayload payload) {
        UUID id=payload.publicId()==null ? CommandIds.derive("ATTENDANCE_CORRECTION_CREATE",caller.getName(),key)
            : payload.publicId();
        CorrectionPayload canonical=new CorrectionPayload(id,payload.workDate(),payload.proposedIn(),
            payload.proposedOut(),payload.reason());
        return execute(caller,key,"ATTENDANCE_CORRECTION_CREATE",canonical,()->corrections.create(canonical,caller),
            CorrectionResult.class,CorrectionResult::publicId);
    }
    public CorrectionResult act(Authentication caller,String key,String action,CorrectionAction payload) {
        return execute(caller,key,"ATTENDANCE_CORRECTION_"+action,payload,()->corrections.act(action,payload,caller),
            CorrectionResult.class,CorrectionResult::publicId);
    }
    public RuleResult publish(Authentication caller,String key,PublishRules payload) {
        UUID id=payload.publicId()==null ? CommandIds.derive("ATTENDANCE_RULE_PUBLISH",caller.getName(),key)
            : payload.publicId();
        PublishRules canonical=new PublishRules(id,payload.rules());
        return execute(caller,key,"ATTENDANCE_RULE_PUBLISH",canonical,()->reviewers.publish(canonical,caller),
            RuleResult.class,RuleResult::publicId);
    }
    public RuleResult activate(Authentication caller,String key,ActivateRules payload) {
        return execute(caller,key,"ATTENDANCE_RULE_ACTIVATE",payload,()->reviewers.activate(payload,caller),
            RuleResult.class,RuleResult::publicId);
    }
    public void verifyCommand(String type,tools.jackson.databind.JsonNode payload,Authentication caller) {
        verify(type,payload,null,caller,false);
    }
    public void verifyReplay(String type,tools.jackson.databind.JsonNode payload,String recordedResult,
                             Authentication caller) {
        verify(type,payload,recordedResult,caller,true);
    }
    private void verify(String type,tools.jackson.databind.JsonNode payload,String recordedResult,
                        Authentication caller,boolean replay) {
        if ("ATTENDANCE_RULE_PUBLISH".equals(type)) {
            reviewers.verifyPublication(caller,payload,Instant.now(clock));return;
        }
        if ("ATTENDANCE_RULE_ACTIVATE".equals(type)) {
            reviewers.requirePublisher(caller,Instant.now(clock));return;
        }
        if ("ATTENDANCE_CLOCK".equals(type) || "ATTENDANCE_CORRECTION_CREATE".equals(type)) {
            UUID currentEmployee;
            if (replay) {
                currentEmployee=corrections.verifyCreateReplay(caller);
                UUID recordedEmployee;
                try {
                    var result=mapper.readTree(recordedResult);
                    recordedEmployee=UUID.fromString(result.get("employeePublicId").asString());
                } catch (RuntimeException ex) {
                    throw new ResourceNotFoundException();
                }
                if (!currentEmployee.equals(recordedEmployee)) throw new ResourceNotFoundException();
            } else {
                corrections.verifyAction("ATTENDANCE_CORRECTION_CREATE",null,caller,false);
            }
            return;
        }
        var id=payload.get("correctionId");
        if (id==null || id.isNull()) throw new id.mydev.peoplecore.common.api.ResourceNotFoundException();
        corrections.verifyAction(type,UUID.fromString(id.asString()),caller,replay);
    }

    private <T> T execute(Authentication caller,String key,String type,Object payload,Supplier<T> action,
        Class<T> resultClass,Function<T,UUID> resource) {
        CommandContext context=new CommandContext(caller.getName(),type,key,mapper.writeValueAsString(payload),RECEIPT_TTL);
        return CommandRetry.withRetry(()->commands.executeCommand(context,action,resultClass,
            result->new AuditDescriptor(type,"ATTENDANCE",null,resource.apply(result),null,
                "Attendance command accepted",mapper.writeValueAsString(new Event(resource.apply(result),type))),
            result->new OutboxDescriptor(type,1,"ATTENDANCE",null,resource.apply(result),
                mapper.writeValueAsString(new Event(resource.apply(result),type)))));
    }
    private record Event(UUID resourceId,String action) { }
}
