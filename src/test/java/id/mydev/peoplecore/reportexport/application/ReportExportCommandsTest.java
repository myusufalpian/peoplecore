package id.mydev.peoplecore.reportexport.application;

import id.mydev.peoplecore.common.command.CommandExecutionService;
import id.mydev.peoplecore.common.command.InvalidIdempotencyKeyException;
import id.mydev.peoplecore.common.command.CommandExecutionService.CommandContext;
import id.mydev.peoplecore.common.command.CommandExecutionService.AuditDescriptor;
import id.mydev.peoplecore.common.command.CommandExecutionService.OutboxDescriptor;
import id.mydev.peoplecore.reporting.application.ReportRequest;
import id.mydev.peoplecore.reporting.application.ReportType;
import id.mydev.peoplecore.reporting.application.ScopedReportService;
import id.mydev.peoplecore.reportexport.application.ReportExportCommands.Request;
import id.mydev.peoplecore.reportexport.application.ReportExportService.Summary;
import id.mydev.peoplecore.support.JwtAuthFixture;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReportExportCommandsTest {
    private static final Instant NOW=Instant.parse("2026-11-13T10:00:00Z");
    private final CommandExecutionService commands=mock(CommandExecutionService.class);
    private final ReportExportService exports=mock(ReportExportService.class);
    private final ScopedReportService reports=mock(ScopedReportService.class);
    private final JsonMapper mapper=JsonMapper.builder().build();
    private final ReportExportCommands facade=new ReportExportCommands(commands,exports,reports,mapper);
    private final Authentication caller=JwtAuthFixture.token("https://identity.example","requester");
    private final ReportRequest report=ReportRequest.of(ReportType.ATTENDANCE,LocalDate.of(2026,11,1),LocalDate.of(2026,11,14));

    @SuppressWarnings("unchecked")
    @Test
    void acceptedRequestUsesAtomicReceiptAuditAndMetadataOnlyOutbox() {
        when(exports.create(any(UUID.class),eq(report),eq(caller))).thenAnswer(invocation ->
            new Summary(invocation.getArgument(0),"PENDING",NOW,NOW.plusSeconds(86400),null,null));
        when(commands.executeCommand(any(CommandContext.class),any(),eq(Summary.class),any(),any())).thenAnswer(invocation->{
            CommandContext context=invocation.getArgument(0);
            assertEquals(ReportExportCommands.CREATE,context.commandType());
            assertEquals("key",context.idempotencyKey());
            Supplier<Summary> action=invocation.getArgument(1);
            Summary result=action.get();
            Function<Summary,AuditDescriptor> audit=invocation.getArgument(3);
            Function<Summary,OutboxDescriptor> outbox=invocation.getArgument(4);
            assertEquals(result.publicId(),audit.apply(result).aggregatePublicId());
            assertEquals("EXPORT_REQUESTED",outbox.apply(result).eventType());
            assertEquals("{}",outbox.apply(result).payload());
            assertEquals(result.publicId(),outbox.apply(result).aggregatePublicId());
            return result;
        });
        Summary first=facade.create(caller,"key",new Request(report,"CSV"));
        Summary sameIdentity=facade.create(caller,"key",new Request(report,"CSV"));
        assertEquals(first.publicId(),sameIdentity.publicId());
        assertEquals("PENDING",first.status());
    }

    @Test
    void unsupportedFormatMissingReportAndMissingKeyNeverReachTheCommandBoundary() {
        assertThrows(InvalidIdempotencyKeyException.class,()->facade.create(caller," ",new Request(report,"CSV")));
        assertThrows(IllegalArgumentException.class,()->facade.create(caller,"key",null));
        assertThrows(IllegalArgumentException.class,()->facade.create(caller,"key",new Request(null,"CSV")));
        assertThrows(IllegalArgumentException.class,()->facade.create(caller,"key",new Request(report,"XLSX")));
        verifyNoInteractions(commands,exports,reports);
    }

    @Test
    void commandAdmissionRechecksCurrentReportAuthorityAndReplayDoesNotCreateAnotherJob() {
        UUID id=UUID.randomUUID();
        var payload=mapper.readTree("{\"publicId\":\""+id+"\",\"request\":"+mapper.writeValueAsString(new Request(report,"CSV"))+"}");
        facade.verifyCommand(payload,caller);
        verify(reports).authorize(caller,report);
        facade.verifyReplay(payload,caller);
        verify(exports).detail(id,caller);
        verify(exports,never()).create(any(UUID.class),any(ReportRequest.class),any(Authentication.class));
        doThrow(new AccessDeniedException("scope removed")).when(reports).authorize(caller,report);
        assertThrows(AccessDeniedException.class,()->facade.verifyCommand(payload,caller));
    }

    @Test
    void malformedStoredCommandFailsExplicitlyInsteadOfBeingReplayed() {
        for(String payload:new String[]{"null","[]","{}","{\"publicId\":\""+UUID.randomUUID()+"\",\"request\":null}"}) {
            assertThrows(IllegalArgumentException.class,()->facade.verifyCommand(mapper.readTree(payload),caller));
        }
        assertThrows(IllegalArgumentException.class,()->facade.verifyReplay(null,caller));
    }
}
