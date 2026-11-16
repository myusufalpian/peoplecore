package id.mydev.peoplecore.reporting.application;

import java.time.Instant;
import java.util.List;

public record ReportSnapshot(ReportType reportType, int reportVersion, String filterHash,
                             List<String> columns, List<List<String>> rows, Instant dataAsOf,
                             long authorizationGeneration, Instant nextBoundary) {
    public ReportSnapshot {
        columns = List.copyOf(columns);
        rows = rows.stream().map(List::copyOf).toList();
    }
}
