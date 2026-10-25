# Simplified Wallet Protection Implementation Plan

> Use superpowers:executing-plans inline.

**Goal:** Implement the approved compact overview without removing capabilities.
**Architecture:** Presentation-only sections and modal sheets reuse existing callbacks. Parent retains app-lock state and navigation.
**Tech Stack:** Kotlin, Compose Material 3.
**Spec:** docs/superpowers/specs/2026-10-25-simple-protection-design.md

## Constraints
No crypto/storage/authentication changes or release build. Native secret dialogs remain unchanged.

## Review focus
All unlock modes, both reset/enrollment modes, backup wallet IDs and recovery stay reachable. Dismiss informational sheets before invoking native prompts. Back returns to overview; PIN-return retains app-lock section. Busy operations retain cancellation and outcomes. No false protection claims.

## Tasks
- [x] Add navigation regression for overview hiding reset/enrollment, sheet routing of explicit screen lock reset, and wallet-specific backups. Observe failure.
- [x] Replace WalletProtectionSettings with overview and informational sheets; retain callback interfaces and add navigation callbacks. Move existing dashboard controls behind App lock; wire backup/recovery navigation.
- [x] Run focused UI/settings tests and debug compile; verify emulator overview and sheets.
- [x] Independent review, address findings, push PR update; no merge until checks/review clear.

Validation: overview navigation RED then GREEN. 21 affected tests passed; debug APK assembled. Independent review found healthy-wallet recovery entry lacked a loaded-empty state; regression failed before fix, then passed alongside loading-state coverage. No authentication/storage formats changed.
