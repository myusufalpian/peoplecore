#!/usr/bin/env bash
set -euo pipefail
trivy_bin="${TRIVY_BIN:-trivy}"
report_dir="${SECURITY_REPORT_DIR:-build/reports/security}"
mkdir -p "$report_dir"
"$trivy_bin" rootfs --scanners vuln --format cyclonedx --output "$report_dir/backend.cdx.json" build/libs/
python3 -c 'import json,sys; s=json.load(open(sys.argv[1])); assert any("pkg:maven/" in c.get("purl", "") for c in s.get("components", [])), "Backend SBOM is missing Java dependencies"' "$report_dir/backend.cdx.json"
python3 local/security/prove-gate.py "$trivy_bin" "$report_dir"
scan_failed=0
if ! "$trivy_bin" sbom --scanners vuln --severity HIGH,CRITICAL --exit-code 1 --format json \
  --output "$report_dir/backend-vulnerabilities.json" "$report_dir/backend.cdx.json"; then
  scan_failed=1
fi
images=$(docker compose config --images | sort -u)
test -n "$images"
sqs_image=$(docker compose config --format json | python3 -c 'import json,sys; print(json.load(sys.stdin)["services"]["sqs"]["image"])')
while IFS= read -r image; do
  report_name="${image//[^a-zA-Z0-9]/_}"
  if [ "$image" = "$sqs_image" ]; then
    (
      runtime_dir=$(mktemp -d "$report_dir/sqs-runtime.XXXXXX")
      container_id=""
      trap 'if [ -n "$container_id" ]; then docker rm "$container_id" >/dev/null; fi; rm -rf "$runtime_dir"' EXIT
      container_id=$(docker create "$image")
      docker cp "$container_id:/opt/elasticmq/." "$runtime_dir/"
      python3 local/security/validate_sqs_inventory.py "$runtime_dir"
      cp "$runtime_dir/sbom.cdx.json" "$report_dir/sqs-dependencies.cdx.json"
    )
    if ! "$trivy_bin" sbom --scanners vuln --severity HIGH,CRITICAL --exit-code 1 --format json \
        --output "$report_dir/sqs-dependency-vulnerabilities.json" "$report_dir/sqs-dependencies.cdx.json"; then
      scan_failed=1
    fi
  fi
  "$trivy_bin" image --scanners vuln --format cyclonedx --output "$report_dir/$report_name.cdx.json" "$image"
  if ! "$trivy_bin" sbom --scanners vuln --severity HIGH,CRITICAL --exit-code 1 --format json \
      --output "$report_dir/$report_name-vulnerabilities.json" "$report_dir/$report_name.cdx.json"; then
    scan_failed=1
  fi
done <<< "$images"
exit "$scan_failed"
