package id.mydev.peoplecore.approval.application.admission;

import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.common.command.CommandExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.HexFormat;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProfileAdmissionGuardTest {

    private static ProfileAdmissionGuard offlineGuard() {
        DatabaseMetaData metadata = (DatabaseMetaData) Proxy.newProxyInstance(
            ProfileAdmissionGuardTest.class.getClassLoader(),
            new Class<?>[] {DatabaseMetaData.class},
            (proxy, method, args) -> {
                if ("getDatabaseProductName".equals(method.getName())) {
                    return "H2";
                }
                throw new UnsupportedOperationException(method.getName());
            });
        Connection connection = (Connection) Proxy.newProxyInstance(
            ProfileAdmissionGuardTest.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
                if ("getMetaData".equals(method.getName())) {
                    return metadata;
                }
                if ("close".equals(method.getName()) || "isClosed".equals(method.getName())) {
                    return "isClosed".equals(method.getName()) ? false : null;
                }
                throw new UnsupportedOperationException(method.getName());
            });
        DataSource dataSource = (DataSource) Proxy.newProxyInstance(
            ProfileAdmissionGuardTest.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> {
                if ("getConnection".equals(method.getName())) {
                    return connection;
                }
                throw new UnsupportedOperationException(method.getName());
            });
        return new ProfileAdmissionGuard(new JdbcTemplate(dataSource));
    }

    @Test
    void commandModesFollowTransactionContracts() {
        ProfileAdmissionGuard guard = offlineGuard();
        for (String command : new String[] {"PROFILE_CREATE", "PROFILE_EDIT", "PROFILE_SUBMIT",
            "PROFILE_RESUBMIT", "PROFILE_WITHDRAW", "PROFILE_APPROVE", "PROFILE_REJECT",
            "PROFILE_RETURN", "PROFILE_REASSIGN", "PROFILE_ROUTE_RETRY", "PROFILE_MASTER_CORRECT"}) {
            assertEquals(AdmissionGuard.Mode.SHARED, guard.modeForCommand(command));
        }
        for (String command : new String[] {"PROFILE_RULE_PUBLISH", "PROFILE_RULE_ACTIVATE",
            "EMPLOYEE_CREATE", "ASSIGNMENT_ADD", "INVITATION_ISSUE", "ACCOUNT_REBIND"}) {
            assertEquals(AdmissionGuard.Mode.EXCLUSIVE, guard.modeForCommand(command));
        }
        assertNull(guard.modeForCommand("UNKNOWN_COMMAND"));
        assertNull(guard.modeForCommand(null));
    }

    @Test
    void nonPostgresRuntimeSkipsNativeCoordination() {
        ProfileAdmissionGuard guard = offlineGuard();
        guard.acquire(AdmissionGuard.Mode.SHARED);
        guard.acquire(AdmissionGuard.Mode.EXCLUSIVE);
    }

    @Test
    void detectionFailureFailsClosed() {
        DataSource broken = (DataSource) Proxy.newProxyInstance(
            ProfileAdmissionGuardTest.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> {
                throw new UnsupportedOperationException("No database connection");
            });
        assertThrows(RuntimeException.class,
            () -> new ProfileAdmissionGuard(new JdbcTemplate(broken)));
    }

    @Test
    void admissionKeyDerivesFromNamespacedIdentity() {
        long expected = ByteBuffer.wrap(HexFormat.of().parseHex(
            CommandExecutionService.computeSha256("profile-admission"))).getLong();
        assertNotEquals(0L, expected);
        assertEquals(expected, ProfileAdmissionGuard.ADMISSION_KEY);
    }
}
