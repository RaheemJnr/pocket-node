from summaries.store import SummaryStore


def test_summary_is_current_when_hash_matches():
    s = SummaryStore({"kt:A#f/0": {"hash": "abc123def456", "text": "Does a thing."}})
    assert s.get("kt:A#f/0", "abc123def456").state == "current"


def test_summary_is_stale_when_hash_differs():
    s = SummaryStore({"kt:A#f/0": {"hash": "abc123def456", "text": "Does a thing."}})
    got = s.get("kt:A#f/0", "999999999999")
    assert got.state == "stale"
    assert got.text == "Does a thing."      # still returned, for greyed display


def test_missing_summary_reports_absent():
    assert SummaryStore({}).get("kt:A#f/0", "abc").state == "absent"


def test_import_rejects_hash_mismatch():
    s = SummaryStore({})
    n = s.import_responses([{"id": "kt:A#f/0", "hash": "aaa", "text": "x"}],
                           {"kt:A#f/0": "bbb"})
    assert n == 0
    assert s.get("kt:A#f/0", "bbb").state == "absent"


def test_import_accepts_matching_hash():
    s = SummaryStore({})
    n = s.import_responses([{"id": "kt:A#f/0", "hash": "bbb", "text": "x"}],
                           {"kt:A#f/0": "bbb"})
    assert n == 1
    assert s.get("kt:A#f/0", "bbb").state == "current"
