#!/usr/bin/env python3
"""Update a release pair without enabling migrations or changing recovery approval."""
import argparse
import re
from pathlib import Path
from update_service_image_values import update_values, validate_image_reference

SERVICES = ("animal", "user", "community", "store", "payment")

def update_pair(content, repository, tag, digest, migration_digest):
    validate_image_reference(repository, tag, digest)
    validate_image_reference(repository, tag, migration_digest)
    if repository not in {f"dorosiya/pawbridge-{s}-service" for s in SERVICES}:
        raise ValueError("unsupported migration repository")
    updated = update_values(content, repository, tag, digest)
    newline = "\r\n" if "\r\n" in content else "\n"
    lines = updated.splitlines()
    starts = [i for i, line in enumerate(lines) if line.startswith("schemaMigration:")]
    if len(starts) > 1:
        raise ValueError("duplicate schemaMigration mapping")
    managed = [
        f"  image: {repository}@{migration_digest}",
        f"  apiImageDigest: {digest}",
        f"  sourceRevision: {tag[4:]}",
    ]
    if not starts:
        lines += ["", "schemaMigration:", *managed, "  enabled: false"]
    else:
        start = starts[0]
        if not re.fullmatch(r"schemaMigration:\s*(?:#.*)?", lines[start]):
            raise ValueError("schemaMigration must be a block mapping")
        end = next((i for i in range(start + 1, len(lines))
                    if lines[i] and not lines[i][0].isspace()
                    and not lines[i].startswith("#")), len(lines))
        kept, seen = [], set()
        for line in lines[start + 1:end]:
            match = re.match(r"^  (image|apiImageDigest|sourceRevision):", line)
            if match:
                if match[1] in seen:
                    raise ValueError("duplicate migration image key")
                seen.add(match[1])
            else:
                kept.append(line)
        lines[start + 1:end] = managed + kept
    return newline.join(lines) + (newline if updated.endswith(("\n", "\r")) else "")

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("values_file", type=Path)
    for name in ("repository", "tag", "digest", "migration-digest"):
        parser.add_argument("--" + name, required=True)
    args = parser.parse_args()
    with args.values_file.open(encoding="utf-8", newline="") as source:
        original = source.read()
    updated = update_pair(original, args.repository, args.tag, args.digest, args.migration_digest)
    if original != updated:
        with args.values_file.open("w", encoding="utf-8", newline="") as target:
            target.write(updated)

if __name__ == "__main__":
    main()
