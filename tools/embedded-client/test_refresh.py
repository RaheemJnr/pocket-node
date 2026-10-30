"""Regression fixtures for overlay generation; uses only temporary source trees."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


def fixture_environment():
    inherited = os.environ.copy()
    # Discover Git's own repository-local names without letting a foreign repository/config
    # affect discovery. Only those names are removed from the actual fixture environment.
    discovery = {key: value for key, value in inherited.items() if not key.startswith("GIT_")}
    local_names = subprocess.check_output(
        ["git", "rev-parse", "--local-env-vars"], env=discovery, text=True,
    ).splitlines()
    return {key: value for key, value in inherited.items() if key not in local_names}


class RefreshTests(unittest.TestCase):
    def test_path_literals_in_hunks_survive_reconstruction(self):
        here = Path(__file__).resolve().parent
        environment = fixture_environment()
        with tempfile.TemporaryDirectory(prefix="pocket-overlay-test-") as directory:
            root = Path(directory)
            tools = root / "tools/embedded-client"
            tools.mkdir(parents=True)
            for script in ["refresh.py", "verify.py"]:
                shutil.copy2(here / script, tools / script)
            upstream = root / "upstream-source"
            vendor = root / "external/ckb-light-client"
            upstream.mkdir()
            vendor.mkdir(parents=True)
            literals = b"a/upstream/ b/mobile/ a/mobile/ b/upstream/\n"
            original = {
                "source.txt": literals + b"-- a/upstream/literal\nold\n",
                "deleted file.txt": literals,
                "binary.dat": b"\x00old\xff",
                'quoted "path".txt': b"old\n",
                'prefix b/mobile/literal.txt': b"old\n",
            }
            modified = {
                "source.txt": literals + b"++ b/mobile/literal\nnew " + literals,
                "added file.txt": literals,
                "binary.dat": b"\x00new\xff" + literals,
                'quoted "path".txt': b"new\n",
                'prefix b/mobile/literal.txt': b"new\n",
            }
            for path, content in original.items():
                (upstream / path).parent.mkdir(parents=True, exist_ok=True)
                (upstream / path).write_bytes(content)
            for path, content in modified.items():
                (vendor / path).parent.mkdir(parents=True, exist_ok=True)
                (vendor / path).write_bytes(content)
            manifest = {
                "upstream_commit": "fixture",
                "upstream_files": {
                    path: hashlib.sha256(content).hexdigest()
                    for path, content in original.items()
                },
            }
            (tools / "upstream.json").write_text(json.dumps(manifest))
            subprocess.run(["git", "init", "-q", str(root)], env=environment, check=True)
            for script in ["refresh.py", "verify.py"]:
                result = subprocess.run(
                    [sys.executable, str(tools / script), "--upstream-tree", str(upstream)],
                    cwd=root, env=environment, capture_output=True, text=True,
                )
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            generated = json.loads((tools / "upstream.json").read_text())
            self.assertEqual(generated["vendor_files"], {
                path: hashlib.sha256(content).hexdigest() for path, content in modified.items()
            }, "Passing reconstruction must include the actual fixture files")

    def test_foreign_repository_and_index_do_not_affect_fixture(self):
        # These repositories and all Git metadata are disposable, never the caller's checkout.
        clean_env = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
        with tempfile.TemporaryDirectory(prefix="pocket-foreign-git-test-") as directory:
            root = Path(directory)
            foreign = root / "foreign"
            foreign.mkdir()
            subprocess.run(["git", "init", "-q", str(foreign)], env=clean_env, check=True)
            staged = foreign / "external/ckb-light-client/foreign-only.txt"
            staged.parent.mkdir(parents=True)
            staged.write_text("unrelated synthetic repository\n")
            subprocess.run(["git", "add", "."], cwd=foreign, env=clean_env, check=True)
            index = root / "foreign-index"
            shutil.copyfile(foreign / ".git/index", index)

            def snapshot():
                return {path.relative_to(root).as_posix(): path.read_bytes()
                        for path in root.rglob("*") if path.is_file()}

            for index_bytes in [index.read_bytes(), b"not a Git index\n"]:
                with self.subTest(valid_index=index_bytes.startswith(b"DIRC")):
                    index.write_bytes(index_bytes)
                    before = snapshot()
                    with patch.dict(os.environ, {
                        "GIT_DIR": str(foreign / ".git"),
                        "GIT_WORK_TREE": str(foreign),
                        "GIT_INDEX_FILE": str(index),
                        "GIT_COMMON_DIR": str(foreign / ".git"),
                        "GIT_OBJECT_DIRECTORY": str(foreign / ".git/objects"),
                    }):
                        try:
                            self.test_path_literals_in_hunks_survive_reconstruction()
                        finally:
                            self.assertEqual(before, snapshot(), "Fixture must not change foreign repository or index")

    def test_fixture_environment_preserves_unrelated_variables(self):
        with patch.dict(os.environ, {
            "GIT_DIR": "/synthetic/foreign",
            "GIT_INDEX_FILE": "/synthetic/index",
            "GIT_CONFIG_COUNT": "1",
            "GIT_CONFIG_KEY_0": "core.bare",
            "GIT_CONFIG_VALUE_0": "true",
            "GIT_AUTHOR_NAME": "Synthetic Test Author",
            "POCKET_TEST_SENTINEL": "keep this value",
        }):
            environment = fixture_environment()
            for name in ["GIT_DIR", "GIT_INDEX_FILE", "GIT_CONFIG_COUNT"]:
                self.assertNotIn(name, environment)
            self.assertEqual(environment["GIT_AUTHOR_NAME"], "Synthetic Test Author")
            self.assertEqual(environment["POCKET_TEST_SENTINEL"], "keep this value")
            self.assertEqual(os.environ["GIT_DIR"], "/synthetic/foreign")


if __name__ == "__main__":
    unittest.main()
