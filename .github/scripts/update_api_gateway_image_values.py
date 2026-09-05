#!/usr/bin/env python3
"""Update API Gateway image references in Infra values and verification."""

from __future__ import annotations

import argparse
import re
from pathlib import Path


TAG_PATTERN = re.compile(r"sha-[0-9a-f]{40}")
DIGEST_PATTERN = re.compile(r"sha256:[0-9a-f]{64}")
REPOSITORY_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._/-]*")
TOP_LEVEL_KEY_PATTERN = re.compile(r"^[A-Za-z0-9_-]+:")
IMAGE_MAPPING_PATTERN = re.compile(r"^image:\s*(?:#.*)?$")
MANAGED_KEY_PATTERN = re.compile(r"^(\s+)(repository|tag|digest):")
GATEWAY_IMAGE_PATTERN = re.compile(
    r'^(readonly GATEWAY_IMAGE=")([^"]+)(")$',
    re.MULTILINE,
)


def validate_repository_and_digest(repository: str, digest: str) -> None:
    if REPOSITORY_PATTERN.fullmatch(repository) is None:
        raise ValueError(f"invalid image repository: {repository}")
    if DIGEST_PATTERN.fullmatch(digest) is None:
        raise ValueError(f"invalid image digest: {digest}")


def validate_image_reference(repository: str, tag: str, digest: str) -> None:
    validate_repository_and_digest(repository, digest)
    if TAG_PATTERN.fullmatch(tag) is None:
        raise ValueError(f"invalid immutable image tag: {tag}")


def update_values(
    content: str,
    repository: str,
    tag: str,
    digest: str,
) -> str:
    validate_image_reference(repository, tag, digest)

    newline = "\r\n" if "\r\n" in content else "\n"
    had_final_newline = content.endswith(("\n", "\r"))
    lines = content.splitlines()
    image_lines = [index for index, line in enumerate(lines) if line.startswith("image:")]

    if len(image_lines) > 1:
        raise ValueError("multiple top-level image mappings are not supported")

    managed_lines = [
        f"  repository: {repository}",
        f"  tag: {tag}",
        f"  digest: {digest}",
    ]

    if not image_lines:
        updated_lines = ["image:", *managed_lines, "", *lines]
    else:
        start = image_lines[0]
        if IMAGE_MAPPING_PATTERN.fullmatch(lines[start]) is None:
            raise ValueError("top-level image must be a YAML mapping")

        end = len(lines)
        for index in range(start + 1, len(lines)):
            line = lines[index]
            if TOP_LEVEL_KEY_PATTERN.match(line):
                end = index
                break

        remaining_block: list[str] = []
        seen_keys: set[str] = set()
        for line in lines[start + 1 : end]:
            match = MANAGED_KEY_PATTERN.match(line)
            if match is None:
                remaining_block.append(line)
                continue

            key = match.group(2)
            if key in seen_keys:
                raise ValueError(f"duplicate image key: {key}")
            seen_keys.add(key)

        updated_lines = [
            *lines[:start],
            lines[start],
            *managed_lines,
            *remaining_block,
            *lines[end:],
        ]

    updated = newline.join(updated_lines)
    if had_final_newline:
        updated += newline
    return updated


def update_verification_script(
    content: str,
    repository: str,
    digest: str,
) -> str:
    validate_repository_and_digest(repository, digest)

    matches = list(GATEWAY_IMAGE_PATTERN.finditer(content))
    if len(matches) != 1:
        raise ValueError(
            "verification script must contain exactly one GATEWAY_IMAGE declaration"
        )

    expected_image = f"{repository}@{digest}"
    match = matches[0]
    return "".join(
        (
            content[: match.start(2)],
            expected_image,
            content[match.end(2) :],
        )
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("values_file", type=Path)
    parser.add_argument("--verification-script", type=Path, required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--digest", required=True)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    values_original = args.values_file.read_text(encoding="utf-8")
    verification_original = args.verification_script.read_text(encoding="utf-8")
    values_updated = update_values(
        values_original,
        args.repository,
        args.tag,
        args.digest,
    )
    verification_updated = update_verification_script(
        verification_original,
        args.repository,
        args.digest,
    )

    updates = (
        (args.values_file, values_original, values_updated),
        (
            args.verification_script,
            verification_original,
            verification_updated,
        ),
    )
    for path, original, updated in updates:
        if updated == original:
            print(f"unchanged: {path}")
            continue
        path.write_text(updated, encoding="utf-8", newline="")
        print(f"updated: {path}")


if __name__ == "__main__":
    main()
