package id.mydev.peoplecore.common.infrastructure.persistence;

import id.mydev.peoplecore.common.reporting.ReportLimitException;
import id.mydev.peoplecore.common.reporting.ReportSnapshotClock;
import java.sql.Timestamp;
import java.time.Instant;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcReportSnapshotClock implements ReportSnapshotClock {
    private final DataSource source;
    public JdbcReportSnapshotClock(DataSource source) { this.source=source; }
    @Override
    public Instant snapshotTime(long deadline) {
        if(System.nanoTime()>=deadline) throw new ReportLimitException("Report execution deadline exceeded");
        JdbcTemplate jdbc=new JdbcTemplate(source);
        jdbc.setQueryTimeout(2);
        Timestamp timestamp=jdbc.queryForObject("SELECT CURRENT_TIMESTAMP",Timestamp.class);
        if(timestamp==null) throw new IllegalStateException("Database snapshot time unavailable");
        return timestamp.toInstant();
    }
}
