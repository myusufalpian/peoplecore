package id.mydev.peoplecore.identity.application.service;

import id.mydev.peoplecore.identity.domain.repository.AuthorizationFenceRepository;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthorizationFenceService {
    private final AuthorizationFenceRepository repository;

    public AuthorizationFenceService(AuthorizationFenceRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AuthorizationWindow currentBoundary(Instant at) {
        return window(at, false);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AuthorizationWindow sharedWindow(Instant at) {
        return window(at, true);
    }

    private AuthorizationWindow window(Instant at, boolean shared) {
        Objects.requireNonNull(at, "at");
        return new AuthorizationWindow(repository.generation(shared), repository.nextBoundary(at));
    }

    public record AuthorizationWindow(long generation, Instant nextBoundary) { }
}
