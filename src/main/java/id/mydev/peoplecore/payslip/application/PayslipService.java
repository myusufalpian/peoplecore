package id.mydev.peoplecore.payslip.application;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.document.application.service.VerifiedDocumentAccess;
import id.mydev.peoplecore.document.application.service.VerifiedDocumentAccess.Purpose;
import id.mydev.peoplecore.identity.application.service.AccountAccessService;
import id.mydev.peoplecore.identity.domain.model.HrisRole;
import id.mydev.peoplecore.identity.domain.model.UserAccount;
import id.mydev.peoplecore.organization.application.service.EmployeeService;
import id.mydev.peoplecore.organization.domain.model.Employee;
import id.mydev.peoplecore.payslip.application.PayslipModels.Detail;
import id.mydev.peoplecore.payslip.application.PayslipModels.Draft;
import id.mydev.peoplecore.payslip.application.PayslipModels.Item;
import id.mydev.peoplecore.payslip.application.PayslipModels.Mutation;
import id.mydev.peoplecore.payslip.application.PayslipModels.Result;
import id.mydev.peoplecore.payslip.application.PayslipModels.Page;
import id.mydev.peoplecore.payslip.application.PayslipModels.Published;
import id.mydev.peoplecore.payslip.application.PayslipModels.PublishedVersion;
import jakarta.persistence.EntityManager;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class PayslipService {
    private final JdbcTemplate jdbc;
    private final EmployeeService employees;
    private final AccountAccessService access;
    private final VerifiedDocumentAccess documents;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final EntityManager entities;
    private final AdmissionGuard admission;

    public PayslipService(JdbcTemplate jdbc, EmployeeService employees, AccountAccessService access,
                          VerifiedDocumentAccess documents, Clock clock, ObjectMapper mapper, EntityManager entities,
                          AdmissionGuard admission) {
        this.jdbc = jdbc;
        this.employees = employees;
        this.access = access;
        this.documents = documents;
        this.clock = clock;
        this.mapper = mapper;
        this.entities = entities;
        this.admission = admission;
    }

    public void verifyCommand(String type, JsonNode payload, Authentication caller) {
        Mutation mutation = mapper.readValue(payload.toString(), Mutation.class);
        if (mutation.batchPublicId() == null) throw new IllegalArgumentException("Batch ID is required");
        if ("PAYSLIP_BATCH_CREATE".equals(type)) {
            authorize(caller, validate(mutation.draft()));
            return;
        }
        Snapshot batch = snapshot(mutation.batchPublicId(), false);
        authorize(caller, batch.items());
        if ("PAYSLIP_BATCH_EDIT".equals(type)) authorize(caller, validate(mutation.draft()));
    }

    public void verifyReplay(String type, JsonNode payload, String recordedResult, Authentication caller) {
        Mutation mutation = mapper.readValue(payload.toString(), Mutation.class);
        requireReplayScope(mutation.batchPublicId(), caller);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Result create(UUID publicId, Draft draft, Authentication caller) {
        List<Item> items = validate(draft);
        List<Employee> targets = authorize(caller, items);
        lockEmployees(targets);
        validateEmploymentPeriods(items, authorize(caller, items), false);
        List<VerifiedDocumentAccess.VerifiedVersion> versions = lockDocuments(items);
        authorize(caller, items);
        UserAccount actor = access.requireActive(caller, clock.instant());
        long batch = jdbc.queryForObject("insert into payslip_batch(public_id,created_by,created_at,updated_at) "
            + "values(?,?,now(),now()) returning id", Long.class, publicId, actor.getId());
        long revision = insertRevision(batch, 1, items, targets, versions);
        jdbc.update("update payslip_batch set current_revision_id=? where id=?", revision, batch);
        authorize(caller, items);
        return new Result(publicId, "DRAFT", 1);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Result edit(UUID publicId, Draft draft, long expected, Authentication caller) {
        List<Item> proposed = validate(draft);
        Snapshot before = snapshot(publicId, false);
        List<Employee> targets = authorize(caller, concat(before.items(), proposed));
        lockEmployees(targets);
        Snapshot current = snapshot(publicId, true);
        requireSame(before, current, expected);
        if ("PUBLISHED".equals(current.status())) throw conflict();
        validateEmploymentPeriods(proposed, authorize(caller, proposed), false);
        List<VerifiedDocumentAccess.VerifiedVersion> versions = lockDocuments(proposed);
        authorize(caller, concat(current.items(), proposed));
        long revision = insertRevision(current.batchId(), expected + 1, proposed, targets, versions);
        jdbc.update("update payslip_batch set current_revision_id=?,status='DRAFT',updated_at=now() where id=?", revision, current.batchId());
        authorize(caller, concat(current.items(), proposed));
        return new Result(publicId, "DRAFT", expected + 1);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Result review(UUID publicId, long expected, Authentication caller) {
        Snapshot current = lockBatch(publicId, expected, caller);
        if (!"DRAFT".equals(current.status())) throw conflict();
        validateEmploymentPeriods(current.items(), authorize(caller, current.items()), false);
        requireVersions(current);
        UserAccount actor = access.requireActive(caller, clock.instant());
        authorize(caller, current.items());
        jdbc.update("update payslip_batch_revision set status='REVIEWED',reviewed_by=?,reviewed_at=now() where id=?", actor.getId(), current.revisionId());
        jdbc.update("update payslip_batch set status='REVIEWED',updated_at=now() where id=?", current.batchId());
        authorize(caller, current.items());
        return new Result(publicId, "REVIEWED", current.revision());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Result publish(UUID publicId, long expected, Authentication caller) {
        Snapshot current = lockBatch(publicId, expected, caller);
        if (!"REVIEWED".equals(current.status())) throw conflict();
        validateEmploymentPeriods(current.items(), authorize(caller, current.items()), true);
        requireVersions(current);
        UserAccount actor = access.requireActive(caller, clock.instant());
        authorize(caller, current.items());
        List<PublicationItem> rows = jdbc.query("select i.id,i.employee_id,i.period_start from payslip_batch_item i "
            + "where i.revision_id=? order by i.employee_id,i.period_start", (row, index) ->
            new PublicationItem(row.getLong(1), row.getLong(2), row.getDate(3)), current.revisionId());
        for (PublicationItem item : rows) {
            long publication = jdbc.queryForObject("insert into payslip_publication(public_id,revision_id,item_id,employee_id,period_start,published_by,published_at) "
                + "values(gen_random_uuid(),?,?,?,?,?,now()) returning id", Long.class,
                current.revisionId(), item.id(), item.employeeId(), item.period(), actor.getId());
            jdbc.update("insert into payslip_current(employee_id,period_start,publication_id) values(?,?,?) "
                + "on conflict(employee_id,period_start) do update set publication_id=excluded.publication_id",
                item.employeeId(), item.period(), publication);
        }
        jdbc.update("update payslip_batch set status='PUBLISHED',updated_at=now() where id=?", current.batchId());
        authorize(caller, current.items());
        return new Result(publicId, "PUBLISHED", current.revision());
    }

    @Transactional
    public Detail detail(UUID publicId, Authentication caller) {
        admission.acquire(AdmissionGuard.Mode.SHARED);
        entities.flush();
        entities.clear();
        access.requireActive(caller, clock.instant());
        Snapshot current = snapshot(publicId, false);
        authorize(caller, current.items());
        Snapshot locked = snapshot(publicId, true);
        authorize(caller, locked.items());
        return new Detail(publicId, locked.status(), locked.revision(), locked.items());
    }

    @Transactional
    public Page published(Authentication caller, int limit, UUID cursor) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Limit must be between 1 and 100");
        admission.acquire(AdmissionGuard.Mode.SHARED);
        entities.flush();
        entities.clear();
        UUID employeeId = requireOwnEmployee(caller);
        List<Published> rows = jdbc.query("select p.public_id,p.period_start from payslip_current c "
                + "join employees e on e.id=c.employee_id join payslip_publication p on p.id=c.publication_id "
                + "join payslip_batch_revision r on r.id=p.revision_id join payslip_batch b on b.id=r.batch_id "
                + "join payslip_batch_item i on i.id=p.item_id join private_document d on d.id=i.document_id "
                + "where e.public_id=? and b.status='PUBLISHED' and d.employee_id=e.id and d.purpose='PAYSLIP' "
                + "and d.deletion_state='LIVE' "
                + "and (?::uuid is null or p.public_id < ?::uuid) order by p.public_id desc limit ?",
            (row, index) -> new Published(row.getObject(1, UUID.class), YearMonth.from(row.getDate(2).toLocalDate())),
            employeeId, cursor, cursor, limit + 1);
        if (!employeeId.equals(requireOwnEmployee(caller))) throw new ResourceNotFoundException();
        boolean hasMore = rows.size() > limit;
        List<Published> page = List.copyOf(hasMore ? rows.subList(0, limit) : rows);
        return new Page(page, hasMore ? page.get(page.size() - 1).publicId() : null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PublishedVersion requirePublishedVersion(UUID publicationId, Authentication caller) {
        if (publicationId == null) throw new IllegalArgumentException("Publication ID is required");
        admission.acquire(AdmissionGuard.Mode.SHARED);
        entities.flush();
        entities.clear();
        UUID employeeId = requireOwnEmployee(caller);
        List<PublishedVersion> versions = jdbc.query("select d.public_id,v.public_id from payslip_current c "
                + "join employees e on e.id=c.employee_id join payslip_publication p on p.id=c.publication_id "
                + "join payslip_batch_revision r on r.id=p.revision_id join payslip_batch b on b.id=r.batch_id "
                + "join payslip_batch_item i on i.id=p.item_id join private_document d on d.id=i.document_id "
                + "join document_version v on v.id=i.document_version_id and v.document_id=d.id "
                + "where p.public_id=? and e.public_id=? and b.status='PUBLISHED' and d.employee_id=e.id "
                + "and d.purpose='PAYSLIP' and d.deletion_state='LIVE' and v.media_type='application/pdf'",
            (row, index) -> new PublishedVersion(employeeId, row.getObject(1, UUID.class), row.getObject(2, UUID.class)),
            publicationId, employeeId);
        PublishedVersion version = versions.stream().findFirst().orElseThrow(ResourceNotFoundException::new);
        if (!employeeId.equals(requireOwnEmployee(caller))) throw new ResourceNotFoundException();
        return version;
    }

    private UUID requireOwnEmployee(Authentication caller) {
        Instant now = clock.instant();
        UserAccount actor = access.requireActive(caller, now);
        UUID employeeId = access.ownEmployeePublicId(actor).orElseThrow(ResourceNotFoundException::new);
        Employee employee = employees.requireInternalByPublicId(employeeId);
        access.requireEmployeeRead(actor, employeeId, employees.currentOrgUnit(employee.getId(), now).orElse(null), now);
        if (access.activeRoles(actor.getId(), now).isEmpty()) throw new ResourceNotFoundException();
        return employeeId;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireReplayScope(UUID publicId, Authentication caller) {
        authorize(caller, snapshot(publicId, false).items());
    }

    private Snapshot lockBatch(UUID id, long expected, Authentication caller) {
        Snapshot before = snapshot(id, false);
        lockEmployees(authorize(caller, before.items()));
        Snapshot current = snapshot(id, true);
        requireSame(before, current, expected);
        return current;
    }

    private void requireVersions(Snapshot current) {
        var versions = lockDocuments(current.items());
        Map<UUID, Long> ids = versions.stream().collect(Collectors.toMap(VerifiedDocumentAccess.VerifiedVersion::documentPublicId,
            VerifiedDocumentAccess.VerifiedVersion::versionId, (first, second) -> first));
        for (Item item : current.items()) {
            Long expected = jdbc.queryForObject("select i.document_version_id from payslip_batch_item i join private_document d on d.id=i.document_id "
                + "where i.revision_id=? and d.public_id=? and i.period_start=?", Long.class,
                current.revisionId(), item.documentPublicId(), item.period().atDay(1));
            if (!expected.equals(ids.get(item.documentPublicId()))) throw conflict();
        }
    }

    private List<VerifiedDocumentAccess.VerifiedVersion> lockDocuments(List<Item> items) {
        return documents.requireAllForUpdate(items.stream().map(item ->
            new VerifiedDocumentAccess.Request(item.documentPublicId(), item.employeePublicId(), Purpose.PAYSLIP)).toList());
    }

    private List<Employee> authorize(Authentication caller, List<Item> items) {
        if (items.isEmpty()) throw new ResourceNotFoundException();
        Instant now = clock.instant();
        UserAccount actor = access.requireActive(caller, now);
        return items.stream().map(item -> {
            Employee employee = employees.requireInternalByPublicId(item.employeePublicId());
            String unit = employees.currentOrgUnit(employee.getId(), now).orElse(null);
            if (!access.hasScopedRole(actor.getId(), HrisRole.HR_ADMIN, unit, now)
                && !access.hasScopedRole(actor.getId(), HrisRole.FINANCE_PAYROLL, unit, now)) throw new ResourceNotFoundException();
            return employee;
        }).distinct().sorted(Comparator.comparing(Employee::getId)).toList();
    }

    private void lockEmployees(List<Employee> targets) {
        for (Employee employee : targets) {
            jdbc.update("insert into document_account(employee_id,created_at) values(?,now()) on conflict(employee_id) do nothing", employee.getId());
            jdbc.queryForObject("select id from document_account where employee_id=? for update", Long.class, employee.getId());
        }
        entities.flush();
        entities.clear();
    }

    private static void validateEmploymentPeriods(List<Item> items, List<Employee> targets, boolean requireStart) {
        Map<UUID, Employee> employeeMap = targets.stream()
            .collect(Collectors.toMap(Employee::getPublicId, Function.identity()));
        for (Item item : items) {
            Employee employee = employeeMap.get(item.employeePublicId());
            var start = employee.getEmploymentStartDate();
            var end = employee.getEmploymentEndDate();
            if (requireStart && start == null) {
                throw new IllegalArgumentException("Employment start date is required for publication");
            }
            if ((start != null && start.isAfter(item.period().atEndOfMonth()))
                || (end != null && end.isBefore(item.period().atDay(1)))) {
                throw new IllegalArgumentException("Payslip period does not overlap employment");
            }
        }
    }

    private long insertRevision(long batch, long number, List<Item> items, List<Employee> targets,
                                List<VerifiedDocumentAccess.VerifiedVersion> versions) {
        long revision = jdbc.queryForObject("insert into payslip_batch_revision(public_id,batch_id,revision_number,created_at) "
            + "values(gen_random_uuid(),?,?,now()) returning id", Long.class, batch, number);
        Map<UUID, Employee> employeeMap = targets.stream().collect(Collectors.toMap(Employee::getPublicId, Function.identity()));
        Map<UUID, VerifiedDocumentAccess.VerifiedVersion> documentMap = versions.stream().collect(Collectors.toMap(
            VerifiedDocumentAccess.VerifiedVersion::documentPublicId, Function.identity(), (first, second) -> first));
        for (Item item : items) {
            var document = documentMap.get(item.documentPublicId());
            jdbc.update("insert into payslip_batch_item(public_id,revision_id,employee_id,period_start,document_id,document_version_id) "
                + "values(gen_random_uuid(),?,?,?,?,?)", revision, employeeMap.get(item.employeePublicId()).getId(),
                item.period().atDay(1), document.documentId(), document.versionId());
        }
        return revision;
    }

    private Snapshot snapshot(UUID publicId, boolean lock) {
        String sql = "select b.id,b.status,r.id,r.revision_number from payslip_batch b join payslip_batch_revision r "
            + "on r.id=b.current_revision_id where b.public_id=?" + (lock ? " for update of b" : "");
        List<Snapshot> rows = jdbc.query(sql, (row, index) -> new Snapshot(row.getLong(1), row.getString(2),
            row.getLong(3), row.getLong(4), List.of()), publicId);
        Snapshot batch = rows.stream().findFirst().orElseThrow(ResourceNotFoundException::new);
        List<Item> items = jdbc.query("select e.public_id,i.period_start,d.public_id from payslip_batch_item i "
            + "join employees e on e.id=i.employee_id join private_document d on d.id=i.document_id "
            + "where i.revision_id=? order by i.employee_id,i.period_start", (row, index) ->
            new Item(row.getObject(1, UUID.class), YearMonth.from(row.getDate(2).toLocalDate()), row.getObject(3, UUID.class)), batch.revisionId());
        return new Snapshot(batch.batchId(), batch.status(), batch.revisionId(), batch.revision(), items);
    }

    private static List<Item> validate(Draft draft) {
        if (draft == null || draft.items() == null || draft.items().isEmpty() || draft.items().size() > 100)
            throw new IllegalArgumentException("A batch requires 1 to 100 items");
        var keys = new HashSet<String>();
        for (Item item : draft.items()) {
            if (item == null || item.employeePublicId() == null || item.documentPublicId() == null || item.period() == null
                || !keys.add(item.employeePublicId() + ":" + item.period()))
                throw new IllegalArgumentException("Invalid or duplicate payslip mapping");
        }
        return List.copyOf(draft.items());
    }

    private static List<Item> concat(List<Item> first, List<Item> second) {
        return java.util.stream.Stream.concat(first.stream(), second.stream()).toList();
    }

    private static void requireSame(Snapshot before, Snapshot current, long expected) {
        if (current.revision() != expected || current.revisionId() != before.revisionId()) throw conflict();
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Payslip batch changed");
    }

    private record Snapshot(long batchId, String status, long revisionId, long revision, List<Item> items) { }
    private record PublicationItem(long id, long employeeId, Date period) { }
}
