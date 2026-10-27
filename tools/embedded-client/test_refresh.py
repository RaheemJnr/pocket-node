"""Regression fixtures for overlay generation; uses only temporary source trees."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


class RefreshTests(unittest.TestCase):
    def test_path_literals_in_hunks_survive_reconstruction(self):
        here = Path(__file__).resolve().parent
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
            subprocess.run(["git", "init", "-q", str(root)], check=True)
            for script in ["refresh.py", "verify.py"]:
                result = subprocess.run(
                    [sys.executable, str(tools / script), "--upstream-tree", str(upstream)],
                    cwd=root, capture_output=True, text=True,
                )
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
