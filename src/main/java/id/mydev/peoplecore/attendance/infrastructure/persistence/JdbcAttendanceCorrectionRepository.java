package id.mydev.peoplecore.attendance.infrastructure.persistence;

import id.mydev.peoplecore.attendance.domain.repository.AttendanceCorrectionRepository;
import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAttendanceCorrectionRepository implements AttendanceCorrectionRepository {
    private final JdbcTemplate jdbc;

    public JdbcAttendanceCorrectionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc;
    }
    @Override
    public Long countOpen(long employeeId, LocalDate workDate) {
        return jdbc.queryForObject("SELECT count(id) FROM attendance_correction WHERE employee_id=? "
            + "AND work_date=? AND status IN ('DRAFT','PENDING','RETURNED')", Long.class, employeeId, workDate);
    }
    @Override
    public void create(UUID publicId, long employeeId, UUID employeePublicId, LocalDate workDate,
        Instant proposedIn, Instant proposedOut, String reason, Instant createdAt) {
        jdbc.update("INSERT INTO attendance_correction(public_id,employee_id,employee_public_id,work_date,status,"
            + "proposed_in,proposed_out,reason,created_at) VALUES(?,?,?,?,'DRAFT',?,?,?,?)", publicId, employeeId, employeePublicId, workDate, ts(proposedIn), ts(proposedOut), reason, ts(createdAt));
    }
    @Override
    public void edit(Instant proposedIn, Instant proposedOut, String reason, long id) {
        jdbc.update("UPDATE attendance_correction SET proposed_in=?,proposed_out=?,reason=?,version=version+1 "
            + "WHERE id=?", ts(proposedIn), ts(proposedOut), reason, id);
    }
    @Override
    public void insertRevision(UUID publicId, long correctionId, long occurrenceId, UUID baselineId,
        Instant proposedIn, Instant proposedOut, String reason, UUID assignmentId, String orgUnit, Long reviewerId,
        Instant createdAt) {
        jdbc.update("INSERT INTO attendance_correction_revision(public_id,correction_id,occurrence_id,baseline_id,"
            + "proposed_in,proposed_out,reason,organization_assignment_id,org_unit,original_reviewer_id,created_at) "
            + "VALUES(?,?,?,?,?,?,?,?,?,?,?)", publicId, correctionId, occurrenceId, baselineId, ts(proposedIn), ts(proposedOut), reason, assignmentId, orgUnit, reviewerId, ts(createdAt));
    }
    @Override
    public void markPending(UUID revisionId, long id) {
        jdbc.update("UPDATE attendance_correction SET active_revision_id=?,status='PENDING',version=version+1 WHERE id=?", revisionId, id);
    }
    @Override
    public List<Submitted> findSubmitted(UUID revisionId) {
        return jdbc.query("SELECT occurrence_id,baseline_id,proposed_in,proposed_out FROM "
            + "attendance_correction_revision WHERE public_id=?", (rs,row)->new Submitted(
                rs.getLong("occurrence_id"),rs.getObject("baseline_id",UUID.class),
                rs.getTimestamp("proposed_in").toInstant(),instant(rs,"proposed_out")), revisionId);
    }
    @Override
    public void insertDecision(UUID publicId, UUID revisionId, UUID assignmentId, long reviewerId, String status,
        String reason, Instant createdAt) {
        jdbc.update("INSERT INTO attendance_correction_decision(public_id,revision_id,assignment_id,reviewer_id,"
            + "decision,reason,created_at) VALUES(?,?,?,?,?,?,?)", publicId, revisionId, assignmentId, reviewerId, status, reason, ts(createdAt));
    }
    @Override
    public void incrementVersion(long id) {
        jdbc.update("UPDATE attendance_correction SET version=version+1 WHERE id=?", id);
    }
    @Override
    public CorrectionRows list(long employeeId, LocalDate from, LocalDate to, String status, boolean owner,
        boolean hr, long reviewerId, int page, int size) {
        String predicate="employee_id=? AND work_date>=? AND work_date<=?";
        ArrayList<Object> args=new ArrayList<>(List.of(employeeId,from,to));
        if (status!=null) {
            predicate+=" AND status=?";
            args.add(status);
        }
        if (!owner && !hr) {
            predicate+=" AND status='PENDING' AND active_revision_id IN (SELECT a.revision_id "
                + "FROM attendance_correction_assignment a WHERE a.reviewer_id=? AND a.id=(SELECT max(b.id) "
                + "FROM attendance_correction_assignment b WHERE b.revision_id=a.revision_id))";
            args.add(reviewerId);
        }
        Long count=jdbc.queryForObject("SELECT count(id) FROM attendance_correction WHERE "+predicate,Long.class,args.toArray());
        args.add(size);args.add(Math.multiplyExact((long)page,size));
        List<Correction> rows= jdbc.query("SELECT " + COLUMNS + " FROM attendance_correction WHERE " + predicate
                + " ORDER BY created_at,public_id LIMIT ? OFFSET ?", this::map, args.toArray());
        return new CorrectionRows(rows, count);
    }
    @Override
    public List<HistoryRecord> history(String kind, long correctionId, UUID after, int size) {
        String sql = switch (kind) {
          case "REVISIONS" ->
              "SELECT public_id,proposed_in,proposed_out,reason,NULL AS decision,created_at FROM attendance_correction_revision "
                  + "WHERE correction_id=? AND id>COALESCE((SELECT id FROM attendance_correction_revision "
                  + "WHERE public_id=? AND correction_id=?),0) ORDER BY id LIMIT ?";
          case "DECISIONS" ->
              "SELECT d.public_id,NULL AS proposed_in,NULL AS proposed_out,d.reason,d.decision,d.created_at "
                  + "FROM attendance_correction_decision d JOIN attendance_correction_revision r ON r.public_id=d.revision_id "
                  + "WHERE r.correction_id=? AND d.id>COALESCE((SELECT d2.id FROM attendance_correction_decision d2 "
                  + "JOIN attendance_correction_revision r2 ON r2.public_id=d2.revision_id WHERE d2.public_id=? "
                  + "AND r2.correction_id=?),0) ORDER BY d.id LIMIT ?";
          case "ASSIGNMENTS" ->
              "SELECT a.public_id,NULL AS proposed_in,NULL AS proposed_out,a.reason,NULL AS decision,a.created_at "
                  + "FROM attendance_correction_assignment a JOIN attendance_correction_revision r ON r.public_id=a.revision_id "
                  + "WHERE r.correction_id=? AND a.id>COALESCE((SELECT a2.id FROM attendance_correction_assignment a2 "
                  + "JOIN attendance_correction_revision r2 ON r2.public_id=a2.revision_id WHERE a2.public_id=? "
                  + "AND r2.correction_id=?),0) ORDER BY a.id LIMIT ?";
          case null, default ->
              throw new IllegalArgumentException("History kind must be REVISIONS, DECISIONS or ASSIGNMENTS");
        };
        if (after!=null) requireHistoryCursor(kind,correctionId,after);
        List<HistoryRecord> rows=jdbc.query(sql,(rs,row)-> new HistoryRecord(rs.getObject("public_id",UUID.class),
            kind,instant(rs,"proposed_in"),instant(rs,"proposed_out"),rs.getString("reason"),rs.getString("decision"),
            rs.getTimestamp("created_at").toInstant()),correctionId,after,correctionId,size+1);
        return rows;
    }
    private void requireHistoryCursor(String kind,Long correctionId,UUID cursor) {
        String sql=switch(kind) {
            case "REVISIONS" -> "SELECT count(id) FROM attendance_correction_revision WHERE public_id=? AND correction_id=?";
            case "DECISIONS" -> "SELECT count(d.id) FROM attendance_correction_decision d JOIN attendance_correction_revision r "
                + "ON r.public_id=d.revision_id WHERE d.public_id=? AND r.correction_id=?";
            case "ASSIGNMENTS" -> "SELECT count(a.id) FROM attendance_correction_assignment a JOIN attendance_correction_revision r "
                + "ON r.public_id=a.revision_id WHERE a.public_id=? AND r.correction_id=?";
            default -> throw new IllegalArgumentException("History kind must be REVISIONS, DECISIONS or ASSIGNMENTS");
        };
        Integer count=jdbc.queryForObject(sql,Integer.class,cursor,correctionId);
        if (count==null || count==0) throw new ResourceNotFoundException();
    }

    @Override
    public Optional<Assignment> assignment(UUID revision) {
        if (revision==null) return Optional.empty();
        return jdbc.query("SELECT public_id,reviewer_id FROM attendance_correction_assignment WHERE revision_id=? "
            + "ORDER BY id DESC LIMIT 1",(rs,row)->new Assignment(rs.getObject("public_id",UUID.class),
                rs.getLong("reviewer_id")),revision).stream().findFirst();
    }
    @Override
    public void assign(UUID revision,Long reviewer,UUID previous,String reason,Instant now) {
        jdbc.update("INSERT INTO attendance_correction_assignment(public_id,revision_id,reviewer_id,previous_assignment_id,"
            + "reason,created_at) VALUES(?,?,?,?,?,?)",UUID.randomUUID(),revision,reviewer,previous,reason,ts(now));
    }
    @Override
    public Correction find(UUID id) {
        return jdbc.query("SELECT "+COLUMNS+" FROM attendance_correction WHERE public_id=?",
            this::map,id).stream().findFirst().orElseThrow(ResourceNotFoundException::new);
    }
    private Correction map(ResultSet rs,int row) throws SQLException {
        return new Correction(rs.getLong("id"),rs.getObject("public_id",UUID.class),rs.getLong("employee_id"),
            rs.getObject("employee_public_id",UUID.class),rs.getObject("work_date",LocalDate.class),rs.getString("status"),
            rs.getTimestamp("proposed_in").toInstant(),instant(rs,"proposed_out"),rs.getString("reason"),
            rs.getLong("version"),rs.getObject("active_revision_id",UUID.class));
    }
    private static Instant instant(ResultSet rs,String column) throws SQLException {
        Timestamp timestamp=rs.getTimestamp(column); return timestamp==null?null:timestamp.toInstant();
    }
    @Override
    public void changeState(Correction current,String state) {
        jdbc.update("UPDATE attendance_correction SET status=?,version=version+1 WHERE id=?",state,current.id());
    }
    @Override
    public UUID findBaseline(UUID revisionId) {
        return jdbc.queryForObject("SELECT baseline_id FROM attendance_correction_revision WHERE public_id=?", UUID.class, revisionId);
    }
    private static final String COLUMNS="id,public_id,employee_id,employee_public_id,work_date,status,proposed_in,"
        + "proposed_out,reason,version,active_revision_id";
    private static Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value);
    }
}
