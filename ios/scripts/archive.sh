#!/usr/bin/env bash
# Regenerates the Xcode project and produces a Release .xcarchive for
# PocketNode, signed automatically with whatever development identity is
# available. See ios/scripts/README.md for the full TestFlight flow.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IOS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PROJECT_YML="$IOS_DIR/project.yml"

DEVELOPMENT_TEAM="${DEVELOPMENT_TEAM:-ARF4H4N5CV}"
DERIVED_DATA="${DERIVED_DATA:-$IOS_DIR/build/DerivedData}"
ARCHIVE_PATH="$IOS_DIR/build/PocketNode.xcarchive"

NEW_VERSION=""
BUMP_BUILD=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --bump-build)
      BUMP_BUILD=1
      shift
      ;;
    --version)
      NEW_VERSION="${2:?--version requires a value, e.g. --version 1.2.0}"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      echo "Usage: $0 [--bump-build] [--version X.Y.Z]" >&2
      exit 1
      ;;
  esac
done

if [[ -n "$NEW_VERSION" ]]; then
  echo "Setting MARKETING_VERSION to $NEW_VERSION in project.yml"
  sed -i '' -E "s/MARKETING_VERSION: \"[^\"]*\"/MARKETING_VERSION: \"$NEW_VERSION\"/" "$PROJECT_YML"
fi

if [[ "$BUMP_BUILD" -eq 1 ]]; then
  CURRENT_BUILD="$(sed -nE 's/.*CURRENT_PROJECT_VERSION: "([0-9]+)".*/\1/p' "$PROJECT_YML" | head -1)"
  if [[ -z "$CURRENT_BUILD" ]]; then
    echo "Could not find CURRENT_PROJECT_VERSION in project.yml" >&2
    exit 1
  fi
  NEXT_BUILD=$((CURRENT_BUILD + 1))
  echo "Bumping CURRENT_PROJECT_VERSION: $CURRENT_BUILD -> $NEXT_BUILD"
  sed -i '' -E "s/CURRENT_PROJECT_VERSION: \"[0-9]+\"/CURRENT_PROJECT_VERSION: \"$NEXT_BUILD\"/" "$PROJECT_YML"
fi

echo "== Regenerating Xcode project =="
(cd "$IOS_DIR" && xcodegen generate)

echo "== Archiving PocketNode (Release, team $DEVELOPMENT_TEAM) =="
xcodebuild archive \
  -project "$IOS_DIR/PocketNode.xcodeproj" \
  -scheme PocketNode \
  -configuration Release \
  -destination 'generic/platform=iOS' \
  -archivePath "$ARCHIVE_PATH" \
  -derivedDataPath "$DERIVED_DATA" \
  -allowProvisioningUpdates \
  DEVELOPMENT_TEAM="$DEVELOPMENT_TEAM"

APP_PLIST="$ARCHIVE_PATH/Products/Applications/PocketNode.app/Info.plist"
SHORT_VERSION="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$APP_PLIST")"
BUILD_VERSION="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "$APP_PLIST")"

echo "== Archive complete =="
echo "Archive path: $ARCHIVE_PATH"
echo "CFBundleShortVersionString: $SHORT_VERSION"
echo "CFBundleVersion: $BUILD_VERSION"
