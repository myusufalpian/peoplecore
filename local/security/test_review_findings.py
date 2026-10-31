import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("review_findings", Path(__file__).with_name("review_findings.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ReviewFindingsTest(unittest.TestCase):
    def report(self, severity):
        return {"Results": [{"Vulnerabilities": [{"VulnerabilityID": "CVE-fixture", "PkgName": "library",
                    "InstalledVersion": "1", "Severity": severity}]}]}

    def test_lower_severity_remains_owned_and_visible_without_blocking(self):
        source = self.report("MEDIUM")
        gate, ledger = module.review(source, "platform-maintainer")
        self.assertFalse(ledger["blocked"])
        self.assertEqual([], gate["Results"][0]["Vulnerabilities"])
        self.assertEqual("REVIEW_REQUIRED", ledger["findings"][0]["decision"])
        self.assertEqual("platform-maintainer", ledger["findings"][0]["owner"])
        self.assertEqual(1, len(source["Results"][0]["Vulnerabilities"]))

    def test_high_severity_blocks_and_duplicate_paths_do_not_duplicate_decisions(self):
        source = self.report("HIGH")
        source["Results"].append(source["Results"][0])
        gate, ledger = module.review(source, "backend-maintainer")
        self.assertTrue(ledger["blocked"])
        self.assertEqual(1, len(ledger["findings"]))
        self.assertEqual(1, len(gate["Results"][0]["Vulnerabilities"]))

    def test_duplicate_lower_severity_cannot_erase_blocking_finding(self):
        for severities in [("HIGH", "LOW"), ("LOW", "CRITICAL")]:
            source = {"Results": [self.report(severity)["Results"][0] for severity in severities]}
            _, ledger = module.review(source, "backend-maintainer")
            self.assertTrue(ledger["blocked"])
            self.assertIn(ledger["findings"][0]["severity"], {"HIGH", "CRITICAL"})

    def test_clean_report_does_not_block(self):
        _, ledger = module.review({"Results": []}, "backend-maintainer")
        self.assertFalse(ledger["blocked"])
        self.assertEqual([], ledger["findings"])
