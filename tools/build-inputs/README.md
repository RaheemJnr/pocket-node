# Build input verification

Android builds use Gradle's strict dependency verification (the default when
`android/gradle/verification-metadata.xml` exists), including POM and Gradle
Module Metadata. Do not disable verification or add broad trusted-artifact
patterns to make a build pass.

For an intentional dependency change:

1. Review the coordinates, version and repository. Preserve unrelated versions.
2. Generate candidate entries with `--write-verification-metadata sha256` while
   resolving the affected configurations and build tasks. This only inventories
   downloaded bytes; it does not establish trust.
3. Independently compare every new checksum against the official repository's
   SHA256 sidecar, or a fresh HTTPS download from that repository when no SHA256
   is published. Review Module Metadata's file URL for logical artifact names.
   Record the source URL and missing publisher-checksum limitation in review
   evidence. A locally generated checksum alone is insufficient. Repository
   checksums and downloads share a publisher trust boundary; neither proves
   that the publisher or its build was uncompromised.
4. Exercise Android debug, release/playRelease, instrumentation and unit-test
   classpaths, shared Android host tests, and both iOS ARM targets. Include
   detached compiler/plugin configurations and Linux/macOS tool classifiers.
   Review metadata diffs; do not commit automatically accepted generated entries.
5. Run normal builds in strict mode and verify tamper rejection in disposable
   fixtures. Never modify a shared cache to test rejection.

The wrapper distribution checksum comes from Gradle's official distribution
endpoint. Update the wrapper JAR from the matching Gradle release and compare
it against the official wrapper JAR checksum. CI validates the JAR before any
Gradle invocation. Actions use immutable provider-reviewed commit SHAs; version
comments are navigation hints, not the trust anchor. Review upstream changes
and transitive downloads when updating a pin.

Release builds use fresh Gradle and Cargo homes, restore no workflow build cache,
and disable Gradle build-output caching. Ordinary CI retains separately named
workflow/OS caches for speed. GitHub restricts cache access by ref: a fork PR's
merge-ref cache does not become readable by default-branch releases. Shared
trusted-branch cache state was a hardening concern, not proof of an arbitrary
fork-to-release poisoning exploit. Gradle checksums do not authenticate cached
transformed outputs or executed build scripts, which is why release is cold.

The workflow validates a tag on the default branch and checks out its exact
commit. Verification files therefore come from that tag. Historical tags lacking
the checksum or dependency metadata fail closed and must be rebuilt from a new,
reviewed tag containing this policy. The signing job remains separate and never
runs Cargo, Gradle or checked-out build scripts.

Native source/lock/compiler provenance is maintained separately in
`tools/embedded-client`. Dependency verification is an integrity policy, not a
vulnerability audit or a claim of byte-for-byte reproducible APKs. Hosted runner
images, SDK/JDK installation, Kotlin/Native tool downloads, action-internal
fetches and signing tools still have their own upstream trust boundaries.
