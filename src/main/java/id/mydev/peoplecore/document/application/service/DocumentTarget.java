package id.mydev.peoplecore.document.application.service;

import java.util.Objects;
import java.util.UUID;

public record DocumentTarget(UUID documentPublicId, UUID versionPublicId, UUID employeePublicId, String mediaType) {
    public DocumentTarget {
        Objects.requireNonNull(documentPublicId, "documentPublicId must not be null");
        Objects.requireNonNull(versionPublicId, "versionPublicId must not be null");
        Objects.requireNonNull(employeePublicId, "employeePublicId must not be null");
        Objects.requireNonNull(mediaType, "mediaType must not be null");
    }
}
