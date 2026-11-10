#!/usr/bin/env bash
set -euo pipefail

root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
workdir=$(mktemp -d "${TMPDIR:-/tmp}/peoplecore-clamd-adapter.XXXXXXXX")
daemon="peoplecore-clamd-adapter-$(basename "$workdir" | cut -d. -f2)"
created=0
cleanup() {
  if [ "$created" -eq 1 ]; then
    docker rm -fv "$daemon" >/dev/null 2>&1 || true
  fi
  rm -rf -- "$workdir"
}
trap cleanup EXIT

daemon_image='clamav/clamav:1.5.4-debian13-slim'
java_image='eclipse-temurin:21-jre-alpine'
docker image inspect "$daemon_image" "$java_image" >/dev/null
mkdir -p "$workdir/db" "$workdir/classes"
printf '%s\n' 'PeoplecoreSynthetic:0:*:70656f706c65636f72652d746573742d7369676e6174757265' > "$workdir/db/local.ndb"
cat > "$workdir/clamd.conf" <<'EOF'
Foreground yes
LocalSocket /scan/clamd.sock
LocalSocketMode 600
DatabaseDirectory /input/db
AlertEncrypted yes
AlertExceedsMax yes
HeuristicAlerts yes
ScanPDF yes
StreamMaxLength 12M
MaxFileSize 1M
EOF
cat > "$workdir/Harness.java" <<'EOF'
import id.mydev.peoplecore.document.infrastructure.scanner.ClamAvScanner;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;

public class Harness {
    public static void main(String[] args) throws Exception {
        Instant expiry = Instant.now().plusSeconds(300);
        try (var scanner = new ClamAvScanner(Path.of("/scan/clamd.sock"), Duration.ofSeconds(15), 1,
                () -> new ClamAvScanner.ScannerStatus(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"), "a".repeat(64), "a".repeat(64), "test-engine", "synthetic-only", expiry, true, true), Clock.systemUTC())) {
            byte[] clean = "%PDF-1.7 clean sample".getBytes(StandardCharsets.US_ASCII);
            byte[] flagged = "%PDF-1.7 peoplecore-test-signature".getBytes(StandardCharsets.US_ASCII);
            scanner.scan(new ByteArrayInputStream(clean), "application/pdf", clean.length);
            try {
                scanner.scan(new ByteArrayInputStream(flagged), "application/pdf", flagged.length);
                throw new AssertionError("Expected synthetic detection");
            } catch (ClamAvScanner.MalwareDetectedException expected) {
                System.out.println("Java adapter: clean and synthetic detection passed");
            }
            byte[] encrypted;
            try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
                document.addPage(new PDPage());
                var policy = new StandardProtectionPolicy("test-owner", "test-user", new AccessPermission());
                policy.setEncryptionKeyLength(128);
                document.protect(policy);
                document.save(output);
                encrypted = output.toByteArray();
            }
            try {
                scanner.scan(new ByteArrayInputStream(encrypted), "application/pdf", encrypted.length);
                throw new AssertionError("Expected encrypted PDF warning");
            } catch (ClamAvScanner.MalwareDetectedException expected) {
                System.out.println("Java adapter: encrypted PDF warning passed");
            }
            byte[] oversized = new byte[1024 * 1024 + 1];
            System.arraycopy("%PDF-".getBytes(StandardCharsets.US_ASCII), 0, oversized, 0, 5);
            try {
                scanner.scan(new ByteArrayInputStream(oversized), "application/pdf", oversized.length);
                throw new AssertionError("Expected scan size limit warning");
            } catch (ClamAvScanner.MalwareDetectedException expected) {
                System.out.println("Java adapter: scan size limit warning passed");
            }
        }
        try (var unavailable = new ClamAvScanner(Path.of("/scan/missing.sock"), Duration.ofSeconds(2), 1,
                () -> new ClamAvScanner.ScannerStatus(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"), "a".repeat(64), "a".repeat(64), "test-engine", "synthetic-only", expiry, true, true), Clock.systemUTC())) {
            byte[] clean = "%PDF-1.7 clean sample".getBytes(StandardCharsets.US_ASCII);
            try {
                unavailable.scan(new ByteArrayInputStream(clean), "application/pdf", clean.length);
                throw new AssertionError("Expected unavailable daemon failure");
            } catch (java.io.IOException expected) {
                System.out.println("Java adapter: unavailable daemon fails closed");
            }
        }
    }
}
EOF
if [ ! -f "$root/build/document-parser-runtime/document-parser.jar" ]; then
  echo 'Parser artifact missing; build documentParserRuntimeFiles before running this test' >&2
  exit 1
fi
cp "$root/build/document-parser-runtime/document-parser.jar" "$workdir/document-parser.jar"
javac --release 21 -cp "$workdir/document-parser.jar" -d "$workdir/classes" \
  "$root/src/main/java/id/mydev/peoplecore/document/application/service/DocumentContentInspector.java" \
  "$root/src/main/java/id/mydev/peoplecore/document/infrastructure/scanner/ClamAvScanner.java" "$workdir/Harness.java"

docker run -d --pull never --network none --memory 512m --cpus 1 --user 0 --name "$daemon" \
  -v /scan --mount "type=bind,source=$workdir,target=/input,readonly" \
  --entrypoint clamd "$daemon_image" --config-file=/input/clamd.conf >/dev/null
created=1
ready=0
for attempt in $(seq 1 30); do
  if docker exec "$daemon" test -S /scan/clamd.sock; then
    ready=1
    break
  fi
  sleep 1
done
if [ "$ready" -ne 1 ]; then
  echo 'Test daemon did not create its socket' >&2
  exit 1
fi
docker run --rm --pull never --network none --memory 256m --cpus 1 --volumes-from "$daemon" \
  --entrypoint java "$java_image" -cp /input/classes:/input/document-parser.jar Harness
echo 'Synthetic test only; this does not establish production scanner readiness.'
