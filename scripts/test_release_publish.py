import unittest
import zipfile
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

import release_publish
from release_publish import (
    GhApiError,
    ReleaseNotFound,
    existing_release_is_identical,
    jar_declares_version,
    publish_or_verify_identical,
    validate_dispatch_pair,
    validate_notes,
    validate_version,
)


def _make_jar(path, version):
    with zipfile.ZipFile(path, "w") as zf:
        zf.writestr("META-INF/neoforge.mods.toml", f'modLoader="javafml"\nversion="{version}"\n')


class ValidateVersionTest(unittest.TestCase):
    def test_accepts_prerelease_version(self):
        self.assertEqual(validate_version("0.2.0-alpha.1"), "0.2.0-alpha.1")

    def test_accepts_plain_semver(self):
        self.assertEqual(validate_version("1.2.3"), "1.2.3")

    def test_strips_surrounding_whitespace(self):
        self.assertEqual(validate_version("  0.2.0-alpha.1\n"), "0.2.0-alpha.1")

    def test_rejects_empty(self):
        with self.assertRaises(ValueError):
            validate_version("")

    def test_rejects_garbage(self):
        with self.assertRaises(ValueError):
            validate_version("not-a-version")

    def test_rejects_leading_v(self):
        with self.assertRaises(ValueError):
            validate_version("v0.2.0-alpha.1")


class ValidateNotesTest(unittest.TestCase):
    def test_accepts_real_notes(self):
        self.assertEqual(validate_notes("### Added\n- a thing"), "### Added\n- a thing")

    def test_rejects_empty(self):
        with self.assertRaises(ValueError):
            validate_notes("")

    def test_rejects_whitespace_only(self):
        with self.assertRaises(ValueError):
            validate_notes("   \n  \n")

    def test_rejects_none(self):
        with self.assertRaises(ValueError):
            validate_notes(None)


class ValidateDispatchPairTest(unittest.TestCase):
    def test_both_blank_is_valid(self):
        validate_dispatch_pair("", "")
        validate_dispatch_pair(None, None)

    def test_both_set_is_valid(self):
        validate_dispatch_pair("0.2.0-alpha.1", "### Added\n- a thing")

    def test_version_only_is_rejected(self):
        with self.assertRaises(ValueError):
            validate_dispatch_pair("0.2.0-alpha.1", "")

    def test_notes_only_is_rejected(self):
        with self.assertRaises(ValueError):
            validate_dispatch_pair("", "### Added\n- a thing")

    def test_whitespace_only_input_counts_as_blank(self):
        validate_dispatch_pair("   \n", "  ")


class JarDeclaresVersionTest(unittest.TestCase):
    def test_matching_version_agrees(self):
        with TemporaryDirectory() as tmp:
            jar_path = Path(tmp) / "dynamicpopulation-0.2.0-alpha.1.jar"
            _make_jar(jar_path, "0.2.0-alpha.1")
            self.assertTrue(jar_declares_version(jar_path, "0.2.0-alpha.1"))

    def test_mismatched_version_disagrees(self):
        with TemporaryDirectory() as tmp:
            jar_path = Path(tmp) / "dynamicpopulation-0.1.0-alpha.1.jar"
            _make_jar(jar_path, "0.1.0-alpha.1")
            self.assertFalse(jar_declares_version(jar_path, "0.2.0-alpha.1"))

    def test_missing_version_line_raises(self):
        with TemporaryDirectory() as tmp:
            jar_path = Path(tmp) / "bad.jar"
            with zipfile.ZipFile(jar_path, "w") as zf:
                zf.writestr("META-INF/neoforge.mods.toml", 'modLoader="javafml"\n')
            with self.assertRaises(ValueError):
                jar_declares_version(jar_path, "0.2.0-alpha.1")


class ExistingReleaseIsIdenticalTest(unittest.TestCase):
    def test_all_three_matching_is_identical(self):
        self.assertTrue(existing_release_is_identical("abc", "abc", "abc", "sha-a", "sha-a"))

    def test_tag_commit_disagreeing_with_new_commit_is_not_identical(self):
        # targetCommitish and jar agree, but the tag itself points elsewhere
        # (the exact "gh release create --target doesn't move the tag" case).
        self.assertFalse(existing_release_is_identical("other-commit", "abc", "abc", "sha-a", "sha-a"))

    def test_target_commitish_disagreeing_with_new_commit_is_not_identical(self):
        self.assertFalse(existing_release_is_identical("abc", "other-commit", "abc", "sha-a", "sha-a"))

    def test_different_jar_is_not_identical(self):
        self.assertFalse(existing_release_is_identical("abc", "abc", "abc", "sha-a", "sha-b"))

    def test_all_different_is_not_identical(self):
        self.assertFalse(existing_release_is_identical("x", "y", "z", "sha-a", "sha-b"))


class PublishOrVerifyIdenticalTest(unittest.TestCase):
    """Mocked publish-path tests: exercise the subprocess-driven decision
    flow in publish_or_verify_identical directly, not just the pure
    helpers it calls."""

    def setUp(self):
        self.tmp = TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.jar_path = Path(self.tmp.name) / "dynamicpopulation-0.2.0-alpha.1.jar"
        self.jar_path.write_bytes(b"fake jar bytes")

    @patch("release_publish.gh_create_release")
    @patch("release_publish.gh_release_view")
    def test_creates_release_when_none_exists(self, mock_view, mock_create):
        mock_view.side_effect = ReleaseNotFound("v0.2.0-alpha.1")
        publish_or_verify_identical("v0.2.0-alpha.1", "commit-a", str(self.jar_path), "notes")
        mock_create.assert_called_once_with("v0.2.0-alpha.1", str(self.jar_path), "commit-a", "notes")

    @patch("release_publish.gh_release_view")
    def test_api_error_fails_closed_without_creating(self, mock_view):
        # A non-404 failure (auth, network, rate limit) must never be
        # treated as "safe to create" -- it must propagate and the release
        # workflow step must fail.
        mock_view.side_effect = GhApiError("401 Unauthorized")
        with self.assertRaises(GhApiError):
            publish_or_verify_identical("v0.2.0-alpha.1", "commit-a", str(self.jar_path), "notes")

    @patch("release_publish.gh_download_asset")
    @patch("release_publish.gh_resolve_ref_commit")
    @patch("release_publish.gh_release_view")
    def test_identical_retry_is_skipped_without_recreating(self, mock_view, mock_resolve, mock_download):
        mock_view.return_value = {"targetCommitish": "commit-a", "assets": [{"name": self.jar_path.name}]}
        mock_resolve.return_value = "commit-a"

        def fake_download(tag, jar_name, dest_dir):
            (Path(dest_dir) / jar_name).write_bytes(self.jar_path.read_bytes())

        mock_download.side_effect = fake_download
        with patch("release_publish.gh_create_release") as mock_create:
            publish_or_verify_identical("v0.2.0-alpha.1", "commit-a", str(self.jar_path), "notes")
            mock_create.assert_not_called()

    @patch("release_publish.gh_download_asset")
    @patch("release_publish.gh_resolve_ref_commit")
    @patch("release_publish.gh_release_view")
    def test_tag_pointing_elsewhere_fails_even_though_target_commitish_and_jar_agree(
        self, mock_view, mock_resolve, mock_download
    ):
        # The exact scenario the review flagged: gh release create --target
        # does not move a pre-existing tag, so targetCommitish can agree
        # with the new build while the tag itself still points elsewhere.
        mock_view.return_value = {"targetCommitish": "commit-a", "assets": [{"name": self.jar_path.name}]}
        mock_resolve.return_value = "some-stale-commit"

        def fake_download(tag, jar_name, dest_dir):
            (Path(dest_dir) / jar_name).write_bytes(self.jar_path.read_bytes())

        mock_download.side_effect = fake_download
        with patch("release_publish.gh_create_release") as mock_create:
            with self.assertRaises(SystemExit):
                publish_or_verify_identical("v0.2.0-alpha.1", "commit-a", str(self.jar_path), "notes")
            mock_create.assert_not_called()

    @patch("release_publish.gh_download_asset")
    @patch("release_publish.gh_resolve_ref_commit")
    @patch("release_publish.gh_release_view")
    def test_different_jar_content_fails_even_with_matching_commits(self, mock_view, mock_resolve, mock_download):
        mock_view.return_value = {"targetCommitish": "commit-a", "assets": [{"name": self.jar_path.name}]}
        mock_resolve.return_value = "commit-a"

        def fake_download(tag, jar_name, dest_dir):
            (Path(dest_dir) / jar_name).write_bytes(b"different bytes entirely")

        mock_download.side_effect = fake_download
        with self.assertRaises(SystemExit):
            publish_or_verify_identical("v0.2.0-alpha.1", "commit-a", str(self.jar_path), "notes")

    @patch("release_publish.gh_release_view")
    def test_existing_release_missing_expected_asset_fails_closed(self, mock_view):
        mock_view.return_value = {"targetCommitish": "commit-a", "assets": [{"name": "some-other-file.jar"}]}
        with self.assertRaises(SystemExit):
            publish_or_verify_identical("v0.2.0-alpha.1", "commit-a", str(self.jar_path), "notes")


if __name__ == "__main__":
    unittest.main()
