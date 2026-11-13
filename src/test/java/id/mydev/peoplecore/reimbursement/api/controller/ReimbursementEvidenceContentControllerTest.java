package id.mydev.peoplecore.reimbursement.api.controller;

import id.mydev.peoplecore.document.application.service.DocumentTarget;
import id.mydev.peoplecore.document.application.service.DocumentVersionContentService;
import id.mydev.peoplecore.document.application.service.RevocableDocumentTransfer;
import id.mydev.peoplecore.document.domain.model.Purpose;
import id.mydev.peoplecore.reimbursement.application.command.ReimbursementCommands;
import id.mydev.peoplecore.reimbursement.application.service.ReimbursementCategoryService;
import id.mydev.peoplecore.reimbursement.application.service.ReimbursementWorkflowService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReimbursementEvidenceContentControllerTest {
    private final ReimbursementWorkflowService workflow = mock(ReimbursementWorkflowService.class);
    private final DocumentVersionContentService content = mock(DocumentVersionContentService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<RevocableDocumentTransfer> transfers = mock(ObjectProvider.class);
    private final Authentication caller = mock(Authentication.class);
    private final UUID claimId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private final DocumentTarget target = new DocumentTarget(documentId, UUID.randomUUID(), UUID.randomUUID(),
        "image/jpeg");
    private final ReimbursementController controller = new ReimbursementController(
        mock(ReimbursementCommands.class), workflow, mock(ReimbursementCategoryService.class), content, transfers);

    @Test
    void reviewerReceivesTheLinkedEvidenceWithItsVerMediaType() throws Exception {
        byte[] bytes = {7, 8, 9};
        try (var transfer = new RevocableDocumentTransfer(1, Duration.ofSeconds(1), Duration.ofSeconds(1))) {
            when(transfers.getIfAvailable()).thenReturn(transfer);
            when(workflow.requireEvidenceContent(claimId, documentId, caller)).thenReturn(target);
            when(content.open(target.documentPublicId(), target.versionPublicId(), target.employeePublicId(),
                Purpose.CLAIM_EVIDENCE))
                .thenReturn(new DocumentVersionContentService.Content(new ByteArrayInputStream(bytes),
                    "image/jpeg", bytes.length));

            var response = controller.evidenceContent(claimId, documentId, caller);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            response.getBody().writeTo(output);

            assertArrayEquals(bytes, output.toByteArray());
            assertEquals("image/jpeg", response.getHeaders().getContentType().toString());
            assertEquals("no-store", response.getHeaders().getCacheControl());
            assertTrue(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).startsWith("attachment"));
        }
    }

    @Test
    void changedClaimEvidenceStopsTheStreamBeforeContentIsOpened() {
        var replacement = new DocumentTarget(documentId, UUID.randomUUID(), target.employeePublicId(), "image/jpeg");
        try (var transfer = new RevocableDocumentTransfer(1, Duration.ofSeconds(1), Duration.ofSeconds(1))) {
            when(transfers.getIfAvailable()).thenReturn(transfer);
            when(workflow.requireEvidenceContent(claimId, documentId, caller)).thenReturn(target, replacement);
            var response = controller.evidenceContent(claimId, documentId, caller);

            assertThrows(IOException.class, () -> response.getBody().writeTo(new ByteArrayOutputStream()));
            verify(content, never()).open(target.documentPublicId(), target.versionPublicId(),
                target.employeePublicId(), Purpose.CLAIM_EVIDENCE);
        }
    }

    @Test
    void missingTransferConfigurationFailsClosed() {
        when(workflow.requireEvidenceContent(claimId, documentId, caller)).thenReturn(target);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> controller.evidenceContent(claimId, documentId, caller));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
        verify(content, never()).open(target.documentPublicId(), target.versionPublicId(),
            target.employeePublicId(), Purpose.CLAIM_EVIDENCE);
    }
}
