package id.mydev.peoplecore.common.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import id.mydev.peoplecore.common.command.CommandExecutionService;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CurrentCallerTest {
    @AfterEach
    void clearCaller() { SecurityContextHolder.clearContext(); }

    @Test
    void anonymousAndUnauthenticatedTokensNeverGrantAccess() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("key", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        assertThrows(AuthenticationCredentialsNotFoundException.class, CurrentCaller::require);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.unauthenticated("actor", ""));
        assertThrows(AuthenticationCredentialsNotFoundException.class, CurrentCaller::require);
        var caller = UsernamePasswordAuthenticationToken.authenticated("actor", "", List.of());
        assertThrows(AccessDeniedException.class, () -> new CurrentAccessPolicy() { }.checkReplay(caller, new id.mydev.peoplecore.common.command.CommandExecutionService.CommandContext("actor", "CREATE", "key", "{}", null), "{}"));
        assertThrows(AccessDeniedException.class, () -> new CurrentAccessPolicy() { }.checkCommand(caller, new id.mydev.peoplecore.common.command.CommandExecutionService.CommandContext("actor", "CREATE", "key", "{}", null)));
    }
}
