"""Copy and patch the pinned SAM3 checkout; never modify an existing installation."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def prepare(upstream, destination):
    definitions = Path(__file__).resolve().parents[1] / "deploy/macos"
    manifest = json.loads((definitions / "sam3-manifest.json").read_text())
    patch = definitions / "sam3-mps.patch"
    if digest(patch) != manifest["patch_sha256"]:
        raise RuntimeError("SAM3 patch digest mismatch")
    actual = {str(p.relative_to(upstream)) for p in (upstream / "sam3").rglob("*")
              if p.is_file() and "__pycache__" not in p.parts and p.suffix != ".pyc"}
    if actual != set(manifest["upstream_files"]):
        raise RuntimeError("SAM3 package file set differs from the pinned commit")
    for name, expected in manifest["upstream_files"].items():
        if digest(upstream / name) != expected:
            raise RuntimeError(f"SAM3 upstream digest mismatch: {name}")
    shutil.copytree(upstream, destination, ignore=shutil.ignore_patterns(".git", "__pycache__", "*.pyc"))
    subprocess.run(["patch", "-p1", "--batch", "-i", str(patch)], cwd=destination, check=True)
    for name, expected in manifest["patched_files"].items():
        if digest(destination / name) != expected:
            raise RuntimeError(f"SAM3 patched digest mismatch: {name}")
    print(json.dumps({"commit": manifest["commit"], "patched_files": len(manifest["patched_files"])}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("upstream", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    prepare(args.upstream.resolve(), args.destination.resolve())
