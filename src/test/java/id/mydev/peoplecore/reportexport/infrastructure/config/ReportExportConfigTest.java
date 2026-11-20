package id.mydev.peoplecore.reportexport.infrastructure.config;

import id.mydev.peoplecore.document.application.service.RevocableDocumentTransfer;
import id.mydev.peoplecore.reportexport.application.ReportExportWorker;
import id.mydev.peoplecore.reportexport.infrastructure.storage.ExportArtifactStorage;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class ReportExportConfigTest {
    @Test
    void transferIsExplicitlyOptInAndDoesNotDependOnDocumentFlags() {
        new ApplicationContextRunner().withUserConfiguration(ReportExportConfig.class).run(context->{
            assertTrue(context.getBeansOfType(RevocableDocumentTransfer.class).isEmpty());
            assertTrue(context.getBeansOfType(ExportArtifactStorage.class).isEmpty());
        });
        new ApplicationContextRunner().withUserConfiguration(ReportExportConfig.class)
            .withPropertyValues("peoplecore.exports.enabled=true").run(context->{
                assertTrue(context.getBeansOfType(RevocableDocumentTransfer.class).isEmpty());
                assertTrue(context.getBeansOfType(ExportArtifactStorage.class).isEmpty());
            });
        new ApplicationContextRunner().withUserConfiguration(ReportExportConfig.class)
            .withPropertyValues("peoplecore.exports.enabled=true","peoplecore.exports.download.enabled=true").run(context->{
                assertEquals(1,context.getBeansOfType(RevocableDocumentTransfer.class).size());
                assertTrue(context.getBeansOfType(ExportArtifactStorage.class).isEmpty());
            });
    }

    @Test
    void apiRuntimeDoesNotCreateAnExportPollerEvenWhenFeaturesAreEnabled() {
        new ApplicationContextRunner().withUserConfiguration(ReportExportSchedulingConfig.class)
            .withBean(ReportExportWorker.class,()->mock(ReportExportWorker.class))
            .withPropertyValues("peoplecore.exports.enabled=true","peoplecore.exports.worker.enabled=true","peoplecore.runtime.role=api")
            .run(context->assertFalse(context.containsBean("exportPoller")));
    }

    @Test
    void unsafeStorageEndpointIsRejectedBeforeCreatingAnAwsClient() {
        ReportExportConfig config=new ReportExportConfig();
        assertNotNull(config);
        assertThrows(IllegalArgumentException.class,
            ()->config.exportS3Client(URI.create("http://remote.example.com"),"ap-southeast-1"));
    }
}
