package id.mydev.peoplecore.identity.infrastructure.persistence;

import id.mydev.peoplecore.identity.application.service.AuthGenerationService;
import id.mydev.peoplecore.identity.application.service.AuthorizationFenceService;
import id.mydev.peoplecore.identity.domain.model.AuthGeneration;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.RoleAssignment;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.organization.domain.model.Employee;
import id.mydev.peoplecore.support.PostgresFixture;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("postgres")
@SpringBootTest(properties={"peoplecore.runtime.role=worker","spring.jpa.hibernate.ddl-auto=validate"})
class AuthGenerationOrmPostgresTest {
    private static final PostgresFixture DATABASE=new PostgresFixture();
    static {
        DATABASE.flyway().migrate();
        DATABASE.grantRuntime();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->DATABASE.url);
        registry.add("spring.datasource.username",()->DATABASE.runtimeUsername);
        registry.add("spring.datasource.password",()->DATABASE.runtimePassword);
    }

    @AfterAll
    static void closeDatabase() { DATABASE.close(); }

    @Autowired EntityManager entities;
    @Autowired AuthGenerationService generations;
    @Autowired AuthorizationFenceService fences;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;

    @Test
    void currentReadsTheDatabaseCounterAfterTriggersEvenWhenTheEntityIsAlreadyManaged() {
        new TransactionTemplate(transactions).executeWithoutResult(status->{
            AuthGeneration cached=entities.find(AuthGeneration.class,AuthGeneration.SINGLETON_ID);
            assertNotNull(cached);
            long before=cached.getGeneration();
            seed();
            entities.flush();
            long triggered=databaseGeneration();
            assertTrue(triggered>before);
            assertEquals(before,cached.getGeneration());
            assertEquals(triggered,generations.current());
            assertEquals(triggered,databaseGeneration());
        });
        TransactionTemplate readOnly=new TransactionTemplate(transactions);
        readOnly.setReadOnly(true);
        readOnly.executeWithoutResult(status->{
            long current=generations.current();
            assertEquals(current,fences.currentBoundary(Instant.now()).generation());
            assertEquals(current,databaseGeneration());
        });
    }

    @Test
    void atomicIncrementFlushesPendingOrmScopeWritesAndNeverReusesTheCachedCounter() {
        Seed seed=new TransactionTemplate(transactions).execute(status->seed());
        assertNotNull(seed);
        Long committed=new TransactionTemplate(transactions).execute(status->{
            AuthGeneration cached=entities.find(AuthGeneration.class,AuthGeneration.SINGLETON_ID);
            assertNotNull(cached);
            long cachedValue=cached.getGeneration();
            Employee employee=entities.find(Employee.class,seed.employeeId());
            employee.changeEmploymentDates(LocalDate.of(2026,1,1),null);
            jdbc.update("UPDATE role_assignments SET effective_to=? WHERE id=?",Instant.now().plusSeconds(3600).atOffset(ZoneOffset.UTC),seed.roleId());
            long afterRoleTrigger=databaseGeneration();
            assertTrue(afterRoleTrigger>cachedValue);
            long first=generations.increment();
            entities.flush();
            assertEquals(afterRoleTrigger+2,first);
            assertEquals(first,databaseGeneration());
            assertEquals(first,generations.current());

            jdbc.update("UPDATE employees SET status='INACTIVE' WHERE id=?",seed.employeeId());
            long afterEmployeeTrigger=databaseGeneration();
            assertTrue(afterEmployeeTrigger>first);
            long second=generations.increment();
            entities.flush();
            assertEquals(afterEmployeeTrigger+1,second);
            assertTrue(second>first);
            assertEquals(second,generations.current());
            assertEquals(second,databaseGeneration());
            return second;
        });
        assertNotNull(committed);
        assertEquals(committed.longValue(),generations.current());
    }

    private Seed seed() {
        Instant now=Instant.now();
        UUID accountId=UUID.randomUUID();
        UserAccount account=new UserAccount(accountId,"https://generation.test",accountId.toString(),now);
        entities.persist(account);
        RoleAssignment role=new RoleAssignment(account.getId(),HrisRole.EMPLOYEE,null,now.minusSeconds(60),now);
        entities.persist(role);
        Employee employee=new Employee(UUID.randomUUID(),"GEN-"+UUID.randomUUID(),now);
        entities.persist(employee);
        entities.flush();
        return new Seed(role.getId(),employee.getId());
    }

    private long databaseGeneration() {
        Long generation=jdbc.queryForObject("SELECT generation FROM auth_generation WHERE id=1",Long.class);
        assertNotNull(generation);
        return generation;
    }

    private record Seed(long roleId,long employeeId) { }
}
