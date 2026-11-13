package id.mydev.peoplecore.payslip.infrastructure.persistence;

import id.mydev.peoplecore.payslip.domain.repository.PayslipRepository;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPayslipRepository implements PayslipRepository {
    private final JdbcTemplate jdbc;

    public JdbcPayslipRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long insertBatch(UUID publicId, long createdBy) {
        Long id = jdbc.queryForObject("insert into payslip_batch(public_id,created_by,created_at,updated_at) "
            + "values(?,?,now(),now()) returning id", Long.class, publicId, createdBy);
        return id == null ? 0L : id;
    }

    @Override
    public long insertRevision(long batchId, long revisionNumber) {
        Long id = jdbc.queryForObject("insert into payslip_batch_revision(public_id,batch_id,revision_number,created_at) "
            + "values(gen_random_uuid(),?,?,now()) returning id", Long.class, batchId, revisionNumber);
        return id == null ? 0L : id;
    }

    @Override
    public void insertItem(long revisionId, long employeeId, LocalDate periodStart, long documentId,
                           long documentVersionId) {
        jdbc.update("insert into payslip_batch_item(public_id,revision_id,employee_id,period_start,document_id,"
                + "document_version_id) values(gen_random_uuid(),?,?,?,?,?)",
            revisionId, employeeId, periodStart, documentId, documentVersionId);
    }

    @Override
    public void setCurrentRevision(long batchId, long revisionId) {
        jdbc.update("update payslip_batch set current_revision_id=? where id=?", revisionId, batchId);
    }

    @Override
    public void setCurrentRevisionDraft(long batchId, long revisionId) {
        jdbc.update("update payslip_batch set current_revision_id=?,status='DRAFT',updated_at=now() where id=?",
            revisionId, batchId);
    }

    @Override
    public void reviewRevision(long revisionId, long reviewedBy) {
        jdbc.update("update payslip_batch_revision set status='REVIEWED',reviewed_by=?,reviewed_at=now() where id=?",
            reviewedBy, revisionId);
    }

    @Override
    public void markReviewed(long batchId) {
        jdbc.update("update payslip_batch set status='REVIEWED',updated_at=now() where id=?", batchId);
    }

    @Override
    public void markPublished(long batchId) {
        jdbc.update("update payslip_batch set status='PUBLISHED',updated_at=now() where id=?", batchId);
    }

    @Override
    public List<PublicationItemRecord> findPublicationItems(long revisionId) {
        return jdbc.query("select i.id,i.employee_id,i.period_start,e.public_id "
                + "from payslip_batch_item i join employees e on e.id=i.employee_id where i.revision_id=? "
                + "order by i.employee_id,i.period_start",
            (row, index) -> new PublicationItemRecord(row.getLong(1), row.getLong(2), localDate(row.getDate(3)),
                row.getObject(4, UUID.class)),
            revisionId);
    }

    @Override
    public long insertPublication(UUID publicId, long revisionId, long itemId, long employeeId, LocalDate periodStart,
                                  long publishedBy) {
        Long id = jdbc.queryForObject("insert into payslip_publication(public_id,revision_id,item_id,employee_id,"
                + "period_start,published_by,published_at) values(?,?,?,?,?,?,now()) returning id",
            Long.class, publicId, revisionId, itemId, employeeId, periodStart, publishedBy);
        return id == null ? 0L : id;
    }

    @Override
    public void upsertCurrent(long employeeId, LocalDate periodStart, long publicationId) {
        jdbc.update("insert into payslip_current(employee_id,period_start,publication_id) values(?,?,?) "
                + "on conflict(employee_id,period_start) do update set publication_id=excluded.publication_id",
            employeeId, periodStart, publicationId);
    }

    @Override
    public List<PublishedRecord> findPublished(UUID employeePublicId, UUID cursor, int limitPlusOne) {
        return jdbc.query("select p.public_id,p.period_start from payslip_current c "
                + "join employees e on e.id=c.employee_id join payslip_publication p on p.id=c.publication_id "
                + "join payslip_batch_revision r on r.id=p.revision_id join payslip_batch b on b.id=r.batch_id "
                + "join payslip_batch_item i on i.id=p.item_id join private_document d on d.id=i.document_id "
                + "where e.public_id=? and b.status='PUBLISHED' and d.employee_id=e.id and d.purpose='PAYSLIP' "
                + "and d.deletion_state='LIVE' and (?::uuid is null or p.public_id < ?::uuid) "
                + "order by p.public_id desc limit ?",
            (row, index) -> new PublishedRecord(row.getObject(1, UUID.class),
                YearMonth.from(localDate(row.getDate(2)))),
            employeePublicId, cursor, cursor, limitPlusOne);
    }

    @Override
    public Optional<VersionRecord> findPublishedVersion(UUID publicationId, UUID employeePublicId) {
        return jdbc.query("select d.public_id,v.public_id from payslip_current c "
                + "join employees e on e.id=c.employee_id join payslip_publication p on p.id=c.publication_id "
                + "join payslip_batch_revision r on r.id=p.revision_id join payslip_batch b on b.id=r.batch_id "
                + "join payslip_batch_item i on i.id=p.item_id join private_document d on d.id=i.document_id "
                + "join document_version v on v.id=i.document_version_id and v.document_id=d.id "
                + "where p.public_id=? and e.public_id=? and b.status='PUBLISHED' and d.employee_id=e.id "
                + "and d.purpose='PAYSLIP' and d.deletion_state='LIVE' and v.media_type='application/pdf'",
            (row, index) -> new VersionRecord(row.getObject(1, UUID.class), row.getObject(2, UUID.class)),
            publicationId, employeePublicId).stream().findFirst();
    }

    @Override
    public Long findItemVersion(long revisionId, UUID documentPublicId, LocalDate periodStart) {
        return jdbc.queryForObject("select i.document_version_id from payslip_batch_item i "
                + "join private_document d on d.id=i.document_id where i.revision_id=? and d.public_id=? "
                + "and i.period_start=?",
            Long.class, revisionId, documentPublicId, periodStart);
    }

    @Override
    public void lockAccount(long employeeId) {
        jdbc.update("insert into document_account(employee_id,created_at) values(?,now()) "
            + "on conflict(employee_id) do nothing", employeeId);
        jdbc.queryForObject("select id from document_account where employee_id=? for update", Long.class, employeeId);
    }

    @Override
    public Optional<SnapshotRecord> findSnapshot(UUID publicId, boolean lock) {
        String sql = "select b.id,b.status,r.id,r.revision_number from payslip_batch b join payslip_batch_revision r "
            + "on r.id=b.current_revision_id where b.public_id=?" + (lock ? " for update of b" : "");
        return jdbc.query(sql, (row, index) -> new SnapshotRecord(row.getLong(1), row.getString(2),
            row.getLong(3), row.getLong(4)), publicId).stream().findFirst();
    }

    @Override
    public List<ItemRecord> findItems(long revisionId) {
        return jdbc.query("select e.public_id,i.period_start,d.public_id from payslip_batch_item i "
                + "join employees e on e.id=i.employee_id join private_document d on d.id=i.document_id "
                + "where i.revision_id=? order by i.employee_id,i.period_start",
            (row, index) -> new ItemRecord(row.getObject(1, UUID.class),
                YearMonth.from(localDate(row.getDate(2))), row.getObject(3, UUID.class)),
            revisionId);
    }

    private static LocalDate localDate(Date date) {
        return date.toLocalDate();
    }
}
