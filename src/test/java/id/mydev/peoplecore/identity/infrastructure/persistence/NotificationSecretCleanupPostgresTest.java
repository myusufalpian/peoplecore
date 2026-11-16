package id.mydev.peoplecore.identity.infrastructure.persistence;

import id.mydev.peoplecore.support.PostgresFixture;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("postgres")
class NotificationSecretCleanupPostgresTest {
    @Test
    void expiredSecretDisposalIsBoundedAndPreservesDeliveryEvidence() {
        Instant now=Instant.parse("2026-11-13T00:00:00Z");
        try(var fixture=new PostgresFixture()) {
            fixture.flyway().migrate();
            fixture.grantRuntime();
            var jdbc=fixture.runtime();
            UUID employee=UUID.randomUUID();
            long owner=jdbc.queryForObject("INSERT INTO employees(public_id,employee_number,created_at) VALUES(?,?,?) RETURNING id",
                Long.class,employee,"CLEANUP-"+employee,Timestamp.from(now));
            var repository=new JdbcNotificationIdentityRepository(jdbc);
            var destination=repository.verifyDestination(employee,"verified@example.test","idp-proof","operator",now);
            for(int index=0;index<103;index++) {
                UUID invitation=UUID.randomUUID();
                jdbc.update("INSERT INTO enrollment_invitations(public_id,employee_id,intended_identity_ref,secret_hash,status,"
                    +"expires_at,issued_at,expected_issuer,expected_subject) VALUES(?,?,?,?,'REVOKED',?,?,?,?)",
                    invitation,owner,"proof-"+index,"a".repeat(64),Timestamp.from(now.minusSeconds(1)),Timestamp.from(now.minusSeconds(3600)),
                    "https://identity.example.test","recipient-"+index);
                repository.storeInvitation(invitation,employee,destination.id(),destination.version(),"opaque-test-ciphertext","test-key",now);
            }
            repository.cleanupInvalidInvitations(now);
            assertEquals(3,jdbc.queryForObject("SELECT count(invitation_public_id) FROM invitation_delivery_source WHERE ciphertext IS NOT NULL",Integer.class));
            repository.cleanupInvalidInvitations(now);
            assertEquals(0,jdbc.queryForObject("SELECT count(invitation_public_id) FROM invitation_delivery_source WHERE ciphertext IS NOT NULL",Integer.class));
            assertEquals(103,jdbc.queryForObject("SELECT count(invitation_public_id) FROM invitation_delivery_source",Integer.class));
        }
    }
}
