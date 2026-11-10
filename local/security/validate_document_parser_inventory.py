import hashlib
import json
from pathlib import Path
import sys
from urllib.parse import unquote
from zipfile import BadZipFile, ZipFile


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def validate(sbom_path, runtime_dir, libs_dir):
    runtime = Path(runtime_dir)
    raw_inventory = (runtime / "inventory.json").read_bytes()
    components = json.loads(raw_inventory)["components"]
    if not components or not any(c["group"] == "org.apache.pdfbox" and c["name"] == "pdfbox" for c in components):
        raise ValueError("Document parser inventory is missing PDFBox")
    expected_purls = set()
    expected_classes = {}
    names = set()
    for component in components:
        filename = component["file"]
        if Path(filename).name != filename or filename in names:
            raise ValueError("Invalid or duplicate parser dependency filename")
        names.add(filename)
        jar = runtime / "lib" / filename
        if jar.is_symlink() or not jar.is_file() or sha256(jar.read_bytes()) != component["sha256"]:
            raise ValueError("Parser dependency is missing or has changed")
        expected_purls.add(f"pkg:maven/{component['group']}/{component['name']}@{component['version']}")
        with ZipFile(jar) as archive:
            for entry in archive.namelist():
                if entry.endswith(".class"):
                    expected_classes.setdefault(entry, set()).add(sha256(archive.read(entry)))
    actual_jars = {path.name for path in (runtime / "lib").iterdir() if path.is_file() and path.suffix == ".jar"}
    if actual_jars != names:
        raise ValueError("Parser dependency inventory does not match runtime JARs")
    sbom = json.loads(Path(sbom_path).read_text())
    found_purls = {unquote(c.get("purl", "").split("?", 1)[0].split("#", 1)[0]) for c in sbom.get("components", [])}
    if not expected_purls.issubset(found_purls):
        raise ValueError("Parser SBOM does not include every runtime dependency")
    helper = runtime / "document-parser.jar"
    if helper.is_symlink() or not helper.is_file():
        raise ValueError("Document parser helper is missing")
    helper_bytes = helper.read_bytes()
    with ZipFile(helper) as archive:
        helper_classes = {name for name in archive.namelist() if name.endswith(".class")}
        if not expected_classes.keys() <= helper_classes:
            raise ValueError("Document parser helper is missing dependency classes")
        for name in helper_classes:
            if name.startswith("id/mydev/peoplecore/document/parser/"):
                continue
            if name not in expected_classes or sha256(archive.read(name)) not in expected_classes[name]:
                raise ValueError("Document parser helper has an unexpected or altered class")
    embedded = False
    for jar in Path(libs_dir).iterdir():
        if not jar.is_file() or jar.suffix != ".jar":
            continue
        try:
            with ZipFile(jar) as archive:
                path = "BOOT-INF/classes/document-parser/document-parser.jar"
                if path not in archive.namelist():
                    continue
                if archive.read(path) != helper_bytes or archive.read("BOOT-INF/classes/document-parser/inventory.json") != raw_inventory:
                    raise ValueError("Application artifact contains a different document parser")
                embedded = True
        except BadZipFile as exception:
            raise ValueError("Application artifact is not a valid JAR") from exception
    if not embedded:
        raise ValueError("Application artifact does not contain the scanned document parser")


if __name__ == "__main__":
    try:
        validate(*sys.argv[1:])
    except (OSError, ValueError, KeyError, BadZipFile) as exception:
        raise SystemExit(f"Document parser inventory validation failed: {exception}") from exception
