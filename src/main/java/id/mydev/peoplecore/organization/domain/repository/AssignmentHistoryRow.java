package id.mydev.peoplecore.organization.domain.repository;

import java.time.Instant;
import java.util.UUID;

public record AssignmentHistoryRow(UUID assignmentId, String orgUnit, String jobLevel,
                                   Instant validFrom, Instant validTo, UUID managerId,
                                   Instant supersededAt, UUID supersedesId) { }
