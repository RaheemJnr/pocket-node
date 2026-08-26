from graph.issues import find_issues


def test_finds_plain_issue_references():
    assert find_issues("fix per #332 and #424") == ["332", "424"]


def test_ignores_hex_colours():
    assert find_issues("colour #ffcc00 is fine") == []


def test_ignores_html_entities():
    assert find_issues("&#8212; an em dash") == []


def test_deduplicates_and_sorts_numerically():
    assert find_issues("#40 #7 #40") == ["7", "40"]


def test_handles_empty_input():
    assert find_issues("") == [] and find_issues(None) == []
