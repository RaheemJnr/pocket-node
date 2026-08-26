from pathlib import Path

from graph.layers import assign_layer, is_backward_edge, load_rules

RULES = Path(__file__).resolve().parent.parent / "rules" / "layers.yaml"
load_rules(RULES)


def test_viewmodel_beats_ui_despite_living_under_ui():
    assert assign_layer("android/app/src/main/java/com/rjnr/pocketnode/ui/screens/home/HomeViewModel.kt") == "viewmodel"


def test_screen_lands_in_ui():
    assert assign_layer("android/app/src/main/java/com/rjnr/pocketnode/ui/screens/home/HomeScreen.kt") == "ui"


def test_jni_beats_rust_core():
    assert assign_layer("external/ckb-light-client/light-client-lib/src/jni_bridge/query.rs") == "jni"


def test_rust_internals_are_rust_core():
    assert assign_layer("external/ckb-light-client/light-client-lib/src/storage.rs") == "rust-core"


def test_kotlin_bridge_object_is_jni_layer():
    assert assign_layer("android/app/src/main/java/com/nervosnetwork/ckblightclient/LightClientNative.kt") == "jni"


def test_unmatched_path_is_unknown_not_an_error():
    assert assign_layer("some/random/path.txt") == "unknown"


def test_backward_edge_direction_convention():
    assert is_backward_edge("ui", "data") is False       # forward: UI depends on data
    assert is_backward_edge("data", "ui") is True        # violation
    assert is_backward_edge("data", "viewmodel") is True


def test_leaf_layers_are_exempt_from_violation_check():
    # Util is a leaf everything may call; depending on it is not a violation
    # even though its lane sits to the right of viewmodel.
    assert is_backward_edge("viewmodel", "util") is False
    assert is_backward_edge("data", "util") is False


def test_auth_is_data_layer_so_migration_can_call_it():
    assert assign_layer("android/app/src/main/java/com/rjnr/pocketnode/data/auth/AuthManager.kt") == "data"
    assert assign_layer("android/app/src/main/java/com/rjnr/pocketnode/data/migration/KeystoreV2MigrationRunner.kt") == "data"
    assert is_backward_edge("data", "data") is False


def test_ios_ui_is_matched_before_the_catch_all_swift_rule():
    # `**/*.swift` in ios-core is broad and first-match-wins, so ios-ui must
    # be declared first or every view lands in the core lane at order 6.
    load_rules(RULES)
    assert assign_layer("ios/Sources/Views/HomeView.swift") == "ios-ui"
    assert assign_layer("ios/Sources/Foo/BarScreen.swift") == "ios-ui"
    assert assign_layer("ios/Sources/Core/Client.swift") == "ios-core"
