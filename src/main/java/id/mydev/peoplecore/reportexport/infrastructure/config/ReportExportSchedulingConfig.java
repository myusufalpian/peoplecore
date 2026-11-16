package id.mydev.peoplecore.reportexport.infrastructure.config;

import id.mydev.peoplecore.reportexport.application.ReportExportWorker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(name={"peoplecore.exports.enabled","peoplecore.exports.worker.enabled"},havingValue="true")
public class ReportExportSchedulingConfig {
    @Bean
    @ConditionalOnProperty(name="peoplecore.runtime.role",havingValue="worker")
    ExportPoller exportPoller(ReportExportWorker worker) { return new ExportPoller(worker); }

    static class ExportPoller {
        private final ReportExportWorker worker;
        ExportPoller(ReportExportWorker worker) { this.worker=worker; }
        @Scheduled(fixedDelayString="${peoplecore.exports.poll-interval:PT5S}")
        public void poll() {
            worker.processNext();
            worker.cleanup(25);
        }
    }
}
