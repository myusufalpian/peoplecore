package id.mydev.peoplecore.notification.infrastructure.persistence;

import id.mydev.peoplecore.notification.domain.NotificationModels.Delivery;
import id.mydev.peoplecore.notification.domain.NotificationModels.Destination;
import id.mydev.peoplecore.notification.domain.NotificationModels.Intent;
import id.mydev.peoplecore.notification.domain.NotificationModels.Summary;
import id.mydev.peoplecore.notification.domain.repository.NotificationRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcNotificationRepository implements NotificationRepository {
    private static final String COLUMNS="public_id,event_id,event_type,aggregate_public_id,occurred_at,"
        + "employee_public_id,account_public_id,template,eligibility_type,assignment_public_id,destination_id,"
        + "destination_version,state,version,lease_generation,attempt_count,manual_attempt,lease_ends_at,updated_at";
    private final JdbcTemplate jdbc;
    public JdbcNotificationRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override public void storeIntent(Intent i, Optional<Destination> destination, Instant now) {
        jdbc.update("INSERT INTO notification_intent(event_id,event_type,aggregate_public_id,occurred_at,"
            + "employee_public_id,account_public_id,template,eligibility_type,assignment_public_id,"
            + "destination_id,destination_version,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
            i.eventId(),i.eventType(),i.aggregateId(),time(i.occurredAt()),i.employeeId(),i.accountId(),i.template(),
            i.eligibilityType(),i.assignmentId(),destination.map(Destination::id).orElse(null),
            destination.map(Destination::version).orElse(null),time(now));
    }
    @Override public int materialize(UUID event, Instant now) {
        int inserted=jdbc.update("INSERT INTO notification_delivery(public_id,event_id,event_type,aggregate_public_id,"
            + "occurred_at,employee_public_id,account_public_id,template,eligibility_type,assignment_public_id,"
            + "destination_id,destination_version,state,next_attempt_at,created_at,updated_at,error_code) "
            + "SELECT gen_random_uuid(),event_id,event_type,aggregate_public_id,occurred_at,employee_public_id,"
            + "account_public_id,template,eligibility_type,assignment_public_id,destination_id,destination_version,"
            + "CASE WHEN destination_id IS NULL THEN 'FAILED' ELSE 'PENDING' END,?,?,?,"
            + "CASE WHEN destination_id IS NULL THEN 'DESTINATION_UNAVAILABLE' ELSE NULL END "
            + "FROM notification_intent WHERE event_id=? ON CONFLICT DO NOTHING",time(now),time(now),time(now),event);
        jdbc.query("SELECT public_id FROM notification_delivery WHERE event_id=? AND state='FAILED' AND error_code='DESTINATION_UNAVAILABLE'",
            (row,index)->row.getObject(1,UUID.class),event).forEach(id->attention(event,id,"DESTINATION_UNAVAILABLE",now));
        return inserted;
    }
    @Override public Optional<Delivery> claim(Instant now) {
        var due=jdbc.query("SELECT "+COLUMNS+" FROM notification_delivery WHERE state='PENDING' AND next_attempt_at<=? "
            + "ORDER BY next_attempt_at,public_id LIMIT 1 FOR UPDATE SKIP LOCKED",JdbcNotificationRepository::map,time(now));
        if (due.isEmpty()) return Optional.empty();
        UUID id=due.getFirst().id();
        jdbc.update("UPDATE notification_delivery SET state='SENDING',lease_generation=lease_generation+1,"
            + "version=version+1,attempt_count=attempt_count+1,lease_ends_at=?,updated_at=? WHERE public_id=?",
            time(now.plusSeconds(60)),time(now),id);
        Delivery row=find(id,false).orElseThrow();
        jdbc.update("INSERT INTO notification_attempt(public_id,delivery_id,generation,started_at,manual_attempt) "
            + "VALUES (?,?,?,?,?)",UUID.randomUUID(),id,row.generation(),time(now),row.manualAttempt());
        return Optional.of(row);
    }
    @Override public Optional<Delivery> find(UUID id, boolean lock) {
        return jdbc.query("SELECT "+COLUMNS+" FROM notification_delivery WHERE public_id=?"+(lock?" FOR UPDATE":""),
            JdbcNotificationRepository::map,id).stream().findFirst();
    }
    @Override public List<Summary> list(int offset,int size) {
        return jdbc.query("SELECT public_id,event_id,event_type,aggregate_public_id,template,state,version,attempt_count,"
            + "updated_at FROM notification_delivery ORDER BY updated_at DESC,public_id LIMIT ? OFFSET ?",
            (row,i)->new Summary(row.getObject(1,UUID.class),row.getObject(2,UUID.class),row.getString(3),
                row.getObject(4,UUID.class),row.getString(5),row.getString(6),row.getLong(7),row.getInt(8),
                row.getTimestamp(9).toInstant()),size,offset);
    }
    @Override public void expireLeases(Instant now) {
        var expired=jdbc.query("UPDATE notification_delivery SET state='UNKNOWN',lease_ends_at=NULL,"
            + "version=version+1,error_code='LEASE_EXPIRED',updated_at=? WHERE state='SENDING' AND lease_ends_at<=? "
            + "RETURNING public_id,event_id",(row,i)->new UUID[]{row.getObject(1,UUID.class),row.getObject(2,UUID.class)},time(now),time(now));
        expired.forEach(ids->attention(ids[1],ids[0],"LEASE_EXPIRED",now));
    }
    @Override public boolean reserve(String key, Instant window, int limit) {
        Integer count=jdbc.queryForObject("INSERT INTO notification_rate_bucket(bucket_key,window_at,used) VALUES (?,?,1) "
            + "ON CONFLICT(bucket_key) DO UPDATE SET window_at=excluded.window_at,"
            + "used=CASE WHEN notification_rate_bucket.window_at=excluded.window_at THEN notification_rate_bucket.used+1 ELSE 1 END "
            + "RETURNING used",Integer.class,key,time(window));
        return count!=null && count<=limit;
    }
    @Override public boolean outcome(Delivery d,String state,String provider,String error,Instant next,Instant now) {
        int changed=jdbc.update("UPDATE notification_delivery SET state=?,provider_message_id=?,error_code=?,"
            + "next_attempt_at=?,lease_ends_at=NULL,updated_at=?,version=version+1 "
            + "WHERE public_id=? AND state='SENDING' AND lease_generation=? AND lease_ends_at>?",state,provider,error,time(next),time(now),d.id(),d.generation(),time(now));
        if (changed==1) {
            evidence(d.id(),attemptId(d.id(),d.generation()).orElseThrow(),d.generation(),state,provider,error,
                "outcome:"+d.id()+":"+d.generation(),now);
            if ("UNKNOWN".equals(state)||"FAILED".equals(state)) attention(d.eventId(),d.id(),state,now);
        }
        return changed == 1;
    }
    @Override public void evidence(UUID delivery,UUID attempt,long generation,String outcome,String provider,
                                   String error,String source,Instant now) {
        jdbc.update("INSERT INTO notification_evidence(public_id,delivery_id,attempt_id,generation,outcome,"
            + "provider_message_id,error_code,source_id,created_at) VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT(source_id) DO NOTHING",
            UUID.randomUUID(),delivery,attempt,generation,outcome,provider,error,source,time(now));
    }
    @Override public Optional<UUID> attemptId(UUID delivery,long generation) {
        return jdbc.query("SELECT public_id FROM notification_attempt WHERE delivery_id=? AND generation=?",
            (row,i)->row.getObject(1,UUID.class),delivery,generation).stream().findFirst();
    }
    @Override public boolean feedback(UUID delivery,UUID attempt,String provider,String state,String source,Instant now) {
        var attempts=jdbc.query("SELECT generation FROM notification_attempt WHERE delivery_id=? AND public_id=?",
            (row,i)->row.getLong(1),delivery,attempt);
        if (attempts.isEmpty()) return false;
        long generation=attempts.getFirst();
        Delivery row=find(delivery,true).orElseThrow();
        var known=jdbc.query("SELECT provider_message_id FROM notification_evidence WHERE delivery_id=? "
            + "AND generation=? AND provider_message_id IS NOT NULL",(r,i)->r.getString(1),delivery,generation);
        if (known.stream().anyMatch(id->!id.equals(provider))) return false;
        evidence(delivery,attempt,generation,state,provider,null,"ses:"+source,now);
        if (row.generation()!=generation || "SUPPRESSED".equals(row.state()) || "DELIVERED".equals(row.state())
            || ("FAILED".equals(row.state()) && "ACCEPTED".equals(state))) return true;
        jdbc.update("UPDATE notification_delivery SET state=?,provider_message_id=?,lease_ends_at=NULL,"
            + "version=version+1,updated_at=? WHERE public_id=? AND lease_generation=?",state,provider,time(now),delivery,generation);
        if ("FAILED".equals(state)) attention(row.eventId(),delivery,"FAILED",now);
        return true;
    }
    @Override public List<EvidenceSummary> evidence(UUID delivery) {
        return jdbc.query("SELECT public_id,attempt_id,outcome,provider_message_id,created_at FROM notification_evidence WHERE delivery_id=? ORDER BY created_at DESC,public_id LIMIT 100",
            (row,i)->new EvidenceSummary(row.getObject(1,UUID.class),row.getObject(2,UUID.class),row.getString(3),row.getString(4),row.getTimestamp(5).toInstant()),delivery);
    }
    @Override public Optional<Evidence> evidenceReference(UUID id,UUID delivery) {
        return jdbc.query("SELECT attempt_id,provider_message_id,outcome FROM notification_evidence WHERE public_id=? AND delivery_id=? "
            + "AND provider_message_id IS NOT NULL AND outcome IN ('ACCEPTED','DELIVERED','FAILED')",
            (row,i)->new Evidence(row.getObject(1,UUID.class),row.getString(2),row.getString(3)),id,delivery).stream().findFirst();
    }
    @Override public Optional<String> operationHash(String actor,String key) {
        return jdbc.query("SELECT request_hash FROM notification_operation WHERE actor=? AND idempotency_key=?",
            (row,i)->row.getString(1),actor,key).stream().findFirst();
    }
    @Override public void operation(String actor,String key,String hash,UUID delivery,String action,String reason,
                                    boolean acknowledged,Instant now) {
        jdbc.update("INSERT INTO notification_operation(actor,idempotency_key,request_hash,delivery_id,action,reason,"
            + "acknowledged_duplicate,created_at) VALUES (?,?,?,?,?,?,?,?)",actor,key,hash,delivery,action,reason,acknowledged,time(now));
    }
    @Override public void retry(Delivery d,Instant now) {
        jdbc.update("UPDATE notification_delivery SET state='PENDING',manual_attempt=TRUE,next_attempt_at=?,"
            + "version=version+1,updated_at=? WHERE public_id=? AND version=?",time(now),time(now),d.id(),d.version());
    }
    @Override public void replacement(UUID id,UUID eventId,Delivery original,Destination destination,Instant now) {
        jdbc.update("INSERT INTO notification_delivery(public_id,event_id,event_type,aggregate_public_id,occurred_at,"
            + "employee_public_id,account_public_id,template,eligibility_type,assignment_public_id,destination_id,"
            + "destination_version,state,next_attempt_at,manual_attempt,created_at,updated_at,original_delivery_id) "
            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'PENDING',?,true,?,?,?)",id,eventId,original.eventType(),original.aggregateId(),
            time(original.occurredAt()),original.employeeId(),original.accountId(),original.template(),original.eligibilityType(),
            original.assignmentId(),destination.id(),destination.version(),time(now),time(now),time(now),original.id());
    }
    @Override public void attention(UUID event,UUID delivery,String code,Instant now) {
        long generation=delivery==null?0:find(delivery,false).map(Delivery::generation).orElse(0L);
        UUID id=UUID.nameUUIDFromBytes((event+"|"+delivery+"|"+generation+"|"+code).getBytes(StandardCharsets.UTF_8));
        jdbc.update("WITH inserted AS (INSERT INTO notification_attention(public_id,event_id,delivery_id,code,created_at) "
            + "VALUES (?,?,?,?,?) ON CONFLICT(public_id) DO NOTHING RETURNING public_id) "
            + "INSERT INTO notification_attention_alert(attention_id,next_attempt_at) SELECT public_id,? FROM inserted",
            id,event,delivery,code,time(now),time(now));
    }
    @Override public int attentionCount() {
        Integer count=jdbc.queryForObject("SELECT count(attention_id) FROM notification_attention_alert WHERE delivered_at IS NULL",Integer.class);
        return count==null?0:count;
    }
    private static Delivery map(ResultSet r,int index) throws SQLException {
        return new Delivery(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getObject(4,UUID.class),
            r.getTimestamp(5).toInstant(),r.getObject(6,UUID.class),r.getObject(7,UUID.class),r.getString(8),r.getString(9),
            r.getObject(10,UUID.class),r.getObject(11,UUID.class),r.getLong(12),r.getString(13),r.getLong(14),r.getLong(15),
            r.getInt(16),r.getBoolean(17),r.getTimestamp(18)==null?null:r.getTimestamp(18).toInstant(),r.getTimestamp(19).toInstant());
    }
    private static Timestamp time(Instant value) { return Timestamp.from(value); }
}
