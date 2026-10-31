package id.mydev.peoplecore.attendance.application.service;

import id.mydev.peoplecore.attendance.domain.exception.WorkContextException;
import id.mydev.peoplecore.attendance.domain.model.AttendanceAccount;
import id.mydev.peoplecore.attendance.domain.repository.AttendanceAccountRepository;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

@Component
public class AttendanceAccountGuard {

    private final AttendanceAccountRepository accounts;
    private final JdbcTemplate jdbc;
    private final EntityManager entities;
    private final Clock clock;
    private final boolean postgres;

    public AttendanceAccountGuard(AttendanceAccountRepository accounts, JdbcTemplate jdbc,
                                  EntityManager entities, Clock clock) {
        this.accounts = Objects.requireNonNull(accounts, "accounts must not be null");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.entities = Objects.requireNonNull(entities, "entities must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.postgres = detectPostgres(jdbc);
    }

    public void ensureLocked(Long employeeId) {
        Objects.requireNonNull(employeeId, "employeeId must not be null");
        if (postgres) {
            ensureLockedConflictSafe(employeeId);
            return;
        }
        ensureLockedJpa(employeeId);
    }

    private void ensureLockedConflictSafe(Long employeeId) {
        jdbc.update("INSERT INTO attendance_account(employee_id, created_at) VALUES (?, ?) "
            + "ON CONFLICT (employee_id) DO NOTHING",
            employeeId, java.sql.Timestamp.from(Instant.now(clock)));
        entities.flush();
        entities.clear();
        Integer locked = jdbc.queryForObject(
            "SELECT 1 FROM attendance_account WHERE employee_id = ? FOR UPDATE", Integer.class, employeeId);
        if (!Integer.valueOf(1).equals(locked)) {
            throw new WorkContextException(WorkContextException.MISSING_SCHEDULE,
                "Attendance account is unavailable");
        }
    }

    private void ensureLockedJpa(Long employeeId) {
        if (accounts.lockByEmployeeId(employeeId).isPresent()) {
            return;
        }
        try {
            accounts.saveAndFlush(new AttendanceAccount(employeeId, Instant.now(clock)));
        } catch (DataAccessException ex) {
            entities.clear();
        }
        accounts.lockByEmployeeId(employeeId)
            .orElseThrow(() -> new WorkContextException(WorkContextException.MISSING_SCHEDULE,
                "Attendance account is unavailable"));
    }

    private static boolean detectPostgres(JdbcTemplate jdbc) {
        try {
            String product = jdbc.execute((Connection connection) ->
                connection.getMetaData().getDatabaseProductName());
            return "PostgreSQL".equals(product);
        } catch (DataAccessException ex) {
            return false;
        }
    }
}
