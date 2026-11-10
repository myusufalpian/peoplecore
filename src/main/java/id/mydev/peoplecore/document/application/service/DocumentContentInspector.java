package id.mydev.peoplecore.document.application.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class DocumentContentInspector {
    public static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final byte[] PDF = {0x25, 0x50, 0x44, 0x46, 0x2d};
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff};

    public Result inspect(InputStream source, OutputStream destination, String mediaType,
                          long expectedSize) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        byte[] signature = signatureFor(mediaType);
        if (expectedSize < 1 || expectedSize > MAX_BYTES) {
            throw new InvalidDocumentContentException("Invalid declared size");
        }

        MessageDigest digest = sha256();
        byte[] buffer = new byte[8192];
        byte[] prefix = new byte[signature.length];
        long total = 0;
        int prefixLength = 0;
        int count;
        while ((count = source.read(buffer)) != -1) {
            if (count == 0) {
                continue;
            }
            if (count > expectedSize - total) {
                throw new InvalidDocumentContentException("Document exceeds declared size");
            }
            int copied = Math.min(count, prefix.length - prefixLength);
            System.arraycopy(buffer, 0, prefix, prefixLength, copied);
            prefixLength += copied;
            digest.update(buffer, 0, count);
            destination.write(buffer, 0, count);
            total += count;
        }
        byte[] actualDigest = digest.digest();
        if (total != expectedSize || prefixLength != signature.length
            || !MessageDigest.isEqual(prefix, signature)) {
            throw new InvalidDocumentContentException("Document content does not match its declaration");
        }
        return new Result(mediaType, total, HexFormat.of().formatHex(actualDigest));
    }

    private static byte[] signatureFor(String mediaType) {
        if ("application/pdf".equals(mediaType)) {
            return PDF;
        }
        if ("image/png".equals(mediaType)) {
            return PNG;
        }
        if ("image/jpeg".equals(mediaType)) {
            return JPEG;
        }
        throw new InvalidDocumentContentException("Unsupported document media type");
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record Result(String mediaType, long byteSize, String sha256) {
    }

    public static final class InvalidDocumentContentException extends IllegalArgumentException {
        public InvalidDocumentContentException(String message) {
            super(message);
        }
    }
}
