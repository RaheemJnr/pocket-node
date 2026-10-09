# TestFlight pipeline

Local scripts to archive, export and upload PocketNode for TestFlight. There is
no CI step for any of this; it all runs by hand on a Mac with Xcode.

## The four-step flow

1. `ios/scripts/archive.sh` regenerates the Xcode project with xcodegen and
   produces a Release `.xcarchive` at `ios/build/PocketNode.xcarchive`,
   code-signed automatically with whatever signing identity Xcode finds for
   team `ARF4H4N5CV`.
2. `ios/scripts/export-testflight.sh` writes `ios/build/ExportOptions.plist`
   and exports an `.ipa` from that archive to `ios/build/export/`.
3. `ios/scripts/upload-testflight.sh` uploads the `.ipa` to App Store Connect
   with `altool`. It is never run automatically; you run it by hand once you
   have an API key (see below).
4. TestFlight processing happens on Apple's side after the upload. It usually
   takes a few minutes to a couple of hours before the build shows up as
   available to testers in App Store Connect. Export-compliance review is
   already answered by `ITSAppUsesNonExemptEncryption: false` in
   `project.yml`, so it should not prompt for that on each build.

## One-time steps only the user can do

These two things need to happen once (per Apple account / app) before step 2
above can succeed. Nothing in this repo can do them:

1. **Create a distribution certificate.** Xcode > Settings > Accounts, select
   the `ARF4H4N5CV` team, "Manage Certificates", add an "Apple Distribution"
   certificate. Alternatively, running `archive.sh` or `export-testflight.sh`
   while signed into that account in Xcode with automatic signing can trigger
   Xcode to create it via `-allowProvisioningUpdates`, but it still needs the
   account to have permission to create distribution certs and profiles,
   which is an App Store Connect account setting, not something scriptable
   here.
2. **Create the App Store Connect app record** for bundle id
   `com.rjnr.pocketnode`. Without an app record, there is no "iOS App Store"
   provisioning profile to export against, and `export-testflight.sh` fails
   with the message quoted below.

## What the export says before the one-time steps are done

Running `export-testflight.sh` against a freshly-signed development archive,
with only the "Apple Development" identity installed and no App Store
Connect app record yet, fails with:

```
IDEDistribution: App Store Connect request for store configuration failed for account (null) (Account "(null)": Unable to authenticate with App Store Connect (Error Domain=CDWebService Code=1085 "No provider associated with App Store Connect user" UserInfo={NSLocalizedRecoverySuggestion=, NSLocalizedFailureReason=, NSLocalizedDescription=No provider associated with App Store Connect user}))
error: exportArchive Team "Raheem Jnr" does not have permission to create "iOS App Store" provisioning profiles.
error: exportArchive No profiles for 'com.rjnr.pocketnode' were found
```

This is expected. It means the archive itself is fine (it built and signed
with the development identity), and what is missing is the distribution
certificate and the App Store Connect app record described above. Once both
exist, re-run `export-testflight.sh` with no changes needed.

## Bumping the build number

`project.yml` carries `MARKETING_VERSION` (the user-facing version, e.g.
`0.1.0`) and `CURRENT_PROJECT_VERSION` (the build number App Store Connect
uses to tell builds apart) as target-level settings on `PocketNode`.

- `ios/scripts/archive.sh --bump-build` increments `CURRENT_PROJECT_VERSION`
  by one in `project.yml` before archiving.
- `ios/scripts/archive.sh --version 1.2.0` sets `MARKETING_VERSION` to
  `1.2.0` before archiving.
- Both can be combined: `ios/scripts/archive.sh --version 1.2.0 --bump-build`.

The script edits `project.yml` in place with `sed`; it does not commit
anything. Review the diff and commit it yourself once you are happy with the
new version.

## Where the artefacts land

All of this is under `ios/build/`, which is gitignored:

- `ios/build/PocketNode.xcarchive`, the archive from `archive.sh`.
- `ios/build/ExportOptions.plist`, written fresh by `export-testflight.sh`
  each run.
- `ios/build/export/*.ipa`, the exported `.ipa` from `export-testflight.sh`.
- `ios/build/DerivedData`, the default derived data location, unless you
  override it with `DERIVED_DATA=/some/path`.

## Environment overrides

- `DEVELOPMENT_TEAM` (both `archive.sh` and `export-testflight.sh`), default
  `ARF4H4N5CV`.
- `DERIVED_DATA` (`archive.sh` only), default `ios/build/DerivedData`.
- `ASC_KEY_ID` and `ASC_ISSUER_ID` (`upload-testflight.sh` only, both
  required). Never commit these; the script exits with an explanation if
  either is missing.
