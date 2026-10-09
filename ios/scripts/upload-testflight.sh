#!/usr/bin/env bash
# Uploads the exported .ipa to App Store Connect / TestFlight.
#
# This script is NOT run as part of the build pipeline. It requires an App
# Store Connect API key that only the user holds; never commit one. Get the
# key from App Store Connect > Users and Access > Integrations > App Store
# Connect API, then export:
#   export ASC_KEY_ID=...
#   export ASC_ISSUER_ID=...
# The .p8 private key file itself must already be installed where `altool`
# expects it (~/.appstoreconnect/private_keys/), per Apple's documentation.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IOS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

if [[ -z "${ASC_KEY_ID:-}" || -z "${ASC_ISSUER_ID:-}" ]]; then
  cat >&2 <<EOF
Missing ASC_KEY_ID and/or ASC_ISSUER_ID.

This script uploads to App Store Connect and needs an API key that only you
hold. Create one in App Store Connect under Users and Access > Integrations
> App Store Connect API, install the downloaded .p8 file under
~/.appstoreconnect/private_keys/, then export ASC_KEY_ID and ASC_ISSUER_ID
before running this script again. Never commit the key or these values.
EOF
  exit 2
fi

IPA_PATH="${1:-}"
if [[ -z "$IPA_PATH" ]]; then
  IPA_PATH="$(find "$IOS_DIR/build/export" -maxdepth 1 -name '*.ipa' -print -quit 2>/dev/null || true)"
fi
if [[ -z "$IPA_PATH" || ! -f "$IPA_PATH" ]]; then
  echo "No .ipa found. Pass one explicitly: $0 <path-to-ipa>" >&2
  exit 1
fi

echo "== Uploading $IPA_PATH to App Store Connect =="
xcrun altool --upload-app \
  -f "$IPA_PATH" \
  -t ios \
  --apiKey "$ASC_KEY_ID" \
  --apiIssuer "$ASC_ISSUER_ID"
