package id.mydev.peoplecore;

import id.mydev.peoplecore.identity.application.command.EnrollmentCommands;
import id.mydev.peoplecore.identity.application.service.AuthGenerationService;
import id.mydev.peoplecore.identity.application.service.EnrollmentService;
import id.mydev.peoplecore.identity.domain.exception.InvitationConflictException;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.RoleAssignment;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.identity.domain.repository.RoleAssignmentRepository;
import id.mydev.peoplecore.identity.domain.repository.UserAccountRepository;
import id.mydev.peoplecore.organization.application.command.EmployeeCommands;
import id.mydev.peoplecore.support.JwtAuthFixture;
import id.mydev.peoplecore.support.PostgresFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("postgres")
@SpringBootTest(properties = {
    "peoplecore.runtime.role=worker",
    "spring.jpa.hibernate.ddl-auto=validate",
    "peoplecore.organization.allowed-units=ENG",
    "spring.datasource.hikari.maximum-pool-size=2",
    "spring.datasource.hikari.connection-timeout=2000"
})
class ActivationAttemptPoolPostgresTest {

    private static final String ISSUER = "https://idp.example";

    private static final PostgresFixture DATABASE = new PostgresFixture();

    static {
        DATABASE.flyway().migrate();
        DATABASE.grantRuntime();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> DATABASE.url);
        properties.add("spring.datasource.username", () -> DATABASE.runtimeUsername);
        properties.add("spring.datasource.password", () -> DATABASE.runtimePassword);
    }

    @Autowired EmployeeCommands employeeCommands;
    @Autowired EnrollmentCommands enrollmentCommands;
    @Autowired AuthGenerationService generations;
    @Autowired UserAccountRepository accounts;
    @Autowired RoleAssignmentRepository roles;

    private Authentication hrCaller;

    @BeforeEach
    void seedHr() {
        Instant now = Instant.now();
        String subject = "hr-" + UUID.randomUUID();
        UserAccount hr = accounts.save(new UserAccount(UUID.randomUUID(), ISSUER, subject, now));
        roles.save(new RoleAssignment(hr.getId(), HrisRole.HR_ADMIN, null, now.minusSeconds(60), now));
        roles.save(new RoleAssignment(hr.getId(), HrisRole.SYSTEM_ADMIN, null, now.minusSeconds(60), now));
        hrCaller = JwtAuthFixture.token(ISSUER, subject);
        SecurityContextHolder.getContext().setAuthentication(hrCaller);
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
    void concurrentActivationsShareSmallPoolWithoutStarvation() throws Exception {
        UUID employeeId = employeeCommands
            .create(hrCaller, "create-" + UUID.randomUUID(), "EMP-" + UUID.randomUUID()).employeeId();
        String subject = "pool-" + UUID.randomUUID();
        var issued = enrollmentCommands.issue(hrCaller, "invite-" + UUID.randomUUID(),
            employeeId, "hrd@example.id", ISSUER, subject);
        Authentication member = JwtAuthFixture.token(ISSUER, subject);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int attempt = 0; attempt < 3; attempt++) {
                String guess = "bad-" + attempt;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!go.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Racers did not start together");
                    }
                    SecurityContextHolder.getContext().setAuthentication(member);
                    try {
                        enrollmentCommands.activate(member, "pool-" + guess + UUID.randomUUID(),
                            issued.result().invitationId(), guess);
                        throw new AssertionError("Bad activation should have been rejected");
                    } catch (InvitationConflictException expected) {
                        return null;
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                }));
            }
            futures.add(pool.submit(() -> {
                ready.countDown();
                if (!go.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Racers did not start together");
                }
                generations.current();
                return null;
            }));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
