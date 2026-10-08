package id.mydev.peoplecore.common.security;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentCaller {
    private CurrentCaller() { }

    public static Authentication require() {
        Authentication caller = SecurityContextHolder.getContext().getAuthentication();
        if (caller == null || !caller.isAuthenticated() || caller instanceof AnonymousAuthenticationToken) {
            throw new AuthenticationCredentialsNotFoundException("Authenticated caller required");
        }
        return caller;
    }
}
