package id.mydev.peoplecore.document.infrastructure.format;

import id.mydev.peoplecore.document.application.service.DocumentContentInspector.InvalidDocumentContentException;
import id.mydev.peoplecore.document.application.service.DocumentContentInspector;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class IsolatedDocumentFormatVerifierTest {
    private static final Path PARSER = Path.of(System.getProperty("peoplecore.document.parser.test-jar"));

    @Test
    void validatesActualPdfJpegAndPngContent() throws Exception {
        try (var verifier = verifier(Duration.ofSeconds(30))) {
            check(verifier, pdf(false), "application/pdf");
            check(verifier, image("JPEG"), "image/jpeg");
            check(verifier, image("PNG"), "image/png");
        }
    }

    @Test
    void rejectsSignatureOnlyFakeFilesAndEncryptedPdfs() throws Exception {
        byte[] fakePdf = "%PDF-garbage".getBytes(StandardCharsets.US_ASCII);
        byte[] fakePng = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1};
        byte[] fakeJpeg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1};
        try (var verifier = verifier(Duration.ofSeconds(30))) {
            assertThrows(InvalidDocumentContentException.class, () -> check(verifier, fakePdf, "application/pdf"));
            assertThrows(InvalidDocumentContentException.class, () -> check(verifier, fakePng, "image/png"));
            assertThrows(InvalidDocumentContentException.class, () -> check(verifier, fakeJpeg, "image/jpeg"));
            assertThrows(InvalidDocumentContentException.class, () -> check(verifier, pdf(true), "application/pdf"));
        }
    }

    @Test
    void rejectsCandidateThatDiffersFromTheScannedDigest() throws Exception {
        byte[] bytes = pdf(false);
        try (var verifier = verifier(Duration.ofSeconds(30))) {
            assertThrows(IOException.class, () -> verifier.verify(new ByteArrayInputStream(bytes),
                "application/pdf", bytes.length, "0".repeat(64)));
        }
    }

    @Test
    void treatsAnIncompleteOrLongerCandidateAsRetryableStorageFailure() throws Exception {
        byte[] bytes = pdf(false);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        try (var verifier = verifier(Duration.ofSeconds(30))) {
            assertThrows(IOException.class, () -> verifier.verify(new ByteArrayInputStream(bytes),
                "application/pdf", bytes.length + 1L, sha));
            assertThrows(IOException.class, () -> verifier.verify(new ByteArrayInputStream(bytes),
                "application/pdf", bytes.length - 1L, sha));
        }
    }

    @Test
    void rejectsInvalidDeclarationsBeforeLaunchingTheParser() {
        try (var verifier = verifier(Duration.ofSeconds(1))) {
            byte[] content = {1};
            String digest = "0".repeat(64);
            assertThrows(InvalidDocumentContentException.class, () -> verifier.verify(new ByteArrayInputStream(content), null, 1, digest));
            assertThrows(InvalidDocumentContentException.class, () -> verifier.verify(new ByteArrayInputStream(content), "text/plain", 1, digest));
            assertThrows(InvalidDocumentContentException.class, () -> verifier.verify(new ByteArrayInputStream(content), "application/pdf", 0, digest));
            assertThrows(InvalidDocumentContentException.class, () -> verifier.verify(new ByteArrayInputStream(content), "application/pdf", DocumentContentInspector.MAX_BYTES + 1, digest));
            assertThrows(InvalidDocumentContentException.class, () -> verifier.verify(new ByteArrayInputStream(content), "application/pdf", 1, null));
            assertThrows(InvalidDocumentContentException.class, () -> verifier.verify(new ByteArrayInputStream(content), "application/pdf", 1, "invalid"));
        }
    }

    @Test
    void rejectsInvalidParserConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new IsolatedDocumentFormatVerifier(
            Path.of("missing-parser.jar"), Duration.ofSeconds(1), 1, 1024, List.of()));
        assertThrows(IllegalArgumentException.class, () -> verifier(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> verifier(Duration.ofNanos(1)));
        assertThrows(IllegalArgumentException.class, () -> new IsolatedDocumentFormatVerifier(
            PARSER, Duration.ofSeconds(1), 0, 1024, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new IsolatedDocumentFormatVerifier(
            PARSER, Duration.ofSeconds(1), 1, 0, List.of()));
    }

    @Test
    void abortsWhenInputStallsBeyondTheDeadline() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        InputStream source = new InputStream() {
            @Override
            public int read() throws IOException {
                try {
                    closed.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException(exception);
                }
                return -1;
            }

            @Override
            public void close() {
                closed.countDown();
            }
        };
        try (var verifier = verifier(Duration.ofMillis(100))) {
            assertThrows(IOException.class, () -> verifier.verify(source, "application/pdf", 1, "0".repeat(64)));
        }
    }

    private static IsolatedDocumentFormatVerifier verifier(Duration timeout) {
        return new IsolatedDocumentFormatVerifier(PARSER, timeout, 1, 128L * 1024 * 1024, List.of());
    }

    private static void check(IsolatedDocumentFormatVerifier verifier, byte[] bytes, String type) throws Exception {
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        verifier.verify(new ByteArrayInputStream(bytes), type, bytes.length, sha);
    }

    private static byte[] pdf(boolean encrypted) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            if (encrypted) {
                document.protect(new StandardProtectionPolicy("owner-secret", "user-secret", new AccessPermission()));
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] image(String type) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (!ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), type, out)) {
                throw new IOException("Image encoder unavailable");
            }
            return out.toByteArray();
        }
    }
}
