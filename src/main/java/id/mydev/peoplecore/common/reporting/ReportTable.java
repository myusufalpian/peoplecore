package id.mydev.peoplecore.common.reporting;

import java.util.List;

public record ReportTable(List<String> columns, List<List<String>> rows) {
    public ReportTable {
        columns = List.copyOf(columns);
        rows = rows.stream().map(List::copyOf).toList();
    }
}
