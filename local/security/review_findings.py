"""Publish every finding while preserving the HIGH/CRITICAL failure policy."""
import copy
import json
from pathlib import Path
import sys


def review(report, owner):
    high = copy.deepcopy(report)
    ledger = {}
    blocked = False
    severity_rank = {"UNKNOWN": 0, "LOW": 1, "MEDIUM": 2, "HIGH": 3, "CRITICAL": 4}
    for original, filtered in zip(report.get("Results", []), high.get("Results", [])):
        vulnerabilities = original.get("Vulnerabilities", [])
        filtered["Vulnerabilities"] = [v for v in vulnerabilities if v["Severity"] in {"HIGH", "CRITICAL"}]
        for finding in vulnerabilities:
            key = (finding["VulnerabilityID"], finding["PkgName"], finding["InstalledVersion"])
            blocked = blocked or finding["Severity"] in {"HIGH", "CRITICAL"}
            previous = ledger.get(key)
            if previous is not None and severity_rank.get(previous["severity"], 0) > severity_rank.get(finding["Severity"], 0):
                continue
            ledger[key] = {
                "id": key[0], "package": key[1], "installed": key[2],
                "fixed": finding.get("FixedVersion"), "severity": finding["Severity"],
                "owner": owner, "decision": "REVIEW_REQUIRED",
                "recommendation": "Upgrade to a compatible fixed version; otherwise record applicability and remediation decision.",
            }
    findings = sorted(ledger.values(), key=lambda row: (row["id"], row["package"], row["installed"]))
    return high, {"owner": owner, "blocked": blocked, "findings": findings}


if __name__ == "__main__":
    source, gate, ledger, owner = sys.argv[1:]
    high, decisions = review(json.loads(Path(source).read_text()), owner)
    Path(gate).write_text(json.dumps(high, indent=2))
    Path(ledger).write_text(json.dumps(decisions, indent=2))
    raise SystemExit(1 if decisions["blocked"] else 0)
