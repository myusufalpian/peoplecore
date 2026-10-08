package id.mydev.peoplecore.common.api;

import tools.jackson.core.StreamReadConstraints;

import java.nio.charset.StandardCharsets;

public final class PayloadLimits {
    public static final int MAX_BODY_BYTES = 1_048_576;
    public static final StreamReadConstraints JSON_CONSTRAINTS = StreamReadConstraints.builder()
        .maxDocumentLength(MAX_BODY_BYTES).maxStringLength(65_536).maxNameLength(256)
        .maxNestingDepth(64).maxNumberLength(128).maxTokenCount(100_000).build();

    private PayloadLimits() { }

    public static void validate(String payload) {
        if (payload.length() > MAX_BODY_BYTES || payload.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            throw new PayloadTooLargeException();
        }
    }
}
