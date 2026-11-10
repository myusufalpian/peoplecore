package id.mydev.peoplecore.payslip;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.command.CommandConflictException;
import id.mydev.peoplecore.document.application.service.DocumentVersionContentService;
import id.mydev.peoplecore.document.application.service.RevocableDocumentTransfer;
import id.mydev.peoplecore.document.application.service.VerifiedDocumentAccess.Purpose;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.RoleAssignment;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.identity.domain.repository.RoleAssignmentRepository;
import id.mydev.peoplecore.identity.domain.repository.UserAccountRepository;
import id.mydev.peoplecore.payslip.application.PayslipCommands;
import id.mydev.peoplecore.payslip.application.PayslipModels.Draft;
import id.mydev.peoplecore.payslip.application.PayslipModels.Item;
import id.mydev.peoplecore.payslip.application.PayslipService;
import id.mydev.peoplecore.payslip.api.PayslipDownloadController;
import id.mydev.peoplecore.support.JwtAuthFixture;
import id.mydev.peoplecore.support.PostgresFixture;
import java.time.Instant;
import java.time.Duration;
import java.time.YearMonth;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("postgres")
@SpringBootTest(properties = {"peoplecore.runtime.role=api", "spring.jpa.hibernate.ddl-auto=validate"})
class PayslipCommandsPostgresTest {
    private static final String ISSUER = "https://payslip.test";
    private static final PostgresFixture DATABASE = new PostgresFixture();

    static {
        DATABASE.flyway().migrate();
        DATABASE.grantRuntime();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATABASE.url);
        registry.add("spring.datasource.username", () -> DATABASE.runtimeUsername);
        registry.add("spring.datasource.password", () -> DATABASE.runtimePassword);
    }

    @Autowired PayslipCommands commands;
    @Autowired UserAccountRepository accounts;
    @Autowired RoleAssignmentRepository roles;
    @Autowired JdbcTemplate jdbc;
    @Autowired PayslipService payslips;

    private UserAccount account;
    private Authentication caller;
    private Draft draft;

    @BeforeEach
    void setUp() {
        Instant now = Instant.now();
        account = accounts.save(new UserAccount(UUID.randomUUID(), ISSUER, UUID.randomUUID().toString(), now));
        roles.save(new RoleAssignment(account.getId(), HrisRole.FINANCE_PAYROLL, null, now.minusSeconds(60), now));
        caller = JwtAuthFixture.token(ISSUER, account.getOidcSubject());
        SecurityContextHolder.getContext().setAuthentication(caller);
        UUID employeePublicId = UUID.randomUUID();
        long employee = jdbc.queryForObject("insert into employees(public_id,employee_number,employment_start_date,created_at) "
            + "values(?,?,date '2020-01-01',now()) returning id", Long.class, employeePublicId, "PAY-" + UUID.randomUUID());
        var document = PayslipPersistencePostgresTest.verifiedDocument(jdbc, employee);
        UUID documentPublicId = jdbc.queryForObject("select public_id from private_document where id=?", UUID.class, document.id());
        draft = new Draft(List.of(new Item(employeePublicId, YearMonth.of(2026, 11), documentPublicId)));
    }

    @AfterEach
    void clearCaller() {
        SecurityContextHolder.clearContext();
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    @Test
    void reviewReplayIsIdempotentAndRequiresCurrentAuthority() {
        var created = commands.create(caller, key(), draft);
        String reviewKey = key();
        var reviewed = commands.review(caller, reviewKey, created.batchPublicId(), 1);

        assertEquals(reviewed, commands.review(caller, reviewKey, created.batchPublicId(), 1));
        assertEquals(1, jdbc.queryForObject("select count(id) from audit_events "
            + "where aggregate_public_id=? and action='PAYSLIP_BATCH_REVIEW'", Integer.class, created.batchPublicId()));
        assertThrows(CommandConflictException.class,
            () -> commands.review(caller, reviewKey, created.batchPublicId(), 2));

        jdbc.update("update role_assignments set effective_to=now() where account_id=? and role=?", account.getId(), HrisRole.FINANCE_PAYROLL);
        assertThrows(ResourceNotFoundException.class,
            () -> commands.review(caller, reviewKey, created.batchPublicId(), 1));
    }

    @Test
    void failedReviewAuditRollsBackTransitionAndAllowsRetry() {
        var created = commands.create(caller, key(), draft);
        String reviewKey = key();
        DATABASE.jdbc.execute("revoke insert on audit_events from " + DATABASE.runtimeUsername);
        try {
            assertThrows(RuntimeException.class, () -> commands.review(caller, reviewKey, created.batchPublicId(), 1));
        } finally {
            DATABASE.jdbc.execute("grant insert on audit_events to " + DATABASE.runtimeUsername);
        }

        assertEquals("DRAFT", jdbc.queryForObject("select status from payslip_batch where public_id=?",
            String.class, created.batchPublicId()));
        assertEquals(0, jdbc.queryForObject("select count(id) from command_receipts "
            + "where command_type='PAYSLIP_BATCH_REVIEW' and idempotency_key=?", Integer.class, reviewKey));
        assertEquals(0, jdbc.queryForObject("select count(id) from audit_events "
            + "where aggregate_public_id=? and action='PAYSLIP_BATCH_REVIEW'", Integer.class, created.batchPublicId()));
        assertEquals("REVIEWED", commands.review(caller, reviewKey, created.batchPublicId(), 1).status());
    }

    @Test
    void publishReplayWritesOneOutboxEventAndRechecksCurrentRole() {
        var created = commands.create(caller, key(), draft);
        commands.review(caller, key(), created.batchPublicId(), 1);
        assertThrows(ResponseStatusException.class, () -> commands.review(caller, key(), created.batchPublicId(), 1));
        String key = key();
        var published = commands.publish(caller, key, created.batchPublicId(), 1);
        assertThrows(ResponseStatusException.class, () -> commands.edit(caller, key(), created.batchPublicId(), draft, 1));

        assertEquals(published, commands.publish(caller, key, created.batchPublicId(), 1));
        assertEquals(1, jdbc.queryForObject("select count(id) from outbox_events "
            + "where aggregate_public_id=? and event_type='PAYSLIP_BATCH_PUBLISH'", Integer.class, created.batchPublicId()));
        assertThrows(CommandConflictException.class, () -> commands.publish(caller, key, created.batchPublicId(), 2));

        jdbc.update("update role_assignments set effective_to=now() where account_id=? and role=?", account.getId(), HrisRole.FINANCE_PAYROLL);
        assertThrows(ResourceNotFoundException.class, () -> commands.publish(caller, key, created.batchPublicId(), 1));
    }

    @Test
    void failedOutboxWriteRollsBackPublicationReceiptAndPointer() {
        var created = commands.create(caller, key(), draft);
        commands.review(caller, key(), created.batchPublicId(), 1);
        String publishKey = key();
        DATABASE.jdbc.execute("revoke insert on outbox_events from " + DATABASE.runtimeUsername);
        try {
            assertThrows(RuntimeException.class, () -> commands.publish(caller, publishKey, created.batchPublicId(), 1));
        } finally {
            DATABASE.jdbc.execute("grant insert on outbox_events to " + DATABASE.runtimeUsername);
        }
        assertEquals("REVIEWED", jdbc.queryForObject("select status from payslip_batch where public_id=?", String.class, created.batchPublicId()));
        assertEquals(0, jdbc.queryForObject("select count(p.id) from payslip_publication p "
            + "join payslip_batch_revision r on r.id=p.revision_id join payslip_batch b on b.id=r.batch_id "
            + "where b.public_id=?", Integer.class, created.batchPublicId()));
        assertEquals(0, jdbc.queryForObject("select count(id) from command_receipts "
            + "where command_type='PAYSLIP_BATCH_PUBLISH' and idempotency_key=?", Integer.class, publishKey));
        assertEquals(0, jdbc.queryForObject("select count(id) from outbox_events "
            + "where aggregate_public_id=? and event_type='PAYSLIP_BATCH_PUBLISH'", Integer.class, created.batchPublicId()));
    }

    @Test
    @Timeout(90)
    void backdatedDatabaseCutoffStopsActiveHttpDownload(@TempDir Path directory) throws Exception {
        var created = commands.create(caller, key(), draft);
        commands.review(caller, key(), created.batchPublicId(), 1);
        commands.publish(caller, key(), created.batchPublicId(), 1);
        UUID employeePublicId = draft.items().getFirst().employeePublicId();
        Long employeeId = jdbc.queryForObject("select id from employees where public_id=?", Long.class, employeePublicId);
        Instant now = Instant.now();
        UserAccount owner = accounts.save(new UserAccount(UUID.randomUUID(), ISSUER, UUID.randomUUID().toString(), now));
        roles.save(new RoleAssignment(owner.getId(), HrisRole.EMPLOYEE, null, now.minusSeconds(60), now));
        jdbc.update("insert into account_bindings(employee_id,employee_public_id,account_id,oidc_issuer,oidc_subject,created_at) "
                + "values(?,?,?,?,?,now())", employeeId, employeePublicId, owner.getId(), ISSUER, owner.getOidcSubject());
        Authentication ownerCaller = JwtAuthFixture.token(ISSUER, owner.getOidcSubject());
        UUID publication = jdbc.queryForObject("select p.public_id from payslip_current c "
            + "join payslip_publication p on p.id=c.publication_id where c.employee_id=?", UUID.class, employeeId);
        var version = payslips.requirePublishedVersion(publication, ownerCaller);
        DocumentVersionContentService content = mock(DocumentVersionContentService.class);
        StalledSource source = new StalledSource();
        when(content.open(version.documentPublicId(), version.versionPublicId(), employeePublicId, Purpose.PAYSLIP))
            .thenReturn(new DocumentVersionContentService.Content(source, "application/pdf", StalledSource.INITIAL_BYTES + 1L));
        @SuppressWarnings("unchecked")
        ObjectProvider<RevocableDocumentTransfer> transfers = mock(ObjectProvider.class);

        try (var transfer = new RevocableDocumentTransfer(1, Duration.ofMillis(25), Duration.ofMillis(500))) {
            when(transfers.getIfAvailable()).thenReturn(transfer);
            var controller = new PayslipDownloadController(payslips, content, transfers);
            Tomcat tomcat = new Tomcat();
            tomcat.setBaseDir(directory.toString());
            tomcat.setPort(0);
            tomcat.getConnector().setProperty("address", "127.0.0.1");
            Context context = tomcat.addContext("", directory.toString());
            CountDownLatch finished = new CountDownLatch(1);
            Tomcat.addServlet(context, "download", new HttpServlet() {
                @Override
                protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
                    response.setBufferSize(1024);
                    try {
                        controller.content(ownerCaller, publication).getBody().writeTo(response.getOutputStream());
                    } catch (IOException | RuntimeException exception) {
                        response.getOutputStream().close();
                    } finally {
                        finished.countDown();
                    }
                }
            });
            context.addServletMappingDecoded("/content", "download");
            try {
                tomcat.start();
                URI uri = URI.create("http://127.0.0.1:" + tomcat.getConnector().getLocalPort() + "/content");
                HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(10000);
                try (InputStream body = connection.getInputStream()) {
                    assertEquals(200, connection.getResponseCode());
                    assertTrue(body.read() >= 0);
                    assertTrue(source.stalled.await(10, TimeUnit.SECONDS));
                    DATABASE.jdbc.update("update user_accounts set access_ends_at=? where id=?",
                        java.sql.Timestamp.from(Instant.now().minusSeconds(60)), owner.getId());
                    assertTrue(source.closed.await(10, TimeUnit.SECONDS));
                    assertTrue(finished.await(10, TimeUnit.SECONDS));
                    int received = 1;
                    byte[] bytes = new byte[4096];
                    try {
                        int count;
                        while ((count = body.read(bytes)) != -1) received += count;
                    } catch (SocketTimeoutException exception) {
                        throw exception;
                    } catch (IOException exception) {
                        // A reset connection is an acceptable cancellation outcome.
                    }
                    assertTrue(received <= StalledSource.INITIAL_BYTES);
                } finally {
                    connection.disconnect();
                }
            } finally {
                source.close();
                tomcat.stop();
                tomcat.destroy();
            }
        }
    }

    private static final class StalledSource extends InputStream {
        private static final int INITIAL_BYTES = 16384;
        private final CountDownLatch stalled = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private int produced;

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (produced < INITIAL_BYTES) {
                int count = Math.min(length, INITIAL_BYTES - produced);
                Arrays.fill(bytes, offset, offset + count, (byte) 65);
                produced += count;
                return count;
            }
            stalled.countDown();
            try {
                if (!closed.await(30, TimeUnit.SECONDS)) throw new IOException("Cancellation timed out");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", exception);
            }
            throw new IOException("Source closed");
        }

        @Override
        public int read() throws IOException {
            byte[] bytes = new byte[1];
            return read(bytes, 0, 1) < 0 ? -1 : bytes[0] & 0xff;
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
