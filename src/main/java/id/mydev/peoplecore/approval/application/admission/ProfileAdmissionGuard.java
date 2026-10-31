package id.mydev.peoplecore.approval.application.admission;

import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.common.command.CommandBusyException;
import id.mydev.peoplecore.common.command.CommandExecutionService;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

@Component
public class ProfileAdmissionGuard implements AdmissionGuard {

    static final long ADMISSION_KEY = ByteBuffer.wrap(HexFormat.of().parseHex(
        CommandExecutionService.computeSha256("profile-admission"))).getLong();

    private static final String DOMAIN_LOCK_TIMEOUT = "2s";

    private static final Set<String> SHARED_COMMANDS = Set.of(
        "PROFILE_CREATE",
        "PROFILE_EDIT",
        "PROFILE_SUBMIT",
        "PROFILE_RESUBMIT",
        "PROFILE_WITHDRAW",
        "PROFILE_APPROVE",
        "PROFILE_REJECT",
        "PROFILE_RETURN",
        "PROFILE_REASSIGN",
        "PROFILE_ROUTE_RETRY",
        "PROFILE_MASTER_CORRECT",
        "WORK_CONTEXT_QUERY",
        "OCCURRENCE_RESOLVE",
        "ATTENDANCE_CLOCK",
        "ATTENDANCE_CORRECTION_CREATE",
        "ATTENDANCE_CORRECTION_EDIT",
        "ATTENDANCE_CORRECTION_DISCARD",
        "ATTENDANCE_CORRECTION_SUBMIT",
        "ATTENDANCE_CORRECTION_RESUBMIT",
        "ATTENDANCE_CORRECTION_WITHDRAW",
        "ATTENDANCE_CORRECTION_APPROVE",
        "ATTENDANCE_CORRECTION_REJECT",
        "ATTENDANCE_CORRECTION_RETURN",
        "ATTENDANCE_CORRECTION_REASSIGN",
        "ATTENDANCE_CORRECTION_ROUTE_RETRY",
        "LEAVE_OPENING",
        "LEAVE_ADJUSTMENT",
        "LEAVE_REQUEST_CREATE",
        "LEAVE_REQUEST_EDIT",
        "LEAVE_REQUEST_DISCARD",
        "LEAVE_REQUEST_SUBMIT",
        "LEAVE_REQUEST_RESUBMIT",
        "LEAVE_REQUEST_WITHDRAW",
        "LEAVE_REQUEST_APPROVE",
        "LEAVE_REQUEST_REJECT",
        "LEAVE_REQUEST_RETURN",
        "LEAVE_REQUEST_REASSIGN",
        "LEAVE_REQUEST_RETRY_ROUTE",
        "LEAVE_CANCELLATION_CREATE",
        "LEAVE_CANCELLATION_WITHDRAW",
        "LEAVE_CANCELLATION_APPROVE",
        "LEAVE_CANCELLATION_REJECT",
        "LEAVE_CANCELLATION_REASSIGN",
        "LEAVE_CANCELLATION_RETRY_ROUTE");

    private static final Set<String> EXCLUSIVE_COMMANDS = Set.of(
        "PROFILE_RULE_PUBLISH",
        "PROFILE_RULE_ACTIVATE",
        "EMPLOYEE_CREATE",
        "EMPLOYEE_UPDATE",
        "ASSIGNMENT_ADD",
        "ASSIGNMENT_REVISE",
        "EMPLOYMENT_UPDATE",
        "INVITATION_ISSUE",
        "INVITATION_REISSUE",
        "INVITATION_ACTIVATE",
        "ACCOUNT_OFFBOARD",
        "ACCOUNT_REBIND",
        "ACCOUNT_REHIRE",
        "ACCOUNT_RESTORE",
        "SCHEDULE_PUBLISH",
        "CALENDAR_PUBLISH",
        "SCHEDULE_ASSIGN",
        "CALENDAR_ASSIGN",
        "ATTENDANCE_RULE_PUBLISH",
        "ATTENDANCE_RULE_ACTIVATE",
        "LEAVE_RULE_PUBLISH",
        "LEAVE_RULE_ACTIVATE");

    private final JdbcTemplate jdbc;
    private final boolean postgres;

    public ProfileAdmissionGuard(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.postgres = detectPostgres(jdbc);
    }

    private static boolean detectPostgres(JdbcTemplate jdbc) {
        String product = jdbc.execute((java.sql.Connection connection) ->
            connection.getMetaData().getDatabaseProductName());
        return "PostgreSQL".equals(product);
    }

    @Override
    public Mode modeForCommand(String commandType) {
        if (commandType == null) {
            return null;
        }
        if (SHARED_COMMANDS.contains(commandType)) {
            return Mode.SHARED;
        }
        if (EXCLUSIVE_COMMANDS.contains(commandType)) {
            return Mode.EXCLUSIVE;
        }
        return null;
    }

    @Override
    public void acquire(Mode mode) {
        Objects.requireNonNull(mode, "mode must not be null");
        if (!postgres) {
            return;
        }
        Assert.state(TransactionSynchronizationManager.isActualTransactionActive(),
            "Admission coordination requires a transaction");
        jdbc.execute("SET LOCAL lock_timeout = '" + DOMAIN_LOCK_TIMEOUT + "'");
        String statement = Mode.EXCLUSIVE.equals(mode)
            ? "SELECT pg_advisory_xact_lock(" + ADMISSION_KEY + ")"
            : "SELECT pg_advisory_xact_lock_shared(" + ADMISSION_KEY + ")";
        try {
            jdbc.execute(statement);
        } catch (DataAccessException ex) {
            if (ex.getMostSpecificCause() instanceof SQLException sql
                && "55P03".equals(sql.getSQLState())) {
                throw new CommandBusyException("Admission guard wait timed out", ex);
            }
            throw ex;
        }
    }
}
