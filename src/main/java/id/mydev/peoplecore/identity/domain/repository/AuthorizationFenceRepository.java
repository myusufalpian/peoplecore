package id.mydev.peoplecore.identity.domain.repository;

import java.time.Instant;

public interface AuthorizationFenceRepository {
    long generation(boolean sharedLock);
    Instant nextBoundary(Instant at);
}
