package id.mydev.peoplecore.document.application.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentContentInspectorTest {
    private final DocumentContentInspector inspector = new DocumentContentInspector();

    @Test
    void validatesAndForwardsTheSamePdfBytes() throws Exception {
        byte[] bytes = "%PDF-1.7\ncontent".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream forwarded = new ByteArrayOutputStream();

        var result = inspector.inspect(new ByteArrayInputStream(bytes), forwarded, "application/pdf", bytes.length);

        assertArrayEquals(bytes, forwarded.toByteArray());
        assertEquals(bytes.length, result.byteSize());
        assertEquals(sha256(bytes), result.sha256());
    }

    @Test
    void acceptsPngAndJpegSignatures() throws Exception {
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1};
        byte[] jpeg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1};

        assertEquals("image/png", inspect(png, "image/png", png.length).mediaType());
        assertEquals("image/jpeg", inspect(jpeg, "image/jpeg", jpeg.length).mediaType());
    }

    @Test
    void rejectsWrongSignatureAndLength() {
        byte[] bytes = "%PDF-1.7".getBytes(StandardCharsets.US_ASCII);
        assertThrows(DocumentContentInspector.InvalidDocumentContentException.class, () -> inspect(bytes, "image/png", bytes.length));
        assertThrows(DocumentContentInspector.InvalidDocumentContentException.class, () -> inspect(bytes, "application/pdf", bytes.length - 1));
        assertThrows(DocumentContentInspector.InvalidDocumentContentException.class, () -> inspect(bytes, "application/pdf", bytes.length + 1));
    }

    @Test
    void rejectsInvalidDeclarationsBeforeReading() {
        byte[] bytes = {1};
        assertThrows(DocumentContentInspector.InvalidDocumentContentException.class, () -> inspect(bytes, "text/plain", 1));
        assertThrows(DocumentContentInspector.InvalidDocumentContentException.class, () -> inspect(bytes, "application/pdf", 0));
        assertThrows(DocumentContentInspector.InvalidDocumentContentException.class, () -> inspect(bytes, "application/pdf", DocumentContentInspector.MAX_BYTES + 1));
    }

    private DocumentContentInspector.Result inspect(byte[] bytes, String type, long size) throws Exception {
        return inspector.inspect(new ByteArrayInputStream(bytes), new ByteArrayOutputStream(), type, size);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
