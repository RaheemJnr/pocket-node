# Play Store "What's new" Changelog Template

Each new versionCode that ships to the Play Store needs a per-locale changelog file at:

    fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt

This document gives you a reusable template plus editorial rules so the
copy stays consistent across releases without re-bikeshedding tone every
two weeks.

## Hard limit

Each `<versionCode>.txt` is capped at **500 characters** by Google Play.
Newlines and spaces count. Verify with:

```bash
python3 -c "print(len(open('fastlane/metadata/android/en-US/changelogs/11.txt').read()))"
```

## Tone rules

- Lead with the user-visible win, not the implementation. "New releases install in one tap" beats "Refactored UpdateRepository for Ktor downloader". Save technical detail for the GitHub release notes.
- No em dashes (project preference).
- Avoid the words **secure**, **safe**, **guaranteed**. Make specific feature claims instead.
- Active voice. "We added X" or "X now works on Y" rather than "X has been added".
- 3 to 5 bullets max. If you need more, the release is probably too big to ship as one Play Store update.

## Template

```
v<version>:

- <user-visible win one>
- <user-visible win two>
- <bug fix or polish, framed as benefit>

Open source under MIT. Source and full release notes at github.com/RaheemJnr/pocket-node/releases.
```

## Worked example (v1.6.1)

```
v1.6.1:
- Auto-update now actually works. When a new version is found, a compact banner sits above the bottom navigation and shows real-time download progress. Tap when ready to install.
- If the app needs install-from-unknown-sources permission, returning from Android settings resumes the download automatically. No more re-tapping Update.
- Cancel an in-progress download by tapping the banner. Retry a failed one the same way.
- The downloaded APK is cleaned up after install or cancellation, so the update never sits around eating disk.
```

## Localisation

Translate the en-US changelog into each supported locale (currently zh-CN, es-ES, ru-RU). Until native-speaker review is in place, machine-translation seed copy is acceptable for closed testing tracks but should be reviewed before production.

## Where the en-US copy comes from

The source of truth is the **GitHub release notes** for the corresponding tag. Pull the user-visible bullets from there, trim to 500 characters, and drop into the en-US changelog file first. Translations follow.
