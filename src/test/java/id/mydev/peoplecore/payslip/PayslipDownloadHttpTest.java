package id.mydev.peoplecore.payslip;

import id.mydev.peoplecore.document.application.service.DocumentVersionContentService;
import id.mydev.peoplecore.document.application.service.RevocableDocumentTransfer;
import id.mydev.peoplecore.document.application.service.VerifiedDocumentAccess.Purpose;
import id.mydev.peoplecore.payslip.api.PayslipDownloadController;
import id.mydev.peoplecore.payslip.application.PayslipModels.PublishedVersion;
import id.mydev.peoplecore.payslip.application.PayslipService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PayslipDownloadHttpTest {
    private static final int SENT_BEFORE_REVOCATION = 16384;

    @Test
    void revocationStopsARealHttpStreamWhenTheUpstreamReadIsStalled(@TempDir Path directory) throws Exception {
        UUID publication = UUID.randomUUID();
        var version = new PublishedVersion(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        Authentication caller = mock(Authentication.class);
        PayslipService payslips = mock(PayslipService.class);
        DocumentVersionContentService content = mock(DocumentVersionContentService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<RevocableDocumentTransfer> transfers = mock(ObjectProvider.class);
        AtomicBoolean allowed = new AtomicBoolean(true);
        StalledSource source = new StalledSource();
        CountDownLatch finished = new CountDownLatch(1);
        when(payslips.requirePublishedVersion(publication, caller)).thenAnswer(invocation -> {
            if (!allowed.get()) {
                throw new AccessDeniedException("Access ended");
            }
            return version;
        });
        when(content.open(version.documentPublicId(), version.versionPublicId(), version.employeePublicId(), Purpose.PAYSLIP))
            .thenReturn(new DocumentVersionContentService.Content(source, "application/pdf", SENT_BEFORE_REVOCATION + 1L));

        try (var transfer = new RevocableDocumentTransfer(1, Duration.ofMillis(25), Duration.ofMillis(250))) {
            when(transfers.getIfAvailable()).thenReturn(transfer);
            PayslipDownloadController controller = new PayslipDownloadController(payslips, content, transfers);
            Tomcat tomcat = new Tomcat();
            tomcat.setBaseDir(directory.toString());
            tomcat.setPort(0);
            tomcat.getConnector().setProperty("address", "127.0.0.1");
            Context context = tomcat.addContext("", directory.toString());
            Tomcat.addServlet(context, "download", new HttpServlet() {
                @Override
                protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
                    response.setBufferSize(1024);
                    response.setContentType("application/pdf");
                    try {
                        controller.content(caller, publication).getBody().writeTo(response.getOutputStream());
                    } catch (IOException | RuntimeException exception) {
                        response.getOutputStream().close();
                    } finally {
                        finished.countDown();
                    }
                }
            });
            context.addServletMappingDecoded("/content", "download");
            try {
                tomcat.start();
                URI url = URI.create("http://127.0.0.1:" + tomcat.getConnector().getLocalPort() + "/content");
                HttpURLConnection connection = (HttpURLConnection) url.toURL().openConnection();
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(3000);
                try (InputStream body = connection.getInputStream()) {
                    assertEquals(200, connection.getResponseCode());
                    assertTrue(body.read() >= 0);
                    assertTrue(source.stalled.await(3, TimeUnit.SECONDS));
                    allowed.set(false);
                    assertTrue(source.closed.await(3, TimeUnit.SECONDS));
                    assertTrue(finished.await(3, TimeUnit.SECONDS));
                    int received = 1;
                    byte[] buffer = new byte[4096];
                    try {
                        int count;
                        while ((count = body.read(buffer)) != -1) {
                            received += count;
                        }
                    } catch (SocketTimeoutException exception) {
                        throw exception;
                    } catch (IOException exception) {
                        // A reset connection is an acceptable cancellation outcome.
                    }
                    assertTrue(received <= SENT_BEFORE_REVOCATION);
                } finally {
                    connection.disconnect();
                }
            } finally {
                tomcat.stop();
                tomcat.destroy();
            }
        }
    }

    private static final class StalledSource extends InputStream {
        private final CountDownLatch stalled = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private int produced;

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (produced < SENT_BEFORE_REVOCATION) {
                int count = Math.min(length, SENT_BEFORE_REVOCATION - produced);
                java.util.Arrays.fill(bytes, offset, offset + count, (byte) 65);
                produced += count;
                return count;
            }
            stalled.countDown();
            try {
                if (!closed.await(3, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting for cancellation");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", exception);
            }
            throw new IOException("Source closed");
        }

        @Override
        public int read() throws IOException {
            byte[] bytes = new byte[1];
            return read(bytes, 0, 1) < 0 ? -1 : bytes[0] & 0xff;
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }
}
