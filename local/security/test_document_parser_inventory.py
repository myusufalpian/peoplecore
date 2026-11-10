import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile

from local.security.validate_document_parser_inventory import validate


class DocumentParserInventoryTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        root = Path(self.directory.name)
        self.runtime = root / "runtime"
        self.libs = root / "libs"
        (self.runtime / "lib").mkdir(parents=True)
        self.libs.mkdir()
        self.dependency = self.runtime / "lib" / "pdfbox-3.0.8.jar"
        with ZipFile(self.dependency, "w") as jar:
            jar.writestr("org/apache/pdfbox/Parser.class", b"dependency-class")
        self.inventory = {"components": [{"group": "org.apache.pdfbox", "name": "pdfbox", "version": "3.0.8",
            "file": self.dependency.name, "sha256": hashlib.sha256(self.dependency.read_bytes()).hexdigest()}]}
        self.inventory_bytes = json.dumps(self.inventory).encode()
        (self.runtime / "inventory.json").write_bytes(self.inventory_bytes)
        self.helper = self.runtime / "document-parser.jar"
        with ZipFile(self.helper, "w") as jar:
            jar.writestr("org/apache/pdfbox/Parser.class", b"dependency-class")
            jar.writestr("id/mydev/peoplecore/document/parser/DocumentParser.class", b"application-class")
        self.app = self.libs / "app.jar"
        self.write_app(self.helper.read_bytes())
        self.sbom = root / "sbom.json"
        self.sbom.write_text(json.dumps({"components": [{"purl": "pkg:maven/org.apache.pdfbox/pdfbox@3.0.8"}]}))

    def write_app(self, helper):
        with ZipFile(self.app, "w") as jar:
            jar.writestr("BOOT-INF/classes/document-parser/document-parser.jar", helper)
            jar.writestr("BOOT-INF/classes/document-parser/inventory.json", self.inventory_bytes)

    def test_accepts_matching_artifacts_and_sbom(self):
        validate(self.sbom, self.runtime, self.libs)

    def test_rejects_missing_sbom_component(self):
        self.sbom.write_text('{"components": []}')
        with self.assertRaises(ValueError):
            validate(self.sbom, self.runtime, self.libs)

    def test_rejects_tampered_dependency(self):
        self.dependency.write_bytes(b"changed")
        with self.assertRaises(ValueError):
            validate(self.sbom, self.runtime, self.libs)

    def test_rejects_changed_helper_or_boot_artifact(self):
        self.write_app(b"different helper")
        with self.assertRaises(ValueError):
            validate(self.sbom, self.runtime, self.libs)
        with ZipFile(self.helper, "w") as jar:
            jar.writestr("org/apache/pdfbox/Parser.class", b"changed-class")
        self.write_app(self.helper.read_bytes())
        with self.assertRaises(ValueError):
            validate(self.sbom, self.runtime, self.libs)


if __name__ == "__main__":
    unittest.main()
