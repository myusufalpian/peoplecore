import hashlib
import json
from pathlib import Path
import sys


def validate(runtime_directory):
    root = Path(runtime_directory)
    sbom = json.loads((root / "sbom.cdx.json").read_text())
    components = sbom.get("components", [])
    if not components:
        raise ValueError("SQS SBOM has no dependencies")
    inventoried = set()
    packages = set()
    for component in components:
        purl = component.get("purl", "")
        if not purl.startswith("pkg:maven/") or not component.get("version"):
            raise ValueError("SQS dependency must have Maven identity and version")
        paths = [p["value"] for p in component.get("properties", [])
                 if p.get("name") == "peoplecore:runtime-file"]
        if len(paths) != 1:
            raise ValueError("SQS dependency must identify exactly one runtime JAR")
        relative = Path(paths[0])
        if relative.parent != Path("lib") or relative.suffix != ".jar" or paths[0] in inventoried:
            raise ValueError("Invalid or duplicate SQS runtime JAR")
        jar = root / relative
        hashes = [h["content"] for h in component.get("hashes", []) if h.get("alg") == "SHA-256"]
        if jar.is_symlink() or not jar.is_file() or len(hashes) != 1:
            raise ValueError("SQS runtime JAR or checksum missing")
        if hashlib.sha256(jar.read_bytes()).hexdigest() != hashes[0]:
            raise ValueError("SQS runtime JAR does not match its SBOM checksum")
        inventoried.add(paths[0])
        packages.add(purl)
    actual = {str(jar.relative_to(root)) for jar in (root / "lib").glob("*.jar")}
    if actual != inventoried:
        raise ValueError("SQS runtime has dependencies absent from its SBOM")
    if not any(p.startswith("pkg:maven/org.elasticmq/elasticmq-server_2.13@") for p in packages):
        raise ValueError("SQS SBOM is missing the ElasticMQ server")
    return len(inventoried)


if __name__ == "__main__":
    try:
        print(f"SQS inventory verified: {validate(sys.argv[1])} runtime JARs")
    except (ValueError, OSError, KeyError) as error:
        raise SystemExit(f"SQS inventory rejected: {error}") from error
