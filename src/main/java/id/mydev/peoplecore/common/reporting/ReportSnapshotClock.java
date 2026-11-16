package id.mydev.peoplecore.common.reporting;

import java.time.Instant;

public interface ReportSnapshotClock {
    Instant snapshotTime(long deadlineNanos);
}
