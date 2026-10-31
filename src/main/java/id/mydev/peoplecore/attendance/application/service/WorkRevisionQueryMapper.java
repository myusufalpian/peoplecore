package id.mydev.peoplecore.attendance.application.service;

import id.mydev.peoplecore.attendance.domain.model.WorkScheduleRevision;
import id.mydev.peoplecore.attendance.domain.model.HolidayCalendarRevision;
import id.mydev.peoplecore.attendance.domain.model.WorkScheduleDay;
import id.mydev.peoplecore.attendance.domain.model.HolidayDate;

final class WorkRevisionQueryMapper {
    private WorkRevisionQueryMapper() { }
    static WorkRevisionQueryService.Summary summary(WorkScheduleRevision revision) {
        return new WorkRevisionQueryService.Summary(revision.getPublicId(), revision.getStatus(), revision.getPublishedAt());
    }
    static WorkRevisionQueryService.Summary summary(HolidayCalendarRevision revision) {
        return new WorkRevisionQueryService.Summary(revision.getPublicId(), revision.getStatus(), revision.getPublishedAt());
    }
    static WorkRevisionQueryService.Day day(WorkScheduleDay day) {
        return new WorkRevisionQueryService.Day(day.getWeekday(), day.getStartTime(), day.getEndTime(), day.getOvernightOffset());
    }
    static WorkRevisionQueryService.Holiday holiday(HolidayDate holiday) {
        return new WorkRevisionQueryService.Holiday(holiday.getHolidayDay(), holiday.getHolidayName());
    }
}
