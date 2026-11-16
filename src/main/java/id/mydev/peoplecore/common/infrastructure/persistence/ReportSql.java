package id.mydev.peoplecore.common.infrastructure.persistence;

import id.mydev.peoplecore.common.reporting.ReportFilter;
import id.mydev.peoplecore.common.reporting.ReportLimitException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public final class ReportSql {
    public static final String EMPLOYEE_COLUMNS = "e.public_id employee_id, e.employee_number, '' employee_name";
    public static final String CURRENT_ASSIGNMENT = " LEFT JOIN employee_assignments a ON a.employee_id=e.id "
        + "AND a.superseded_at IS NULL AND a.valid_from<=:now AND (a.valid_to IS NULL OR a.valid_to>:now) ";
    public static final String EMPLOYEE_FILTER = " AND (:employee IS NULL OR e.public_id=CAST(:employee AS UUID)) "
        + "AND (:unit IS NULL OR a.org_unit=:unit) ";
    public static final String REQUEST_COLUMNS = "requestId,employeeId,employeeNumber,employeeName,workflowType,submittedAt,updatedAt,status,currentReviewStep,blocked";
    private ReportSql() { }

    public static String employeeScope(boolean hr, boolean manager, boolean finance, boolean self) {
        List<String> choices = new ArrayList<>();
        if (self) choices.add("EXISTS (SELECT 1 FROM account_bindings b WHERE b.employee_id=e.id AND b.account_id=:account "
            + "AND b.revoked_at IS NULL AND EXISTS (SELECT 1 FROM role_assignments r WHERE r.account_id=:account "
            + "AND r.effective_from<=:now AND (r.effective_to IS NULL OR r.effective_to>:now)))");
        if (hr) choices.add(role("HR_ADMIN"));
        if (finance) choices.add(role("FINANCE_PAYROLL"));
        if (manager) choices.add("(" + role("MANAGER") + " AND EXISTS (SELECT 1 FROM account_bindings mb "
            + "WHERE mb.account_id=:account AND mb.revoked_at IS NULL AND mb.employee_id=a.manager_employee_id))");
        return "(" + String.join(" OR ", choices) + ")";
    }

    public static String role(String role) {
        if (!List.of("HR_ADMIN", "FINANCE_PAYROLL", "MANAGER", "SYSTEM_ADMIN").contains(role))
            throw new IllegalArgumentException("Unsupported report role");
        return "EXISTS (SELECT 1 FROM role_assignments r WHERE r.account_id=:account AND r.role='" + role
            + "' AND r.effective_from<=:now AND (r.effective_to IS NULL OR r.effective_to>:now) "
            + "AND (r.scope IS NULL OR TRIM(r.scope)='' OR r.scope=a.org_unit))";
    }

    public static String operationsScope() {
        return "EXISTS (SELECT 1 FROM role_assignments op WHERE op.account_id=:account AND op.role='SYSTEM_ADMIN' "
            +"AND (op.scope IS NULL OR TRIM(op.scope)='') AND op.effective_from<=:now AND (op.effective_to IS NULL OR op.effective_to>:now))";
    }

    public static String eligibleReviewerById(String accountExpression,String role) {
        return eligibleReviewer("reviewer.id="+accountExpression,null,role);
    }

    public static String eligibleReviewerByPublicId(String accountExpression,String employeeExpression,String role) {
        return eligibleReviewer("reviewer.public_id="+accountExpression,employeeExpression,role);
    }

    private static String eligibleReviewer(String accountCondition,String employeeExpression,String role) {
        if(!List.of("MANAGER","HR_ADMIN","FINANCE_PAYROLL").contains(role))
            throw new IllegalArgumentException("Unsupported reviewer role");
        return "EXISTS(SELECT 1 FROM user_accounts reviewer JOIN account_bindings reviewer_binding "
            +"ON reviewer_binding.account_id=reviewer.id AND reviewer_binding.revoked_at IS NULL "
            +"JOIN role_assignments reviewer_role ON reviewer_role.account_id=reviewer.id WHERE "+accountCondition
            +" AND reviewer.status='ACTIVE' AND (reviewer.access_ends_at IS NULL OR reviewer.access_ends_at>:now) "
            +"AND reviewer_binding.employee_id<>e.id "
            +(employeeExpression==null?"":"AND reviewer_binding.employee_public_id="+employeeExpression+" ")
            +"AND reviewer_role.role='"+role+"' AND reviewer_role.effective_from<=:now "
            +"AND (reviewer_role.effective_to IS NULL OR reviewer_role.effective_to>:now) "
            +"AND (reviewer_role.scope IS NULL OR TRIM(reviewer_role.scope)='' OR reviewer_role.scope=a.org_unit))";
    }

    public static MapSqlParameterSource parameters(ReportFilter filter) {
        return new MapSqlParameterSource().addValue("account",filter.accountId()).addValue("now",Timestamp.from(filter.now()))
            .addValue("employee", string(filter.employeeId()), Types.VARCHAR).addValue("unit",filter.unit(), Types.VARCHAR).addValue("status",filter.status(), Types.VARCHAR)
            .addValue("fromDate",filter.from()).addValue("toDate",filter.to()).addValue("year",filter.year(), Types.INTEGER)
            .addValue("fromTime",filter.from()==null?null:Timestamp.from(filter.from().atStartOfDay(ZoneId.of("Asia/Jakarta")).toInstant()))
            .addValue("toTime",filter.to()==null?null:Timestamp.from(filter.to().atStartOfDay(ZoneId.of("Asia/Jakarta")).toInstant()))
            .addValue("resource",string(filter.resourceId()), Types.VARCHAR).addValue("actor",filter.actorId(), Types.VARCHAR).addValue("action",filter.action(), Types.VARCHAR)
            .addValue("correlation",filter.correlationId(), Types.VARCHAR).addValue("category",filter.category(), Types.VARCHAR).addValue("workflow",filter.workflowType(),Types.VARCHAR).addValue("limit",filter.rowLimit()+1);
    }

    public static List<List<String>> query(DataSource source, String sql, ReportFilter filter, List<String> resultColumns) {
        long remaining = filter.deadlineNanos()-System.nanoTime();
        if (remaining<=0) throw new ReportLimitException("Report execution deadline exceeded");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout((int)Math.max(1,Math.min(30,Duration.ofNanos(remaining).toSeconds())));
        try {
            List<List<String>> rows = new NamedParameterJdbcTemplate(jdbc).query(sql,parameters(filter),(row,index)->values(row,resultColumns));
            if (System.nanoTime()>filter.deadlineNanos()) throw new ReportLimitException("Report execution deadline exceeded");
            return rows;
        } catch (QueryTimeoutException timeout) {
            throw new ReportLimitException("Report execution deadline exceeded; narrow the selected filters");
        }
    }

    private static List<String> values(ResultSet row,List<String> columns) throws SQLException {
        List<String> values=new ArrayList<>();
        for (String column:columns) {
            Object value=row.getObject(column);
            values.add(value==null?"":value.toString());
        }
        return List.copyOf(values);
    }

    private static String string(Object value) { return value==null?null:value.toString(); }
}
