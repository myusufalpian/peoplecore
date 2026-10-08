import json
from pathlib import Path
import subprocess
import sys

scanner, report_directory = sys.argv[1:]
report = Path(report_directory)
fixture = report / "vulnerable-fixture.cdx.json"
fixture.write_text(json.dumps({
    "bomFormat": "CycloneDX", "specVersion": "1.5", "version": 1,
    "components": [{"type": "library", "group": "org.apache.logging.log4j",
                    "name": "log4j-core", "version": "2.14.1",
                    "purl": "pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1"}]
}))
result_file = report / "vulnerable-fixture-result.json"
result_file.unlink(missing_ok=True)
result = subprocess.run([scanner, "sbom", "--scanners", "vuln", "--severity", "HIGH,CRITICAL",
                         "--exit-code", "1", "--format", "json", "--output", str(result_file), str(fixture)])
if result.returncode != 1 or not result_file.is_file():
    raise SystemExit("Security gate proof did not produce the expected vulnerability failure")
findings = json.loads(result_file.read_text()).get("Results", [])
if not any(v.get("PkgName") == "org.apache.logging.log4j:log4j-core" and v.get("Severity") in {"HIGH", "CRITICAL"}
           for entry in findings for v in entry.get("Vulnerabilities", [])):
    raise SystemExit("Security gate failed without confirming the vulnerable fixture")
print("Security gate proof passed: vulnerable fixture rejected")
