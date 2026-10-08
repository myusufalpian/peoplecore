package id.mydev.peoplecore.common.security;

import org.springframework.security.access.AccessDeniedException;
import id.mydev.peoplecore.common.command.CommandExecutionService.CommandContext;
import org.springframework.security.core.Authentication;

import java.util.Set;

public interface CurrentAccessPolicy {
    default void checkCommand(Authentication caller, CommandContext command) {
        throw new AccessDeniedException("No command access policy is configured");
    }

    default void checkReplay(Authentication caller, CommandContext command, String recordedResult) {
        throw new AccessDeniedException("No command replay access policy is configured");
    }

    default Set<String> auditResources(Authentication caller) {
        return Set.of();
    }
}
