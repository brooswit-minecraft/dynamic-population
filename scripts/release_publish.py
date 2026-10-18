#!/usr/bin/env python3
"""Validate and publish a Dynamic Population release.

Supports two ways `.github/workflows/release.yml` can be triggered:
  - push to `main` touching `version.txt`: version comes from `version.txt`,
    notes come from the top entry of `CHANGELOG.md` (the original, unchanged
    behaviour).
  - `workflow_dispatch` with explicit `version`/`notes` inputs: for a
    reviewed, explicit release-time version/notes selection when
    `version.txt`/`CHANGELOG.md` haven't been consolidated yet.
    `release-gate` v1 is gate-only -- it never assigns a version or writes
    `CHANGELOG.md` (see
    https://github.com/brooswit-factory/release-gate/blob/v1/README.md) --
    so until repo-owned automation for that exists, an explicit dispatch
    input is how a reviewed version/notes pair reaches this workflow.

Either way, this never trusts "the release doesn't exist yet" or "the
release already exists" blindly: a retry against an EXISTING tag must
prove the existing release is for the identical commit and jar content
before treating it as already-done, and must fail loudly -- never
overwrite, never silently accept a mismatch -- otherwise.
"""
import hashlib
import json
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

VERSION_RE = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.-]+)?$")


def validate_version(version):
    """Return the trimmed version string, or raise ValueError if it is not
    a valid `major.minor.patch[-prerelease]` release version."""
    version = (version or "").strip()
    if not VERSION_RE.match(version):
        raise ValueError(f"not a valid version: {version!r}")
    return version


def validate_notes(notes):
    """Return `notes` unchanged, or raise ValueError if there is no actual
    content to publish (empty, or only whitespace)."""
    if not notes or not notes.strip():
        raise ValueError("release notes are empty")
    return notes


def jar_declares_version(jar_path, expected_version):
    """True if the jar's baked neoforge.mods.toml declares exactly
    `expected_version` -- the jar/tag/version agreement check."""
    with zipfile.ZipFile(jar_path) as zf:
        toml_text = zf.read("META-INF/neoforge.mods.toml").decode("utf-8")
    match = re.search(r'^version="([^"]*)"', toml_text, re.M)
    if not match:
        raise ValueError("jar's neoforge.mods.toml has no version= line")
    return match.group(1) == expected_version


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def existing_release_is_identical(existing_target_sha, new_commit_sha, existing_jar_sha256, new_jar_sha256):
    """True only if a pre-existing release for this tag is provably the same
    commit AND the same jar bytes as what we are about to publish -- the
    only condition under which a retry may treat it as already-done.
    Same-commit-different-jar, same-jar-different-commit, and
    both-different are all NOT identical and must fail, never be
    silently accepted or overwritten.
    """
    return existing_target_sha == new_commit_sha and existing_jar_sha256 == new_jar_sha256


def _gh_json(*args):
    out = subprocess.run(["gh", *args], check=True, capture_output=True, text=True).stdout
    return json.loads(out)


def resolve_version(dispatch_version, version_file):
    return validate_version(dispatch_version or Path(version_file).read_text())


def resolve_notes(dispatch_notes, changelog_file):
    return validate_notes(dispatch_notes or Path(changelog_file).read_text())


def publish_or_verify_identical(tag, commit_sha, jar_path, notes):
    """Create the immutable GitHub release for `tag`, or -- if it already
    exists -- verify it is identical to what we would publish and exit
    cleanly; mismatches raise SystemExit(1) and never touch the release."""
    existing = subprocess.run(["gh", "release", "view", tag, "--json", "targetCommitish,assets"],
                               capture_output=True, text=True)
    if existing.returncode != 0:
        subprocess.run(["gh", "release", "create", tag, jar_path, "--target", commit_sha,
                         "--title", f"Dynamic Population {tag.lstrip('v')}",
                         "--notes", notes, "--prerelease"], check=True)
        print(f"published {tag} @ {commit_sha}")
        return

    data = json.loads(existing.stdout)
    assets = data.get("assets") or []
    jar_name = Path(jar_path).name
    asset = next((a for a in assets if a["name"] == jar_name), None)
    if asset is None:
        raise SystemExit(f"FAIL: release {tag} already exists but has no asset named {jar_name} -- "
                          "not a retry of this exact build; refusing to touch it")

    with tempfile.TemporaryDirectory() as tmp:
        downloaded = Path(tmp) / jar_name
        subprocess.run(["gh", "release", "download", tag, "--pattern", jar_name, "--dir", tmp], check=True)
        existing_sha = sha256_of(downloaded)

    new_sha = sha256_of(jar_path)
    if existing_release_is_identical(data["targetCommitish"], commit_sha, existing_sha, new_sha):
        print(f"release {tag} already exists for this exact commit and jar -- skipping re-publish.")
        return

    raise SystemExit(
        f"FAIL: release {tag} already exists but is NOT identical to this build "
        f"(existing target {data['targetCommitish']} vs {commit_sha}, "
        f"existing jar sha256 {existing_sha} vs {new_sha}). Refusing to overwrite an "
        "immutable release; a tag must never be re-published with different content.")


if __name__ == "__main__":
    command, *args = sys.argv[1:]
    if command == "resolve-version":
        print(resolve_version(*args))
    elif command == "resolve-notes":
        dispatch_notes_file, changelog_file = args
        print(resolve_notes(Path(dispatch_notes_file).read_text(), changelog_file), end="")
    elif command == "check-jar-version":
        jar_path, expected_version = args
        if not jar_declares_version(jar_path, expected_version):
            raise SystemExit(f"FAIL: {jar_path} does not declare version {expected_version}")
        print(f"OK: {jar_path} declares {expected_version}")
    elif command == "publish":
        tag, commit_sha, jar_path, notes_file = args
        publish_or_verify_identical(tag, commit_sha, jar_path, Path(notes_file).read_text())
    else:
        raise SystemExit(f"unknown command: {command}")
