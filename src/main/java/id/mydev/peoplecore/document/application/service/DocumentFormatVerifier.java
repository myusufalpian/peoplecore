package id.mydev.peoplecore.document.application.service;

import java.io.IOException;
import java.io.InputStream;

@FunctionalInterface
public interface DocumentFormatVerifier {
    void verify(InputStream source, String mediaType, long byteSize, String expectedSha256) throws IOException;
}
