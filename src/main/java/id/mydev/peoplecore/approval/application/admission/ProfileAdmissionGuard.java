package id.mydev.peoplecore.approval.application.admission;

import id.mydev.peoplecore.common.command.AdmissionGuard;
import id.mydev.peoplecore.approval.domain.repository.ProfileAdmissionLockRepository;
import id.mydev.peoplecore.common.command.CommandBusyException;
import id.mydev.peoplecore.common.command.CommandExecutionService;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

@Component
public class ProfileAdmissionGuard implements AdmissionGuard {

    static final long ADMISSION_KEY = ByteBuffer.wrap(HexFormat.of().parseHex(
        CommandExecutionService.computeSha256("profile-admission"))).getLong();

    private static final String DOMAIN_LOCK_TIMEOUT = "2s";

    private static final Set<String> SHARED_COMMANDS = Set.of(
        "PROFILE_CREATE",
        "PROFILE_EDIT",
        "PROFILE_SUBMIT",
        "PROFILE_RESUBMIT",
        "PROFILE_WITHDRAW",
        "PROFILE_APPROVE",
        "PROFILE_REJECT",
        "PROFILE_RETURN",
        "PROFILE_REASSIGN",
        "PROFILE_ROUTE_RETRY",
        "PROFILE_MASTER_CORRECT",
        "WORK_CONTEXT_QUERY",
        "OCCURRENCE_RESOLVE",
        "ATTENDANCE_CLOCK",
        "ATTENDANCE_CORRECTION_CREATE",
        "ATTENDANCE_CORRECTION_EDIT",
        "ATTENDANCE_CORRECTION_DISCARD",
        "ATTENDANCE_CORRECTION_SUBMIT",
        "ATTENDANCE_CORRECTION_RESUBMIT",
        "ATTENDANCE_CORRECTION_WITHDRAW",
        "ATTENDANCE_CORRECTION_APPROVE",
        "ATTENDANCE_CORRECTION_REJECT",
        "ATTENDANCE_CORRECTION_RETURN",
        "ATTENDANCE_CORRECTION_REASSIGN",
        "ATTENDANCE_CORRECTION_ROUTE_RETRY",
        "LEAVE_OPENING",
        "LEAVE_ADJUSTMENT",
        "LEAVE_REQUEST_CREATE",
        "LEAVE_REQUEST_EDIT",
        "LEAVE_REQUEST_DISCARD",
        "LEAVE_REQUEST_SUBMIT",
        "LEAVE_REQUEST_RESUBMIT",
        "LEAVE_REQUEST_WITHDRAW",
        "LEAVE_REQUEST_APPROVE",
        "LEAVE_REQUEST_REJECT",
        "LEAVE_REQUEST_RETURN",
        "LEAVE_REQUEST_REASSIGN",
        "LEAVE_REQUEST_RETRY_ROUTE",
        "LEAVE_CANCELLATION_CREATE",
        "LEAVE_CANCELLATION_WITHDRAW",
        "LEAVE_CANCELLATION_APPROVE",
        "LEAVE_CANCELLATION_REJECT",
        "LEAVE_CANCELLATION_REASSIGN",
        "LEAVE_CANCELLATION_RETRY_ROUTE",
        "PAYSLIP_BATCH_CREATE",
        "PAYSLIP_BATCH_EDIT",
        "PAYSLIP_BATCH_REVIEW",
        "PAYSLIP_BATCH_PUBLISH",
        "DOCUMENT_CREATE",
        "DOCUMENT_RESERVE_UPLOAD",
        "DOCUMENT_FINALIZE",
        "DOCUMENT_HOLD_PLACE",
        "DOCUMENT_HOLD_RELEASE",
        "DOCUMENT_DELETE_CLAIM",
        "DOCUMENT_DELETE_RESTORE",
        "REIMBURSEMENT_CREATE",
        "REIMBURSEMENT_EDIT",
        "REIMBURSEMENT_SUBMIT",
        "REIMBURSEMENT_RESUBMIT",
        "REIMBURSEMENT_WITHDRAW",
        "REIMBURSEMENT_REASSIGN",
        "REIMBURSEMENT_RETRY_ROUTE",
        "REIMBURSEMENT_APPROVE",
        "REIMBURSEMENT_REJECT",
        "REIMBURSEMENT_RETURN",
        "REIMBURSEMENT_RECORD_PAYMENT",
        "REIMBURSEMENT_CORRECT_PAYMENT");

    private static final Set<String> EXCLUSIVE_COMMANDS = Set.of(
        "PROFILE_RULE_PUBLISH",
        "PROFILE_RULE_ACTIVATE",
        "EMPLOYEE_CREATE",
        "EMPLOYEE_UPDATE",
        "ASSIGNMENT_ADD",
        "ASSIGNMENT_REVISE",
        "EMPLOYMENT_UPDATE",
        "INVITATION_ISSUE",
        "INVITATION_REISSUE",
        "INVITATION_ACTIVATE",
        "ACCOUNT_OFFBOARD",
        "ACCOUNT_REBIND",
        "ACCOUNT_REHIRE",
        "ACCOUNT_RESTORE",
        "SCHEDULE_PUBLISH",
        "CALENDAR_PUBLISH",
        "SCHEDULE_ASSIGN",
        "CALENDAR_ASSIGN",
        "ATTENDANCE_RULE_PUBLISH",
        "ATTENDANCE_RULE_ACTIVATE",
        "LEAVE_RULE_PUBLISH",
        "LEAVE_RULE_ACTIVATE",
        "REIMBURSEMENT_CATEGORY_PUBLISH");

    private final ProfileAdmissionLockRepository locks;

    public ProfileAdmissionGuard(ProfileAdmissionLockRepository locks) {
        this.locks = Objects.requireNonNull(locks, "locks must not be null");
    }

    @Override
    public Mode modeForCommand(String commandType) {
        if (commandType == null) {
            return null;
        }
        if (SHARED_COMMANDS.contains(commandType)) {
            return Mode.SHARED;
        }
        if (EXCLUSIVE_COMMANDS.contains(commandType)) {
            return Mode.EXCLUSIVE;
        }
        return null;
    }

    @Override
    public void acquire(Mode mode) {
        locks.acquire(mode);
    }
}
