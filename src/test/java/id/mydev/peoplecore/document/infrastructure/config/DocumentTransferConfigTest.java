package id.mydev.peoplecore.document.infrastructure.config;

import id.mydev.peoplecore.document.application.service.RevocableDocumentTransfer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentTransferConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(DocumentTransferConfig.class);

    @Test
    void transferBeanIsAbsentUnlessExplicitlyEnabled() {
        runner.run(context -> assertThat(context).doesNotHaveBean(RevocableDocumentTransfer.class));
    }

    @Test
    void enabledTransferRequiresBoundedTimingConfiguration() {
        runner.withPropertyValues("peoplecore.documents.transfer.enabled=true",
                "peoplecore.documents.transfer.max-concurrent=2",
                "peoplecore.documents.transfer.poll-interval=PT30S",
                "peoplecore.documents.transfer.check-timeout=PT30S")
            .run(context -> assertThat(context).hasSingleBean(RevocableDocumentTransfer.class));

        runner.withPropertyValues("peoplecore.documents.transfer.enabled=true",
                "peoplecore.documents.transfer.max-concurrent=1",
                "peoplecore.documents.transfer.poll-interval=PT5M",
                "peoplecore.documents.transfer.check-timeout=PT1S")
            .run(context -> assertThat(context).hasFailed());
    }
}
