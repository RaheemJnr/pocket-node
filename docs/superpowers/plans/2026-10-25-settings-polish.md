# Settings Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Apply approved compact Settings and Wallet settings layouts without changing behavior.
**Architecture:** Existing Compose screens retain ViewModel callbacks and security gates. Public display/navigation state selects local detail pages or Material bottom sheets; sensitive native flows remain unchanged.
**Tech Stack:** Kotlin, Jetpack Compose Material3, Robolectric Compose tests.
**Spec:** docs/superpowers/specs/2026-10-25-settings-polish.md

## Global Constraints
- Preserve every existing action, real backup status, wallet-type capability and security gate.
- No release builds, signing, publishing or merging. Debug validation only.
- Retain theme colours; use focused sheets for short public choices and detail pages for groups.
- Never put wallet secrets in ordinary Compose sheets or change vault/auth code.
- Use the existing isolated clone /tmp/pocket-node-576-fix on feat/wallet-protection-header.

## Review Focus
- Back/rotation with an open detail or sheet must preserve sensible navigation.
- Private-key/subaccount wallets must not gain unsupported account actions.
- Build variants must not expose unavailable background sync/updater controls.
- Privacy disclosures and pending-transaction/DAO removal warnings remain visible at decisions.
- Long names/large fonts and real backup pending/completed state remain readable and truthful.

### Task 1: Implement both approved settings layouts

**Files:** Modify android/app/src/main/java/com/rjnr/pocketnode/ui/screens/settings/SettingsScreen.kt and ui/screens/wallet/WalletSettingsScreen.kt; extract focused content components in those directories if useful. Add targeted Compose navigation tests in corresponding src/test directories. Preserve ViewModels; resources may be added for new labels.
**Interfaces:** Existing screen parameters and callbacks remain stable. Expose internal stateless content composables for tests as appropriate; no shared crypto interfaces.

- [ ] Add focused UI regression tests: root settings groups, nested sync/privacy/about action reachability, Back, wallet backup/recovery/details/name/accounts/remove routing and wallet capability visibility. Reuse existing fake/state patterns; test meaningful observable callbacks rather than source text.
- [ ] Run new tests and confirm missing-layout failure before implementation.
- [ ] Implement approved grouping, focused rename/details/education sheets, state/back handling and main Settings nested destinations. Keep every existing option and disclosure, background-sync build gate, update/version behavior, secure secret callback and deletion flow. Public sheet must dismiss before launching sensitive flow.
- [ ] Run relevant settings and wallet tests plus :app:assembleDebug -x cargoBuild; expect zero failures and BUILD SUCCESSFUL. JAVA_HOME=/Users/raheemjnr/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home; ./android/gradlew -p android; --max-workers=2; -Dorg.gradle.jvmargs=-Xmx2g. Redirect long logs to /tmp/pocket578.
- [ ] Inspect diff and commit only task files, excluding generated .kotlin cache.
- [ ] Independent task review followed by whole-change review; resolve actionable findings before proposing integration.
- [ ] Install debug APK on emulator-5582, smoke-test destinations and capture updated screens without touching wallet secrets or deleting data.
