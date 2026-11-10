package id.mydev.peoplecore.document.application.service;

import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.document.application.service.VerifiedDocumentAccess.Purpose;
import id.mydev.peoplecore.document.infrastructure.storage.PrivateDocumentStorage;
import java.io.ByteArrayInputStream;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DocumentVersionContentServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<PrivateDocumentStorage> provider = mock(ObjectProvider.class);
    private final DocumentVersionContentService service = new DocumentVersionContentService(jdbc, provider);

    @Test
    void inaccessibleVersionDoesNotOpenStorage() {
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
            any(), any(), any(), any())).thenReturn(List.of());
        assertThrows(ResourceNotFoundException.class,
            () -> service.open(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Purpose.PAYSLIP));
        verifyNoInteractions(provider);
    }

    @Test
    void opensThePinnedVersionAndFailsClosedWithoutStorage() {
        UUID document = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        UUID employee = UUID.randomUUID();
        UUID attempt = UUID.randomUUID();
        doAnswer(call -> {
            ResultSet row = mock(ResultSet.class);
            when(row.getObject(1, UUID.class)).thenReturn(attempt);
            when(row.getString(2)).thenReturn("application/pdf");
            when(row.getLong(3)).thenReturn(12L);
            RowMapper<?> mapper = call.getArgument(1);
            return List.of(mapper.mapRow(row, 0));
        }).when(jdbc).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
            eq(document), eq(version), eq(employee), eq("PAYSLIP"));
        assertThrows(ResponseStatusException.class, () -> service.open(document, version, employee, Purpose.PAYSLIP));

        PrivateDocumentStorage storage = mock(PrivateDocumentStorage.class);
        ByteArrayInputStream bytes = new ByteArrayInputStream(new byte[] {1});
        when(provider.getIfAvailable()).thenReturn(storage);
        when(storage.openCandidate(attempt)).thenReturn(bytes);
        var content = service.open(document, version, employee, Purpose.PAYSLIP);
        assertSame(bytes, content.body());
        assertEquals("application/pdf", content.mediaType());
        assertEquals(12, content.byteSize());
    }
}
