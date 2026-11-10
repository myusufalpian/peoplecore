package id.mydev.peoplecore.document.infrastructure.config;

import id.mydev.peoplecore.document.infrastructure.scanner.ClamAvScanner;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ClamAvScannerConfig {
    @Bean(destroyMethod = "close")
    @ConditionalOnBean(ClamAvScanner.ScannerReadiness.class)
    @ConditionalOnMissingBean(ClamAvScanner.class)
    @ConditionalOnProperty(prefix = "peoplecore.documents.scanner", name = {"socket", "timeout", "max-concurrent"})
    ClamAvScanner clamAvScanner(ClamAvScanner.ScannerReadiness readiness, Clock clock,
            @Value("${peoplecore.documents.scanner.socket}") String socket,
            @Value("${peoplecore.documents.scanner.timeout}") String timeout,
            @Value("${peoplecore.documents.scanner.max-concurrent}") int maxConcurrent) {
        Path path = Path.of(socket);
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("Scanner socket path must be absolute");
        }
        return new ClamAvScanner(path, Duration.parse(timeout), maxConcurrent, readiness, clock);
    }
}
