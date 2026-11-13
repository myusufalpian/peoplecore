package id.mydev.peoplecore.approval.infrastructure.persistence;

import id.mydev.peoplecore.approval.domain.repository.ProfileAdmissionLockRepository;
import id.mydev.peoplecore.common.command.AdmissionGuard.Mode;
import id.mydev.peoplecore.common.command.CommandBusyException;
import id.mydev.peoplecore.common.command.CommandExecutionService;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

@Repository
public class JdbcProfileAdmissionLockRepository implements ProfileAdmissionLockRepository {
    private static final long ADMISSION_KEY = ByteBuffer.wrap(HexFormat.of().parseHex(
        CommandExecutionService.computeSha256("profile-admission"))).getLong();
    private static final String DOMAIN_LOCK_TIMEOUT = "2s";
    private final JdbcTemplate jdbc;
    private final boolean postgres;

    public JdbcProfileAdmissionLockRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        String product = jdbc.execute((Connection connection) -> connection.getMetaData().getDatabaseProductName());
        this.postgres = "PostgreSQL".equals(product);
    }

    @Override
    public void acquire(Mode mode) {
        Objects.requireNonNull(mode, "mode must not be null");
        if (!postgres) { return; }
        Assert.state(TransactionSynchronizationManager.isActualTransactionActive(),
            "Admission coordination requires a transaction");
        jdbc.execute("SET LOCAL lock_timeout = '" + DOMAIN_LOCK_TIMEOUT + "'");
        String statement = Mode.EXCLUSIVE.equals(mode)
            ? "SELECT pg_advisory_xact_lock(" + ADMISSION_KEY + ")"
            : "SELECT pg_advisory_xact_lock_shared(" + ADMISSION_KEY + ")";
        try {
            jdbc.execute(statement);
        } catch (DataAccessException ex) {
            if (ex.getMostSpecificCause() instanceof SQLException sql && "55P03".equals(sql.getSQLState())) {
                throw new CommandBusyException("Admission guard wait timed out", ex);
            }
            throw ex;
        }
    }
}
