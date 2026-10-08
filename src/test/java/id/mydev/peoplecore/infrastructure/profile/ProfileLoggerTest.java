package id.mydev.peoplecore.infrastructure.profile;

import org.junit.jupiter.api.Test;

class ProfileLoggerTest {
    @Test
    void logsActualRuntimeRole() {
        new ProfileLogger(RuntimeProfileConfig.RuntimeRole.API).onApplicationReady();
        new ProfileLogger(RuntimeProfileConfig.RuntimeRole.WORKER).onApplicationReady();
    }
}
