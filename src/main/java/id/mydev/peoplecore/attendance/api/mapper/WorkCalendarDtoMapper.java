package id.mydev.peoplecore.attendance.api.mapper;

import id.mydev.peoplecore.attendance.api.dto.WorkCalendarDto.AssignmentResponse;
import id.mydev.peoplecore.attendance.api.dto.WorkCalendarDto.HolidayRequest;
import id.mydev.peoplecore.attendance.api.dto.WorkCalendarDto.ImpactPreviewResponse;
import id.mydev.peoplecore.attendance.api.dto.WorkCalendarDto.OccurrenceListResponse;
import id.mydev.peoplecore.attendance.api.dto.WorkCalendarDto.OccurrenceResponse;
import id.mydev.peoplecore.attendance.api.dto.WorkCalendarDto.ScheduleDayRequest;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.HolidayPayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarPayloads.ScheduleDayPayload;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarResults.AssignmentResult;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarResults.ImpactPreview;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarResults.OccurrenceListResult;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarResults.OccurrenceView;
import id.mydev.peoplecore.attendance.application.command.WorkCalendarResults.RevisionResult;
import id.mydev.peoplecore.attendance.api.dto.WorkCalendarDto.RevisionResponse;

import java.util.ArrayList;
import java.util.List;

public final class WorkCalendarDtoMapper {

    private WorkCalendarDtoMapper() {
    }

    public static ScheduleDayPayload dayPayload(ScheduleDayRequest request) {
        return new ScheduleDayPayload(request.weekday(), request.startTime(),
            request.endTime(), request.overnightOffset());
    }

    public static HolidayPayload holidayPayload(HolidayRequest request) {
        return new HolidayPayload(request.holidayDay(), request.holidayName());
    }

    public static RevisionResponse revision(RevisionResult result) {
        return new RevisionResponse(result.publicId(), result.status(), result.publishedAt());
    }

    public static AssignmentResponse assignment(AssignmentResult result) {
        return new AssignmentResponse(result.publicId(), result.employeePublicId(),
            result.revisionPublicId(), result.fromDate(), result.toDate());
    }

    public static ImpactPreviewResponse preview(ImpactPreview result) {
        return new ImpactPreviewResponse(result.affectedEmployees(), result.affectedDates(),
            result.pinnedOccurrences(), result.fingerprint());
    }

    public static OccurrenceListResponse occurrences(OccurrenceListResult result) {
        List<OccurrenceResponse> rows = new ArrayList<>(result.occurrences().size());
        for (OccurrenceView view : result.occurrences()) {
            rows.add(occurrence(view));
        }
        return new OccurrenceListResponse(List.copyOf(rows));
    }

    public static OccurrenceResponse occurrence(OccurrenceView view) {
        return new OccurrenceResponse(view.publicId(), view.employeePublicId(), view.workDate(),
            view.startAt(), view.endAt(), view.timezone(), view.holiday(), view.holidayName(),
            view.pinned(), view.blockedReason());
    }
}
