package id.mydev.peoplecore.identity.infrastructure.persistence;

import id.mydev.peoplecore.identity.domain.repository.NotificationIdentityRepository;
import id.mydev.peoplecore.notification.domain.NotificationModels.Destination;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcNotificationIdentityRepository implements NotificationIdentityRepository {
    private final JdbcTemplate jdbc;
    public JdbcNotificationIdentityRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public Optional<VerificationOperation> verificationOperation(String actor,String key) {
        return jdbc.query("SELECT request_hash,destination_id,destination_version FROM notification_destination_operation WHERE actor=? AND idempotency_key=?",
            (row,i)->new VerificationOperation(row.getString(1),row.getObject(2,UUID.class),row.getLong(3)),actor,key).stream().findFirst();
    }
    @Override public void recordVerification(String actor,String key,String hash,Destination destination,Instant now) {
        jdbc.update("INSERT INTO notification_destination_operation(actor,idempotency_key,request_hash,destination_id,destination_version,created_at) VALUES (?,?,?,?,?,?)",
            actor,key,hash,destination.id(),destination.version(),time(now));
    }
    @Override
    public Optional<Destination> currentDestination(UUID employeeId) {
        return jdbc.query("SELECT public_id,employee_public_id,address,version,status FROM notification_destination "
            + "WHERE employee_public_id=?", (row, i) -> new Destination(row.getObject(1, UUID.class),
                row.getObject(2, UUID.class), row.getString(3), row.getLong(4), row.getString(5)), employeeId)
            .stream().findFirst();
    }

    @Override
    public Destination verifyDestination(UUID employeeId, String address, String evidence, String actor, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO notification_destination(public_id,employee_public_id,address,version,status,"
            + "evidence_ref,verified_by,verified_at) VALUES (?,?,?,0,'VERIFIED',?,?,?) "
            + "ON CONFLICT(employee_public_id) DO UPDATE SET address=excluded.address,"
            + "version=notification_destination.version+1,status='VERIFIED',evidence_ref=excluded.evidence_ref,"
            + "verified_by=excluded.verified_by,verified_at=excluded.verified_at", id, employeeId, address, evidence,
            actor, time(now));
        return currentDestination(employeeId).orElseThrow();
    }

    @Override
    public void storeInvitation(UUID invitationId, UUID employeeId, UUID destinationId, long destinationVersion,
                                String ciphertext, String keyId, Instant now) {
        jdbc.update("INSERT INTO invitation_delivery_source(invitation_public_id,employee_public_id,"
            + "destination_id,destination_version,ciphertext,key_id,created_at) VALUES (?,?,?,?,?,?,?)",
            invitationId, employeeId, destinationId, destinationVersion, ciphertext, keyId, time(now));
    }

    @Override
    public Optional<InvitationMaterial> lockInvitation(UUID invitationId, Instant now) {
        var owners = jdbc.query("SELECT e.id FROM employees e JOIN enrollment_invitations i ON i.employee_id=e.id "
            + "WHERE i.public_id=? FOR UPDATE OF e", (row, i) -> row.getLong(1), invitationId);
        if (owners.isEmpty()) return Optional.empty();
        return jdbc.query("SELECT i.public_id,s.employee_public_id,d.public_id,d.address,d.version,d.status,"
            + "s.ciphertext,s.key_id FROM enrollment_invitations i JOIN employees e ON e.id=i.employee_id "
            + "JOIN invitation_delivery_source s ON s.invitation_public_id=i.public_id "
            + "JOIN notification_destination d ON d.public_id=s.destination_id "
            + "WHERE i.public_id=? AND i.status='ISSUED' AND i.expires_at>? AND e.status='ACTIVE' "
            + "AND d.status='VERIFIED' AND d.version=s.destination_version AND s.ciphertext IS NOT NULL "
            + "FOR UPDATE OF i,s", (row, index) -> new InvitationMaterial(row.getObject(1, UUID.class),
                row.getObject(2, UUID.class), new Destination(row.getObject(3, UUID.class),
                    row.getObject(2, UUID.class), row.getString(4), row.getLong(5), row.getString(6)),
                row.getString(7), row.getString(8)), invitationId, time(now)).stream().findFirst();
    }

    @Override public void wipeInvitation(UUID id) {
        jdbc.update("UPDATE invitation_delivery_source SET ciphertext=NULL WHERE invitation_public_id=?", id);
    }
    @Override public void cleanupInvalidInvitations(Instant now) {
        jdbc.update("WITH due AS (SELECT s.invitation_public_id FROM invitation_delivery_source s "
            + "JOIN enrollment_invitations i ON i.public_id=s.invitation_public_id WHERE s.ciphertext IS NOT NULL "
            + "AND (i.status<>'ISSUED' OR i.expires_at<=?) ORDER BY i.expires_at,i.public_id "
            + "LIMIT 100 FOR UPDATE OF s SKIP LOCKED) UPDATE invitation_delivery_source s SET ciphertext=NULL "
            + "FROM due WHERE s.invitation_public_id=due.invitation_public_id", time(now));
    }
    @Override public boolean lockInvitationAcceptance(UUID invitation,UUID employee,UUID destination,long version) {
        var employees=jdbc.query("SELECT e.id FROM employees e JOIN enrollment_invitations i ON i.employee_id=e.id "
            + "WHERE i.public_id=? AND e.public_id=? FOR UPDATE OF e",(row,index)->row.getLong(1),invitation,employee);
        if(employees.isEmpty()) return false;
        return !jdbc.query("SELECT i.public_id FROM enrollment_invitations i "
            + "JOIN invitation_delivery_source s ON s.invitation_public_id=i.public_id "
            + "WHERE i.public_id=? AND s.employee_public_id=? AND s.destination_id=? AND s.destination_version=? "
            + "FOR UPDATE OF i,s",(row,index)->row.getObject(1,UUID.class),invitation,employee,destination,version).isEmpty();
    }
    @Override public void markInvitationAccepted(UUID id) {
        jdbc.update("UPDATE enrollment_invitations SET delivery_status='SENT' WHERE public_id=?", id);
    }
    @Override public void suppressDestination(UUID id) {
        jdbc.update("UPDATE notification_destination SET status='SUPPRESSED',version=version+1 WHERE public_id=?", id);
    }
    private static Timestamp time(Instant value) { return Timestamp.from(value); }
}
