package id.mydev.peoplecore.attendance.api.mapper;

import id.mydev.peoplecore.attendance.api.dto.AttendanceRecordingDto.ClockRequest;
import id.mydev.peoplecore.attendance.api.dto.AttendanceRecordingDto.CreateCorrectionRequest;
import id.mydev.peoplecore.attendance.api.dto.AttendanceRecordingDto.CorrectionActionRequest;
import id.mydev.peoplecore.attendance.application.command.AttendancePayloads.ClockPayload;
import id.mydev.peoplecore.attendance.application.command.AttendancePayloads.CorrectionPayload;
import id.mydev.peoplecore.attendance.application.command.AttendancePayloads.CorrectionAction;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class AttendanceRecordingDtoMapper {
    public ClockPayload clock(ClockRequest request) { return new ClockPayload(request.action()); }
    public CorrectionPayload create(CreateCorrectionRequest request) {
        return new CorrectionPayload(null,request.workDate(),request.proposedIn(),request.proposedOut(),request.reason());
    }
    public CorrectionAction action(UUID id,CorrectionActionRequest request) {
        return new CorrectionAction(id,request.expectedVersion(),request.revisionId(),request.expectedEvidenceId(),
            request.reviewerId(),request.reason(),request.proposedIn(),request.proposedOut());
    }
}
