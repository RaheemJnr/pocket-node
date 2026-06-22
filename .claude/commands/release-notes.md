---
description: Draft all release announcement text for a Pocket Node version (GitHub release body, Play changelogs, Nervos Talk, Twitter/X, Telegram)
argument-hint: "[version, e.g. 1.7.4] (optional — defaults to current versionName in build.gradle.kts)"
---

You are drafting the complete set of release-announcement text for Pocket Node. Do NOT publish anything; produce text for the user to review and post. Creating the GitHub release / posting to channels stays a separate, explicit step.

## Inputs to gather first (run these, do not ask the user)

1. **Target version**: `$1` if given, else read `versionName` from `android/app/build.gradle.kts`. Also read `versionCode`.
2. **Last released version**: `gh release list --limit 1` (the current "Latest"), and its tag.
3. **What shipped since then**: `git log <last-tag>..origin/main --oneline`. Group the merged PRs by user-facing theme (sync, Nervos DAO, sending, security/hardening, polish). Read PR titles/bodies as needed (`gh pr view <n>`) to describe changes in plain user language, not commit-speak. Ignore pure-CI / refactor / test-only changes unless they change user-visible behavior.

## House style (do not violate)

- **No em dashes** anywhere in human-facing copy. Use commas, periods, or "and".
- Global audience. Plain English, no jargon. Explain the symptom a user saw, then that it is fixed.
- Lead with the bug fixes users actually reported, then features, then hardening, then polish.
- Credit community feedback warmly (the reports drive these releases). Do not invent names; only name a reporter if the user tells you to.
- Never overstate. If something needs live validation before wide promotion (DAO / sync changes that could not be exercised end to end), remind the user privately at the end of your message, NOT in the public copy.
- Download link is always the release tag URL `https://github.com/RaheemJnr/pocket-node/releases/tag/v<version>` and pocket-node.com.

## Produce these five outputs, each in its own clearly labelled block

1. **GitHub release body** (Markdown). User-facing only, no maintainer notes. Sections by theme with short "what you saw / now fixed" bullets. End with an install line. This same text becomes the in-app updater's "what's new" via `releases/latest`, so keep it self-contained.

2. **Play Store changelogs** — the text for `fastlane/metadata/android/{en-US,es-ES,ru-RU,zh-CN}/changelogs/<versionCode>.txt`. Start each with `v<version>:`. Write en-US first, then accurate translations for es-ES, ru-RU, zh-CN (these locales are genuinely translated in this repo, keep it that way). End each with the localized "Source: github.com/RaheemJnr/pocket-node/releases" line, matching the prior version's files. Offer to write these files.

3. **Nervos Talk forum post** — title + body. Fuller than the others. Thank the community, group by theme, link the release.

4. **Twitter / X** — a single ~280-char post, plus an optional 3-tweet thread version. Punchy, lead with the headline fixes. Include the release link.

5. **Telegram channel** — emoji-led, scannable bullet list grouped by theme, casual but accurate, with the update link.

## After producing the text

- Offer to write the fastlane changelog files and (if not already bumped) the version bump.
- Privately remind the user of any live-validation gaps for this release's changes.
- Do not post or publish; the user does that, or asks you to in a follow-up.
