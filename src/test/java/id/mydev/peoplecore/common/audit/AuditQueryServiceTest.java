package id.mydev.peoplecore.common.audit;

import id.mydev.peoplecore.common.api.InvalidPaginationException;
import id.mydev.peoplecore.common.api.ResourceNotFoundException;
import id.mydev.peoplecore.common.security.CurrentAccessPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuditQueryServiceTest {
    @AfterEach
    void clearCaller() { SecurityContextHolder.clearContext(); }

    @Test
    void rejectsUnauthenticatedAndDefaultDeniedReads() {
        var service = new AuditQueryService(mock(AuditEventRepository.class), new CurrentAccessPolicy() { });
        assertThrows(AuthenticationCredentialsNotFoundException.class, () -> service.findByActor("actor", 0, 10));
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("actor", "", List.of()));
        assertThrows(AccessDeniedException.class, () -> service.findByActor("actor", 0, 10));
        assertThrows(AccessDeniedException.class, () -> service.findByAggregate("EMPLOYEE", UUID.randomUUID(), 0, 10));
    }

    @Test
    void allowsOnlyExactObjectAndMapsSafeSummary() {
        UUID id = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        var event = new AuditEvent(eventId, "actor", "CREATE", "EMPLOYEE", 1L, id, "correlation", "private reason", "private details", Instant.now());
        var repository = mock(AuditEventRepository.class);
        var pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        when(repository.findByAggregateTypeAndAggregatePublicId("EMPLOYEE", id, pageable)).thenReturn(new PageImpl<>(List.of(event)));
        var policy = new CurrentAccessPolicy() {
            @Override public Set<String> auditResources(Authentication caller) { return Set.of("EMPLOYEE:" + id); }
        };
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("actor", "", List.of()));
        var service = new AuditQueryService(repository, policy);
        assertEquals(eventId, service.findByAggregate("EMPLOYEE", id, 0, 10).getContent().getFirst().eventId());
        assertThrows(ResourceNotFoundException.class, () -> service.findByAggregate("EMPLOYEE", UUID.randomUUID(), 0, 10));
        assertThrows(InvalidPaginationException.class, () -> service.findByAggregate("EMPLOYEE", id, -1, 10));
    }
}
