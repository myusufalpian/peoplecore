import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from local.security.validate_sqs_inventory import validate


class SqsInventoryTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / "lib").mkdir()
        self.jar = self.root / "lib/server.jar"
        self.jar.write_bytes(b"test artifact")
        self.component = {
            "purl": "pkg:maven/org.elasticmq/elasticmq-server_2.13@1.7.1", "version": "1.7.1",
            "properties": [{"name": "peoplecore:runtime-file", "value": "lib/server.jar"}],
            "hashes": [{"alg": "SHA-256", "content": hashlib.sha256(self.jar.read_bytes()).hexdigest()}],
        }
        self.write([self.component])

    def write(self, components):
        (self.root / "sbom.cdx.json").write_text(json.dumps({"components": components}))

    def test_accepts_matching_inventory(self):
        self.assertEqual(1, validate(self.root))

    def test_rejects_missing_or_empty_inventory(self):
        self.write([])
        with self.assertRaises(ValueError):
            validate(self.root)
        (self.root / "sbom.cdx.json").unlink()
        with self.assertRaises(FileNotFoundError):
            validate(self.root)

    def test_rejects_modified_missing_and_unlisted_jars(self):
        self.jar.write_bytes(b"changed artifact")
        with self.assertRaises(ValueError):
            validate(self.root)
        self.jar.unlink()
        with self.assertRaises(ValueError):
            validate(self.root)
        self.jar.write_bytes(b"test artifact")
        (self.root / "lib/unlisted.jar").write_bytes(b"unlisted dependency")
        with self.assertRaises(ValueError):
            validate(self.root)

    def test_rejects_duplicate_jar_and_path_traversal(self):
        self.write([self.component, self.component])
        with self.assertRaises(ValueError):
            validate(self.root)
        component = copy.deepcopy(self.component)
        component["properties"][0]["value"] = "../outside.jar"
        self.write([component])
        with self.assertRaises(ValueError):
            validate(self.root)


if __name__ == "__main__":
    unittest.main()
