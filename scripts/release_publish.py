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


def validate_dispatch_pair(dispatch_version, dispatch_notes):
    """A workflow_dispatch version and notes input must travel together.
    version set with notes blank would silently fall back to the old
    scaffold CHANGELOG.md for an explicit new version; notes set with
    version blank would publish explicit notes under version.txt's
    (wrong) version. Only "both blank" (fall back to version.txt/
    CHANGELOG.md entirely) or "both set" (fully explicit) are valid."""
    version_set = bool((dispatch_version or "").strip())
    notes_set = bool((dispatch_notes or "").strip())
    if version_set != notes_set:
        raise ValueError(
            "workflow_dispatch's version and notes inputs must both be set or both left "
            f"blank (got version={'set' if version_set else 'blank'}, notes={'set' if notes_set else 'blank'})")


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


def existing_release_is_identical(tag_commit_sha, target_commitish, new_commit_sha, existing_jar_sha256, new_jar_sha256):
    """True only if a pre-existing release for this tag is provably the same
    commit AND the same jar bytes as what we are about to publish -- the
    only condition under which a retry may treat it as already-done.

    Checks the tag's OWN resolved commit (`tag_commit_sha`), not just the
    release's `targetCommitish`: `gh release create --target` does not move
    a pre-existing tag, so a tag left over at another commit can make a
    release's targetCommitish agree with the current build while the tag
    itself -- the thing an installer actually downloads by ref -- still
    points elsewhere. All three of tag commit, targetCommitish, and jar
    sha256 must agree; any single disagreement must fail, never be
    silently accepted or overwritten.
    """
    return (tag_commit_sha == new_commit_sha
            and target_commitish == new_commit_sha
            and existing_jar_sha256 == new_jar_sha256)


class ReleaseNotFound(Exception):
    """A confirmed 404: the tag/release does not exist. Distinct from
    GhApiError so callers never mistake an auth/network failure for
    "safe to create"."""


class GhApiError(Exception):
    """gh/the GitHub API failed in a way that is NOT a confirmed 404 --
    auth, network, rate limit, unexpected response shape. Callers must
    propagate this and fail closed, never fall back to treating it as
    "release doesn't exist"."""


def gh_release_view(tag):
    """Return `{targetCommitish, assets}` for the release tagged `tag`.
    Raises ReleaseNotFound only on gh's own confirmed "release not found"
    message; any other failure raises GhApiError."""
    proc = subprocess.run(["gh", "release", "view", tag, "--json", "targetCommitish,assets"],
                           capture_output=True, text=True)
    if proc.returncode == 0:
        return json.loads(proc.stdout)
    if "release not found" in proc.stderr.lower():
        raise ReleaseNotFound(tag)
    raise GhApiError(f"gh release view {tag} failed (not a confirmed not-found): {proc.stderr.strip()}")


def gh_commit_for_ref_or_none(ref):
    """Resolve a git ref (here, always a tag) to the commit sha it actually
    points at via the GitHub API, which dereferences an annotated tag
    itself -- never trust a release field for this. Returns None only on
    a confirmed "this ref does not exist" response. Raises GhApiError on
    any other failure (auth, network, rate limit, unexpected shape) --
    never silently treated as "doesn't exist".

    GitHub's commits endpoint (`GET /repos/{owner}/{repo}/commits/{ref}`)
    answers an unresolvable ref with HTTP 422 "No commit found for SHA:
    <ref>", not 404 -- confirmed live against an actual nonexistent tag
    (gh: No commit found for SHA: v0.2.0-alpha.1 (HTTP 422)). Both that
    and an explicit 404 count as confirmed-absent.
    """
    proc = subprocess.run(["gh", "api", f"repos/{{owner}}/{{repo}}/commits/{ref}", "--jq", ".sha"],
                           capture_output=True, text=True)
    if proc.returncode == 0:
        return proc.stdout.strip()
    if ref_confirmed_absent(proc.stderr):
        return None
    raise GhApiError(f"could not resolve ref {ref} to a commit (not a confirmed not-found): {proc.stderr.strip()}")


def ref_confirmed_absent(stderr):
    """True if `stderr` from the commits API confirms the ref simply does
    not exist -- a 404, or the 422 "No commit found for SHA" GitHub
    actually returns for an unresolvable ref (confirmed live against a
    real nonexistent tag). False for anything else (auth, network, rate
    limit), which the caller must treat as a hard failure, not absence."""
    return "HTTP 404" in stderr or "Not Found" in stderr or "No commit found for SHA" in stderr


def gh_download_asset(tag, jar_name, dest_dir):
    subprocess.run(["gh", "release", "download", tag, "--pattern", jar_name, "--dir", dest_dir], check=True)


def gh_create_release(tag, jar_path, commit_sha, notes):
    subprocess.run(["gh", "release", "create", tag, jar_path, "--target", commit_sha,
                     "--title", f"Dynamic Population {tag.lstrip('v')}",
                     "--notes", notes, "--prerelease"], check=True)


def resolve_version(dispatch_version, version_file):
    return validate_version(dispatch_version or Path(version_file).read_text())


def resolve_notes(dispatch_notes, changelog_file):
    return validate_notes(dispatch_notes or Path(changelog_file).read_text())


def publish_or_verify_identical(tag, commit_sha, jar_path, notes):
    """Create the immutable GitHub release for `tag`, or -- if it already
    exists -- verify it is identical to what we would publish and exit
    cleanly. Any mismatch, or any API failure that isn't a confirmed
    not-found, raises and never touches the release (fail closed)."""
    try:
        existing = gh_release_view(tag)
    except ReleaseNotFound:
        # No release yet, but `gh release create --target` does NOT move a
        # pre-existing tag -- a tag can exist (e.g. a prior partial
        # failure, or something else created it) without a release. Check
        # the tag's own identity before creating, not just the release's.
        tag_commit = gh_commit_for_ref_or_none(tag)
        if tag_commit is not None and tag_commit != commit_sha:
            raise SystemExit(
                f"FAIL: no release exists for {tag}, but the git tag itself already exists at commit "
                f"{tag_commit}, not this build's commit {commit_sha}. gh release create --target would "
                "not move it; refusing to publish a release whose tag would point at the wrong commit.")
        gh_create_release(tag, jar_path, commit_sha, notes)
        print(f"published {tag} @ {commit_sha}")
        return

    assets = existing.get("assets") or []
    jar_name = Path(jar_path).name
    asset = next((a for a in assets if a["name"] == jar_name), None)
    if asset is None:
        raise SystemExit(f"FAIL: release {tag} already exists but has no asset named {jar_name} -- "
                          "not a retry of this exact build; refusing to touch it")

    tag_commit = gh_commit_for_ref_or_none(tag)
    if tag_commit is None:
        raise SystemExit(f"FAIL: release {tag} exists but its git tag does not resolve to any commit -- "
                          "an inconsistent state; refusing to touch it")

    with tempfile.TemporaryDirectory() as tmp:
        gh_download_asset(tag, jar_name, tmp)
        existing_sha = sha256_of(Path(tmp) / jar_name)

    new_sha = sha256_of(jar_path)
    if existing_release_is_identical(tag_commit, existing["targetCommitish"], commit_sha, existing_sha, new_sha):
        print(f"release {tag} already exists for this exact commit and jar -- skipping re-publish.")
        return

    raise SystemExit(
        f"FAIL: release {tag} already exists but is NOT identical to this build "
        f"(tag {tag} resolves to commit {tag_commit}, release targetCommitish {existing['targetCommitish']}, "
        f"new commit {commit_sha}; existing jar sha256 {existing_sha} vs new {new_sha}). Refusing to overwrite "
        "an immutable release; a tag must never be re-published with different content.")


if __name__ == "__main__":
    command, *args = sys.argv[1:]
    if command == "validate-dispatch-pair":
        dispatch_version, dispatch_notes = args
        validate_dispatch_pair(dispatch_version, dispatch_notes)
        print("OK: dispatch version/notes pair is valid")
    elif command == "resolve-version":
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
