package id.mydev.peoplecore.reportexport.application;

import id.mydev.peoplecore.common.command.CommandExecutionService;
import id.mydev.peoplecore.common.command.CommandExecutionService.CommandContext;
import id.mydev.peoplecore.common.command.CommandExecutionService.AuditDescriptor;
import id.mydev.peoplecore.common.command.CommandExecutionService.OutboxDescriptor;
import id.mydev.peoplecore.common.command.CommandIds;
import id.mydev.peoplecore.common.command.CommandRetry;
import id.mydev.peoplecore.common.command.IdempotencyKeys;
import id.mydev.peoplecore.reporting.application.ReportRequest;
import id.mydev.peoplecore.reporting.application.ScopedReportService;
import id.mydev.peoplecore.reportexport.application.ReportExportService.Summary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class ReportExportCommands {
    public static final String CREATE = "REPORT_EXPORT_CREATE";
    private final CommandExecutionService commands;
    private final ReportExportService exports;
    private final ScopedReportService reports;
    private final ObjectMapper mapper;

    public ReportExportCommands(CommandExecutionService commands,ReportExportService exports,
                                ScopedReportService reports,ObjectMapper mapper) {
        this.commands=commands; this.exports=exports; this.reports=reports; this.mapper=mapper;
    }

    public Summary create(Authentication caller,String key,Request request) {
        IdempotencyKeys.requireValid(key);
        if(request==null || !"CSV".equals(request.format()) || request.report()==null)
            throw new IllegalArgumentException("CSV report is required");
        UUID id=CommandIds.derive(CREATE,caller.getName(),key);
        Payload payload=new Payload(id,request);
        var context=new CommandContext(caller.getName(),CREATE,key,mapper.writeValueAsString(payload),Duration.ofDays(30));
        return CommandRetry.withRetry(() -> commands.executeCommand(context,
            () -> exports.create(id,request.report(),caller),Summary.class,
            result -> new AuditDescriptor(CREATE,"REPORT_EXPORT",null,id,null,"Report export requested","{}"),
            result -> new OutboxDescriptor("EXPORT_REQUESTED",1,"REPORT_EXPORT",null,id,"{}")));
    }

    public void verifyCommand(JsonNode payload,Authentication caller) {
        exports.requireAvailable();
        Payload parsed=parse(payload);
        reports.authorize(caller,parsed.request().report());
    }

    public void verifyReplay(JsonNode payload,Authentication caller) {
        Payload parsed=parse(payload);
        exports.detail(parsed.publicId(),caller);
    }

    private Payload parse(JsonNode payload) {
        if(payload==null || !payload.isObject()) throw new IllegalArgumentException("Export command payload is required");
        Payload parsed=mapper.readValue(payload.toString(),Payload.class);
        if(parsed.publicId()==null || parsed.request()==null || parsed.request().report()==null
            || !"CSV".equals(parsed.request().format())) throw new IllegalArgumentException("CSV report is required");
        return parsed;
    }

    public record Request(@NotNull @Valid ReportRequest report,@NotBlank String format) { }
    private record Payload(UUID publicId,Request request) { }
}
