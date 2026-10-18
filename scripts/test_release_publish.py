import unittest
import zipfile
from pathlib import Path
from tempfile import TemporaryDirectory

from release_publish import (
    existing_release_is_identical,
    jar_declares_version,
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
    def test_same_commit_same_jar_is_identical(self):
        self.assertTrue(existing_release_is_identical("abc", "abc", "sha-a", "sha-a"))

    def test_same_commit_different_jar_is_not_identical(self):
        self.assertFalse(existing_release_is_identical("abc", "abc", "sha-a", "sha-b"))

    def test_different_commit_same_jar_is_not_identical(self):
        self.assertFalse(existing_release_is_identical("abc", "def", "sha-a", "sha-a"))

    def test_different_commit_different_jar_is_not_identical(self):
        self.assertFalse(existing_release_is_identical("abc", "def", "sha-a", "sha-b"))


if __name__ == "__main__":
    unittest.main()
