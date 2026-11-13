package id.mydev.peoplecore.leave.application.service;

import id.mydev.peoplecore.leave.domain.repository.LeaveWorkflowRepository;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.History;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.PortionView;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.TimelineEntry;
import id.mydev.peoplecore.leave.application.command.LeaveWorkflowModels.Summary;

final class LeaveWorkflowMapper {
    private LeaveWorkflowMapper() { }

    static History toHistory(LeaveWorkflowRepository.HistoryRow row) {
        return new History(row.revision(), row.step(), row.reviewerAccountPublicId(), row.outcome(), row.reason(), row.createdAt());
    }

    static PortionView toPortionView(LeaveWorkflowRepository.PortionViewRow row) {
        return new PortionView(row.workDate(), row.half(), row.startAt(), row.endAt());
    }

    static TimelineEntry toTimelineEntry(LeaveWorkflowRepository.TimelineEntryRow row) {
        return new TimelineEntry(row.publicId(), row.action(), row.revision(), row.cancellationPublicId(), row.payload(), row.createdAt());
    }

    static Summary toSummary(LeaveWorkflowRepository.SummaryRow row) {
        return new Summary(row.publicId(), row.requestPublicId(), row.workDate(), row.half(), row.startAt(), row.endAt(), row.status());
    }
}
