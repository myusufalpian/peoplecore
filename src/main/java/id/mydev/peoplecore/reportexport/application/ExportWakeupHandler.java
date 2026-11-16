package id.mydev.peoplecore.reportexport.application;

import id.mydev.peoplecore.common.outbox.EventEnvelope;
import id.mydev.peoplecore.common.outbox.EventIngestionHandler;
import id.mydev.peoplecore.reportexport.domain.repository.ReportExportRepository;
import org.springframework.stereotype.Component;

@Component
public class ExportWakeupHandler implements EventIngestionHandler {
    private final ReportExportRepository repository;
    public ExportWakeupHandler(ReportExportRepository repository) { this.repository=repository; }
    @Override
    public boolean supports(String type) { return "EXPORT_REQUESTED".equals(type); }
    @Override
    public void ingest(EventEnvelope event) {
        if(repository.find(event.aggregatePublicId(),false).isEmpty()) throw new IllegalArgumentException("Export job unavailable");
    }
}
