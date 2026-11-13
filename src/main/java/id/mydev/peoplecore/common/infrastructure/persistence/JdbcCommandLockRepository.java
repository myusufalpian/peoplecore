package id.mydev.peoplecore.common.infrastructure.persistence;

import id.mydev.peoplecore.common.command.CommandBusyException;
import id.mydev.peoplecore.common.command.CommandLockRepository;
import java.sql.SQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCommandLockRepository implements CommandLockRepository {
    private final JdbcTemplate jdbcTemplate;

    public JdbcCommandLockRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void lock(long lockKey, int lockTimeoutMs) {
        String previousTimeout = jdbcTemplate.queryForObject("show lock_timeout", String.class);
        jdbcTemplate.queryForObject("select set_config('lock_timeout', ?, true)", String.class, lockTimeoutMs + "ms");
        try {
            jdbcTemplate.query("select pg_advisory_xact_lock(?)", rs -> { }, lockKey);
        } catch (DataAccessException ex) {
            if (ex.getMostSpecificCause() instanceof SQLException sql && "55P03".equals(sql.getSQLState())) {
                throw new CommandBusyException("Command lock wait timed out", ex);
            }
            throw ex;
        }
        jdbcTemplate.queryForObject("select set_config('lock_timeout', ?, true)", String.class, previousTimeout);
    }
}
