package id.mydev.peoplecore.infrastructure.profile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class ProfileLogger {
    private static final Logger log = LoggerFactory.getLogger(ProfileLogger.class);
    private final RuntimeProfileConfig.RuntimeRole role;

    public ProfileLogger(RuntimeProfileConfig.RuntimeRole role) {
        this.role = role;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("Peoplecore initialized in {} role", role);
    }
}
