package id.mydev.peoplecore.infrastructure.recovery;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ServiceRecoveryGate {
    public static final String ISOLATED_PROPERTY = "peoplecore.recovery.isolated";

    private final Environment environment;
    private final ObjectProvider<ServiceRecoveryRepository> repository;

    public ServiceRecoveryGate(Environment environment, ObjectProvider<ServiceRecoveryRepository> repository) {
        this.environment = environment;
        this.repository = repository;
    }

    public boolean isIsolated() {
        if (environment != null && environment.getProperty(ISOLATED_PROPERTY, Boolean.class, false)) {
            return true;
        }
        ServiceRecoveryRepository configured = repository == null ? null : repository.getIfAvailable();
        return configured != null && configured.hasActiveIncident();
    }

    public void requireNormalOperation() {
        if (isIsolated()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Service recovery isolation is active");
        }
    }
}
