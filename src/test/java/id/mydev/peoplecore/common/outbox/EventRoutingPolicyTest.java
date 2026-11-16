package id.mydev.peoplecore.common.outbox;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventRoutingPolicyTest {
    @Test void ordinaryMasterDataDraftConfigurationAndDocumentProgressEventsNeedNoExternalConsumer() {
        for(String type:List.of("EMPLOYEE_CREATE","EMPLOYEE_UPDATE","ASSIGNMENT_ADD","ASSIGNMENT_REVISE","EMPLOYMENT_UPDATE",
            "INVITATION_ACTIVATE","ACCOUNT_OFFBOARD","ACCOUNT_REBIND","ACCOUNT_REHIRE","ACCOUNT_RESTORE",
            "PROFILE_CREATE","PROFILE_EDIT","PROFILE_MASTER_CORRECT","PROFILE_RULE_PUBLISH","PROFILE_RULE_ACTIVATE",
            "ATTENDANCE_CLOCK","ATTENDANCE_CORRECTION_CREATE","ATTENDANCE_CORRECTION_EDIT","ATTENDANCE_CORRECTION_DISCARD",
            "ATTENDANCE_RULE_PUBLISH","ATTENDANCE_RULE_ACTIVATE","SCHEDULE_PUBLISH","SCHEDULE_ASSIGN","CALENDAR_PUBLISH","CALENDAR_ASSIGN",
            "LEAVE_OPENING","LEAVE_ADJUSTMENT","LEAVE_REQUEST_CREATE","LEAVE_REQUEST_EDIT","LEAVE_REQUEST_DISCARD","LEAVE_RULE_PUBLISH","LEAVE_RULE_ACTIVATE",
            "REIMBURSEMENT_CREATE","REIMBURSEMENT_EDIT","REIMBURSEMENT_CATEGORY_PUBLISH","DOCUMENT_CREATE","DOCUMENT_RESERVE_UPLOAD","DOCUMENT_FINALIZE")) {
            assertTrue(EventRoutingPolicy.metadataOnly(type),type);
            assertFalse(EventRoutingPolicy.knownAsync(type),type);
            assertFalse(EventRoutingPolicy.notificationEvent(type),type);
        }
    }
    @Test void committedWorkflowNotificationsRemainAsyncEvenWhenTheirHandlerIsDisabled() {
        for(String type:List.of("INVITATION_ISSUE","INVITATION_REISSUE","PROFILE_SUBMIT","PROFILE_RESUBMIT","PROFILE_WITHDRAW",
            "PROFILE_APPROVE","PROFILE_REJECT","PROFILE_RETURN","PROFILE_REASSIGN","PROFILE_ROUTE_RETRY",
            "ATTENDANCE_CORRECTION_SUBMIT","ATTENDANCE_CORRECTION_RESUBMIT","ATTENDANCE_CORRECTION_WITHDRAW","ATTENDANCE_CORRECTION_APPROVE",
            "ATTENDANCE_CORRECTION_REJECT","ATTENDANCE_CORRECTION_RETURN","ATTENDANCE_CORRECTION_REASSIGN","ATTENDANCE_CORRECTION_ROUTE_RETRY",
            "LEAVE_REQUEST_SUBMIT","LEAVE_REQUEST_RESUBMIT","LEAVE_REQUEST_WITHDRAW","LEAVE_REQUEST_APPROVE","LEAVE_REQUEST_REJECT",
            "LEAVE_REQUEST_RETURN","LEAVE_REQUEST_REASSIGN","LEAVE_REQUEST_RETRY_ROUTE","LEAVE_CANCELLATION_CREATE","LEAVE_CANCELLATION_WITHDRAW",
            "LEAVE_CANCELLATION_APPROVE","LEAVE_CANCELLATION_REJECT","LEAVE_CANCELLATION_REASSIGN","LEAVE_CANCELLATION_RETRY_ROUTE",
            "REIMBURSEMENT_SUBMIT","REIMBURSEMENT_RESUBMIT","REIMBURSEMENT_WITHDRAW","REIMBURSEMENT_APPROVE","REIMBURSEMENT_REJECT",
            "REIMBURSEMENT_RETURN","REIMBURSEMENT_REASSIGN","REIMBURSEMENT_RETRY_ROUTE","REIMBURSEMENT_RECORD_PAYMENT","REIMBURSEMENT_CORRECT_PAYMENT",
            "PAYSLIP_PUBLISHED","EXPORT_READY","EXPORT_FAILED","EXPORT_EXPIRED","EXPORT_INVALIDATED")) {
            assertTrue(EventRoutingPolicy.notificationEvent(type),type);
            assertTrue(EventRoutingPolicy.knownAsync(type),type);
            assertFalse(EventRoutingPolicy.metadataOnly(type),type);
        }
    }
    @Test void exportRequestIsDurableWorkAndNotAnInformationalNotice() {
        assertTrue(EventRoutingPolicy.knownAsync("EXPORT_REQUESTED"));
        assertFalse(EventRoutingPolicy.metadataOnly("EXPORT_REQUESTED"));
        assertFalse(EventRoutingPolicy.notificationEvent("EXPORT_REQUESTED"));
    }
    @Test void missingFutureAndPrefixLookalikesDoNotAcquireAnImplicitPolicy() {
        for(String type:List.of("", "PROFILE_NEW_UNREVIEWED_ACTION", "PROFILE_EDIT_SUFFIX", "DOCUMENT_FINALIZE_AND_SEND",
            "ACCOUNT_RESTORE_UNKNOWN", "EXPORT_GENERATING", "EXPORT_ARBITRARY", "PAYSLIP_BATCH_PUBLISH", "DOCUMENT_HOLD_PLACE")) {
            assertFalse(EventRoutingPolicy.metadataOnly(type),type);
            assertFalse(EventRoutingPolicy.knownAsync(type),type);
            assertFalse(EventRoutingPolicy.notificationEvent(type),type);
        }
        assertFalse(EventRoutingPolicy.metadataOnly(null));
        assertFalse(EventRoutingPolicy.notificationEvent(null));
        assertFalse(EventRoutingPolicy.knownAsync(null));
    }
}
