package id.mydev.peoplecore.organization.infrastructure.migration;

import id.mydev.peoplecore.support.PostgresFixture;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("postgres")
class ProfileProposalPostgresTest {

    @Test
    void cleanMigrationCreatesProposalTables() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            assertEquals(0, fixture.jdbc.queryForObject(
                "select count(*) from profile_proposal", Integer.class));
            assertEquals(0, fixture.jdbc.queryForObject(
                "select count(*) from employee_contact_profile", Integer.class));
        }
    }

    @Test
    void oneOpenProposalIndexAllowsSingleDraftPerEmployee() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.jdbc.update(
                "insert into employees(public_id,employee_number,created_at) values(gen_random_uuid(),'OPEN-1',now())");
            long employeeId = fixture.jdbc.queryForObject(
                "select id from employees where employee_number='OPEN-1'", Long.class);
            fixture.jdbc.update(
                "insert into profile_proposal(public_id,employee_id,state,created_at,updated_at) "
                    + "values(gen_random_uuid(),?,'DRAFT',now(),now())", employeeId);
            assertThrows(DataAccessException.class, () -> fixture.jdbc.update(
                "insert into profile_proposal(public_id,employee_id,state,created_at,updated_at) "
                    + "values(gen_random_uuid(),?,'PENDING',now(),now())", employeeId));
            fixture.jdbc.update(
                "update profile_proposal set state='CANCELLED' where employee_id=?", employeeId);
            fixture.jdbc.update(
                "insert into profile_proposal(public_id,employee_id,state,created_at,updated_at) "
                    + "values(gen_random_uuid(),?,'RETURNED',now(),now())", employeeId);
            assertEquals(2, fixture.jdbc.queryForObject(
                "select count(*) from profile_proposal where employee_id=?", Integer.class, employeeId));
        }
    }

    @Test
    void historyRowsAreAppendOnlyWhileAssignmentsTransition() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.jdbc.update(
                "insert into employees(public_id,employee_number,created_at) values(gen_random_uuid(),'HIST-1',now())");
            long employeeId = fixture.jdbc.queryForObject(
                "select id from employees where employee_number='HIST-1'", Long.class);
            fixture.jdbc.update(
                "insert into profile_proposal(public_id,employee_id,state,created_at,updated_at) "
                    + "values(gen_random_uuid(),?,'DRAFT',now(),now())", employeeId);
            long proposalId = fixture.jdbc.queryForObject(
                "select id from profile_proposal where employee_id=?", Long.class, employeeId);
            fixture.jdbc.update(
                "insert into profile_revision(public_id,proposal_id,revision_number,created_at) "
                    + "values(gen_random_uuid(),?,1,now())", proposalId);
            long revisionId = fixture.jdbc.queryForObject(
                "select id from profile_revision where proposal_id=?", Long.class, proposalId);
            fixture.jdbc.update(
                "insert into profile_revision_change(revision_id,field_name,proposed_value,baseline_version) "
                    + "values(?,'personalPhone','+62 812 345 678',0)", revisionId);
            fixture.jdbc.update(
                "insert into profile_assignment(public_id,proposal_id,revision_id,assignee_account_public_id,created_at) "
                    + "values(gen_random_uuid(),?,?,gen_random_uuid(),now())", proposalId, revisionId);
            fixture.jdbc.update(
                "insert into profile_decision(proposal_id,revision_id,action,actor_account_public_id,created_at) "
                    + "values(?,?,'RETURN',gen_random_uuid(),now())", proposalId, revisionId);
            fixture.jdbc.update(
                "insert into profile_route_evaluation(proposal_id,revision_id,evaluated_at,result) "
                    + "values(?,?,now(),'BLOCKED')", proposalId, revisionId);

            assertThrows(DataAccessException.class, () -> fixture.jdbc.update(
                "update profile_revision set revision_number=2 where id=?", revisionId));
            assertThrows(DataAccessException.class, () -> fixture.jdbc.update(
                "delete from profile_revision_change where revision_id=?", revisionId));
            assertThrows(DataAccessException.class, () -> fixture.jdbc.update(
                "delete from profile_decision where proposal_id=?", proposalId));
            assertThrows(DataAccessException.class, () -> fixture.jdbc.update(
                "update profile_route_evaluation set result='MATCHED' where proposal_id=?", proposalId));

            fixture.jdbc.update(
                "update profile_assignment set status='CLOSED',closed_at=now() where proposal_id=?",
                proposalId);
            assertEquals("CLOSED", fixture.jdbc.queryForObject(
                "select status from profile_assignment where proposal_id=?", String.class, proposalId));
        }
    }

    @Test
    void stateAndFieldConstraintsRejectUnknownValues() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.jdbc.update(
                "insert into employees(public_id,employee_number,created_at) values(gen_random_uuid(),'CHK-1',now())");
            long employeeId = fixture.jdbc.queryForObject(
                "select id from employees where employee_number='CHK-1'", Long.class);
            assertThrows(DataAccessException.class, () -> fixture.jdbc.update(
                "insert into profile_proposal(public_id,employee_id,state,created_at,updated_at) "
                    + "values(gen_random_uuid(),?,'UNKNOWN',now(),now())", employeeId));
            assertThrows(DataAccessException.class, () -> fixture.jdbc.update(
                "insert into profile_proposal(public_id,employee_id,state,blocked_reason,created_at,updated_at) "
                    + "values(gen_random_uuid(),?,'PENDING','BOGUS',now(),now())", employeeId));
            fixture.jdbc.update(
                "insert into profile_proposal(public_id,employee_id,state,created_at,updated_at) "
                    + "values(gen_random_uuid(),?,'DRAFT',now(),now())", employeeId);
            long proposalId = fixture.jdbc.queryForObject(
                "select id from profile_proposal where employee_id=?", Long.class, employeeId);
            fixture.jdbc.update(
                "insert into profile_revision(public_id,proposal_id,revision_number,created_at) "
                    + "values(gen_random_uuid(),?,1,now())", proposalId);
            long revisionId = fixture.jdbc.queryForObject(
                "select id from profile_revision where proposal_id=?", Long.class, proposalId);
            assertThrows(DataAccessException.class, () -> fixture.jdbc.update(
                "insert into profile_revision_change(revision_id,field_name,proposed_value,baseline_version) "
                    + "values(?,'salary','100',0)", revisionId));
            assertThrows(DataAccessException.class, () -> fixture.jdbc.update(
                "insert into profile_decision(proposal_id,revision_id,action,actor_account_public_id,created_at) "
                    + "values(?,?,'ESCALATE',gen_random_uuid(),now())", proposalId, revisionId));
        }
    }

    @Test
    void upgradeFromRuleSnapshotPreservesPublishedContent() {
        try (var fixture = new PostgresFixture()) {
            Flyway.configure().dataSource(fixture.url, fixture.username, fixture.password)
                .schemas(fixture.schema).target("10").load().migrate();
            UUID setId = UUID.randomUUID();
            UUID reviewer = UUID.randomUUID();
            fixture.jdbc.update(
                "insert into profile_rule_sets(public_id,request_type,status,created_at) "
                    + "values(?,'PROFILE_CHANGE','DRAFT',now())", setId);
            fixture.jdbc.update(
                "insert into profile_rules(rule_set_id,rule_id,priority,reviewer_account_public_id,created_at) "
                    + "select id,'route-eng',10,?::uuid,now() from profile_rule_sets where public_id=?::uuid",
                reviewer, setId);
            fixture.jdbc.update(
                "update profile_rule_sets set status='PUBLISHED',published_at=now() where public_id=?::uuid",
                setId);
            assertEquals(8, fixture.flyway().migrate().migrationsExecuted);
            assertEquals(1, fixture.jdbc.queryForObject(
                "select count(*) from profile_rules where rule_id='route-eng'", Integer.class));
            assertEquals(0, fixture.jdbc.queryForObject(
                "select count(*) from profile_proposal", Integer.class));
            assertEquals(0, fixture.flyway().migrate().migrationsExecuted);
        }
    }

    @Test
    void sealedRevisionRejectsLateFieldInserts() {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            var runtime = fixture.runtime();
            runtime.update(
                "insert into employees(public_id,employee_number,created_at) values(gen_random_uuid(),'SEAL-1',now())");
            long employeeId = runtime.queryForObject(
                "select id from employees where employee_number='SEAL-1'", Long.class);
            runtime.update(
                "insert into profile_proposal(public_id,employee_id,state,created_at,updated_at) "
                    + "values(gen_random_uuid(),?,'DRAFT',now(),now())", employeeId);
            long proposalId = runtime.queryForObject(
                "select id from profile_proposal where employee_id=?", Long.class, employeeId);
            runtime.update(
                "insert into profile_revision(public_id,proposal_id,revision_number,created_at) "
                    + "values(gen_random_uuid(),?,1,now())", proposalId);
            long revisionId = runtime.queryForObject(
                "select id from profile_revision where proposal_id=?", Long.class, proposalId);
            assertEquals(false, runtime.queryForObject(
                "select sealed from profile_revision where id=?", Boolean.class, revisionId));
            runtime.update(
                "insert into profile_revision_change(revision_id,field_name,proposed_value,baseline_version) "
                    + "values(?,'personalPhone','+62 812 345 678',0)", revisionId);
            assertThrows(DataAccessException.class, () -> runtime.update(
                "update profile_revision set sealed=TRUE,org_assignment_public_id=gen_random_uuid() where id=?", revisionId));
            assertThrows(DataAccessException.class, () -> runtime.update(
                "update profile_revision set sealed=TRUE,org_assignment_predecessor_public_id=gen_random_uuid() where id=?", revisionId));
            runtime.update("update profile_revision set sealed=TRUE where id=?", revisionId);
            assertEquals(true, runtime.queryForObject(
                "select sealed from profile_revision where id=?", Boolean.class, revisionId));
            assertThrows(DataAccessException.class, () -> runtime.update(
                "insert into profile_revision_change(revision_id,field_name,proposed_value,baseline_version) "
                    + "values(?,'personalEmail','a@example.id',0)", revisionId));
            assertThrows(DataAccessException.class, () -> runtime.update(
                "update profile_revision set revision_number=2 where id=?", revisionId));
            assertThrows(DataAccessException.class, () -> runtime.update(
                "update profile_revision set org_assignment_public_id=gen_random_uuid() where id=?", revisionId));
            assertThrows(DataAccessException.class, () -> runtime.update(
                "update profile_revision set org_assignment_predecessor_public_id=gen_random_uuid() where id=?", revisionId));
            assertThrows(DataAccessException.class, () -> runtime.update(
                "delete from profile_revision where id=?", revisionId));
            assertThrows(DataAccessException.class, () -> runtime.update(
                "update profile_revision set sealed=TRUE where id=?", revisionId));
            assertEquals(1, runtime.queryForObject(
                "select count(*) from profile_revision_change where revision_id=?", Integer.class,
                revisionId));
        }
    }

    @Test
    void upgradeSealsExistingRevisions() {
        try (var fixture = new PostgresFixture()) {
            Flyway.configure().dataSource(fixture.url, fixture.username, fixture.password)
                .schemas(fixture.schema).target("11").load().migrate();
            fixture.jdbc.update(
                "insert into employees(public_id,employee_number,created_at) values(gen_random_uuid(),'SEAL-UP',now())");
            long employeeId = fixture.jdbc.queryForObject(
                "select id from employees where employee_number='SEAL-UP'", Long.class);
            fixture.jdbc.update(
                "insert into profile_proposal(public_id,employee_id,state,created_at,updated_at) "
                    + "values(gen_random_uuid(),?,'PENDING',now(),now())", employeeId);
            long proposalId = fixture.jdbc.queryForObject(
                "select id from profile_proposal where employee_id=?", Long.class, employeeId);
            fixture.jdbc.update(
                "insert into profile_revision(public_id,proposal_id,revision_number,created_at) "
                    + "values(gen_random_uuid(),?,1,now())", proposalId);
            long revisionId = fixture.jdbc.queryForObject(
                "select id from profile_revision where proposal_id=?", Long.class, proposalId);
            fixture.jdbc.update(
                "insert into profile_revision_change(revision_id,field_name,proposed_value,baseline_version) "
                    + "values(?,'personalPhone','+62 812 345 678',0)", revisionId);
            assertEquals(7, fixture.flyway().migrate().migrationsExecuted);
            fixture.grantRuntime();
            var runtime = fixture.runtime();
            assertEquals(true, runtime.queryForObject(
                "select sealed from profile_revision where id=?", Boolean.class, revisionId));
            assertThrows(DataAccessException.class, () -> runtime.update(
                "insert into profile_revision_change(revision_id,field_name,proposed_value,baseline_version) "
                    + "values(?,'personalEmail','a@example.id',0)", revisionId));
            assertEquals(1, runtime.queryForObject(
                "select count(*) from profile_revision_change where revision_id=?", Integer.class,
                revisionId));
            assertEquals(0, fixture.flyway().migrate().migrationsExecuted);
        }
    }

    @Test
    void concurrentAppendLosesToSealRace() throws Exception {
        try (var fixture = new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            var runtime = fixture.runtime();
            runtime.update(
                "insert into employees(public_id,employee_number,created_at) values(gen_random_uuid(),'SEAL-RACE',now())");
            long employeeId = runtime.queryForObject(
                "select id from employees where employee_number='SEAL-RACE'", Long.class);
            runtime.update(
                "insert into profile_proposal(public_id,employee_id,state,created_at,updated_at) "
                    + "values(gen_random_uuid(),?,'DRAFT',now(),now())", employeeId);
            long proposalId = runtime.queryForObject(
                "select id from profile_proposal where employee_id=?", Long.class, employeeId);
            runtime.update(
                "insert into profile_revision(public_id,proposal_id,revision_number,created_at) "
                    + "values(gen_random_uuid(),?,1,now())", proposalId);
            long revisionId = runtime.queryForObject(
                "select id from profile_revision where proposal_id=?", Long.class, proposalId);
            var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                fixture.url, fixture.runtimeUsername, fixture.runtimePassword);
            java.sql.Connection sealer = dataSource.getConnection();
            sealer.setAutoCommit(false);
            try {
                try (var lock = sealer.prepareStatement(
                    "select id from profile_revision where id=? for update")) {
                    lock.setObject(1, revisionId);
                    lock.executeQuery().close();
                }
                java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newSingleThreadExecutor();
                try {
                    java.util.concurrent.Future<?> late = pool.submit(() -> runtime.update(
                        "insert into profile_revision_change(revision_id,field_name,"
                            + "proposed_value,baseline_version) "
                            + "values(?,'personalEmail','a@example.id',0)", revisionId));
                    Thread.sleep(500);
                    try (var seal = sealer.prepareStatement(
                        "update profile_revision set sealed=TRUE where id=?")) {
                        seal.setObject(1, revisionId);
                        seal.executeUpdate();
                    }
                    sealer.commit();
                    java.util.concurrent.ExecutionException failure =
                        org.junit.jupiter.api.Assertions.assertThrows(
                            java.util.concurrent.ExecutionException.class, late::get);
                    assertEquals("42501", rootSqlState(failure));
                    assertEquals(0, runtime.queryForObject(
                        "select count(*) from profile_revision_change where revision_id=?",
                        Integer.class, revisionId));
                } finally {
                    pool.shutdownNow();
                }
            } finally {
                sealer.close();
            }
        }
    }

    private static String rootSqlState(Throwable failure) {
        String state = null;
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof java.sql.SQLException sql && sql.getSQLState() != null) {
                state = sql.getSQLState();
            }
        }
        return state;
    }
}
