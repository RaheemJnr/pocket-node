#!/usr/bin/env bash
# Exports a TestFlight-ready .ipa from the archive produced by archive.sh.
# Requires a distribution certificate and an App Store Connect app record for
# com.rjnr.pocketnode to exist first; see ios/scripts/README.md.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IOS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

TEAM_ID="${DEVELOPMENT_TEAM:-ARF4H4N5CV}"
ARCHIVE_PATH="$IOS_DIR/build/PocketNode.xcarchive"
EXPORT_OPTIONS_PLIST="$IOS_DIR/build/ExportOptions.plist"
EXPORT_PATH="$IOS_DIR/build/export"

if [[ ! -d "$ARCHIVE_PATH" ]]; then
  echo "No archive found at $ARCHIVE_PATH. Run ios/scripts/archive.sh first." >&2
  exit 1
fi

mkdir -p "$IOS_DIR/build"

cat > "$EXPORT_OPTIONS_PLIST" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>method</key>
    <string>app-store-connect</string>
    <key>teamID</key>
    <string>$TEAM_ID</string>
    <key>signingStyle</key>
    <string>automatic</string>
    <key>uploadSymbols</key>
    <true/>
    <key>destination</key>
    <string>export</string>
</dict>
</plist>
PLIST

echo "== Exporting .ipa for App Store Connect (team $TEAM_ID) =="
xcodebuild -exportArchive \
  -archivePath "$ARCHIVE_PATH" \
  -exportOptionsPlist "$EXPORT_OPTIONS_PLIST" \
  -exportPath "$EXPORT_PATH" \
  -allowProvisioningUpdates

IPA_PATH="$(find "$EXPORT_PATH" -maxdepth 1 -name '*.ipa' -print -quit)"
echo "== Export complete =="
echo "IPA path: $IPA_PATH"
