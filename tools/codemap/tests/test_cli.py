from pathlib import Path

from codemap import main

FIXTURE_ROOT = Path(__file__).parent / "fixtures" / "mini"


def test_full_run_writes_both_artifacts(tmp_path):
    assert main(["--root", str(FIXTURE_ROOT), "--out", str(tmp_path)]) == 0
    assert (tmp_path / "codemap.json").exists()
    assert (tmp_path / "codemap.html").exists()


def test_stats_only_skips_html(tmp_path):
    assert main(["--root", str(FIXTURE_ROOT), "--out", str(tmp_path), "--stats-only"]) == 0
    assert (tmp_path / "codemap.json").exists()
    assert not (tmp_path / "codemap.html").exists()


def test_emit_summary_requests_produces_records(tmp_path):
    assert main(["--root", str(FIXTURE_ROOT), "--out", str(tmp_path),
                 "--emit-summary-requests"]) == 0
    reqs = (tmp_path / "summary-requests.jsonl").read_text().splitlines()
    assert len(reqs) > 0
    import json
    r = json.loads(reqs[0])
    assert {"id", "hash", "source", "signature"} <= set(r)
