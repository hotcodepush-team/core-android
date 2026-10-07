# CLAUDE.md

`com.hotcodepush:core-android`, the HotCodePush update-protocol client for Android: the wire types, the evaluator, the downloader, the signature check, the patch application, the file store, the state machine and the debug screen every HotCodePush SDK on Android runs, held to the fixture suite of `@hotcodepush/protocol`.
`@hotcodepush/protocol` (`protocol`) and the Swift package `HotCodePushCore` (`core-ios`) implement the same functions and types; a change to one is a change to the other two.
The SDKs consume it at a pinned commit until its publish decision — `com.hotcodepush:core-android:<full sha>` from this repository's `maven` branch, `https://raw.githubusercontent.com/hotcodepush-team/core-android/maven/`; it is not the supported API, apps use the SDK for their framework.
Stack: Kotlin 2.2 on the Android Gradle plugin, minSdk 23, OkHttp with Okio and coroutines, C for bspatch and bzip2's decompression (bzip2 1.0.8, unmodified), JUnit 4 and Robolectric on the JVM, Java 21, Node 24 for the fixtures.
CMake builds the C with the plugin's default NDK for its four ABIs, the two 64-bit ones aligned for 16 KB pages.

The plan is the private `handbook` repo, checked out beside this one: `../handbook/docs/`.
Its `sdk-api.md` (the SDK surface, the state and the functions, statuses and reasons) and `architecture.md` (_The device protocol_, _Signing_, _Packs_, _Debugging_, _Evolving the wire format_, _Testing_) are binding here.
When code and plan disagree, stop and surface it; never improvise.

## Layout

```
src/main/java/com/hotcodepush/core       the library: no framework import, Android's UI classes in DebugScreen.kt alone; the manifest carries INTERNET, the backup and data-extraction rules and the debug screen's activity
src/main/cpp                             the native library hotcodepush_bspatch behind one JNI function: bspatch/ is FreeBSD's bspatch.c and its header, byte for byte core-ios's, bzip2/ the decompression sources of bzip2 1.0.8, unmodified
src/main/res/xml                         the rules that keep the store out of backups and device transfers
src/main/res/values, values-v29          the debug screen's strings and its theme, day and night from API 29
src/test/java/com/hotcodepush/core       JUnit on the JVM; FixtureTest and SigningTest read node_modules/@hotcodepush/protocol/fixtures after npm ci, DeviceEventsContractTest runs the package's schema with node; Robolectric runs the debug screen's activity
src/sharedTest/java                      the download fakes and the pack writer the JVM tests and the on-device tests share
src/androidTest/java/com/hotcodepush/core   the tests that need a device: the signature fixtures against the device's own `java.security` providers, bspatch on its hostile patches and the fixtures' patch cases through the native library of the device's ABI, the fixtures packaged as assets
src/androidTest/assets/bspatch           what BspatchTest reads: the committed inputs old.bin, new.bin and valid.patch, the patch written once by bsdiff 4.3, and the hostile patches src/androidTest/make-bspatch-fixtures.sh writes with bash, xxd and bzip2
build.gradle                             the library module and its Maven publication, versioned by the commit's full sha
THIRD-PARTY-NOTICES                      the notices of bspatch, BSD 2-clause, and of bzip2, under its own licence
package.json                             private, only the pinned @hotcodepush/protocol the fixtures come from
```

## Commands

| Command                               | Does                                                               |
| ------------------------------------- | ------------------------------------------------------------------ |
| `npm ci`                              | installs the protocol package the fixtures are read from           |
| `npm run lint`                        | `./gradlew lint`                                                   |
| `npm test`                            | `./gradlew test`                                                   |
| `npm run verify`                      | the lint, the tests and the release build                          |
| `./gradlew connectedDebugAndroidTest` | the on-device tests, on every running emulator and attached device |

`ci.yml` runs the lint, the tests and `assembleRelease` on every push to `main` and every pull request, and its `device` job runs `connectedDebugAndroidTest` on two emulators: a 32-bit x86 one at API 23, where bspatch runs on a 32-bit `off_t`, and an x86_64 one at API 35.
Once the lint, the tests and the build pass on a push to `main`, `ci.yml` calls `publish.yml`, which commits the publication onto the `maven` branch; a version is published once and never rewritten, and the branch is never force-pushed.
A dispatch of `publish.yml` with a full sha publishes an earlier commit whose `build.gradle` already carries the publication.
No releases yet: the version is the commit's full sha, and release-please and the Maven Central publication arrive with the publish decision.
The fixtures move with `package.json`'s pin: a protocol change is a bump of that sha, and the cases the new build adds fail here until the Kotlin follows.

## Rules

- The wire format is additive only and parsed strictly, with no `org.json` coercion: a string is a string, a boolean a boolean, a whole number whole; every v1 field is present, a nullable one as `null`, an absent one refuses the document; a field, a condition type or a platform the reader does not know is kept, and an unknown condition fails closed.
- What the device sends is `DeviceEventsRequestSchema`'s, key for key: a nullable key travels as `null`, never absent — a rollback's `toReleaseId` to the embedded bundle — and only an optional key is left out; `DeviceEventsContractTest` runs the installed package's schema over the encoded batch through Node.
- A build whose resource file carries `channelId: null` — built without a token or offline — answers an explicit call `FAILED` with `CHANNEL_UNKNOWN` without a request, starts no automatic cycle and sends no report; a channel set at runtime makes it a device like any other, and clearing that choice returns it to the answer.
- A build whose resource file carries `embeddedBundleManifest: null` — a React Native or Expo debug build, whose build step bundled no JavaScript — and a debug build with `enabledInDebugBuilds` off answer every cycle `SKIPPED` with `BUILD_DEBUG` and send nothing.
- A rollback's `rolledBack` event is stored and announced at every start until the app is up in a run that received it, so the JavaScript that listens only after its start never misses it; `clearUpdates()` and a new binary drop the stored notice with the rest.
- A batch the events endpoint refuses with a 4xx other than 408 and 429 is dropped, its events gone and the report left unacknowledged for the next one; a 202 takes both, and anything else keeps both for the next sync.
- A value is checked before it names anything: ids are identifiers, hashes lowercase sha256, paths relative with no `.` or `..` segment — split on code units, so nothing hides behind a combining mark — timestamps UTC with a `Z`, URLs absolute, and the pack's ustar headers carry a checksum that must add up.
- The evaluator is `@hotcodepush/protocol`'s, case for case: the outcome and the verdicts behind it come from the fixtures, never from a reading of the plan.
- A downloaded release that has left the cached index — revoked, or gone from it — is discarded before it would install, never applied.
- Safety is on by default and cannot be switched off: the readiness gate, the local blocklist, the automatic rollback.
- Before the app is up in this run the only reload is a rollback, and checking and downloading never wait. The first render, whatever `readySignal` is, `notifyReady()` or the readiness timeout settles the start, `hasStartSettled`, and every reload the core performs unsettles it until the reloaded app reaches one of the three. The automatic check at start runs at once for a confirmed release and at its confirmation for a new one: a release that was confirmed and later dies before it renders must still receive a fix or a revocation. A reload waits for the settled start whoever asks for it: a restart the core performs on its own, which also waits while the app holds restarts, and the app's `applyUpdate()` and `clearUpdates()`, which `setRestartAllowed(false)` never holds. One restart is held at most: the app's replaces a held one, the core's yields to a held one, and a reload the core performs clears it. A rollback never waits for the start: the one at start after a crash in the previous run and the app's `rollbackUpdate()` reload at once, and the readiness timeout settles the start before it rolls back, so only `setRestartAllowed(false)` can still hold that reload. An install the core holds is persisted as the bundle to serve before it waits, a `next-resume` install included, so the next start runs it when this run never does; a held `applyUpdate()` or `clearUpdates()` persists nothing and stays the app's business.
- Every key in the store is `hotcodepush.<name>`; three identity keys survive everything, the rest is a cache dropped on an unknown `stateVersion`.
- Statuses and reasons are `SCREAMING_SNAKE_CASE` from the one catalog; a method throws a plain error only for a programming mistake.
- `java.time` sits above the API floor: timestamps live as epoch milliseconds and travel through `Iso8601`.
- The signature allow-list is pinned and has one entry, `rsa-v1_5-sha256`: the manifest string is verified as received under the key whose id the signature names, with `java.security` alone — `KeyFactory` over the SPKI DER the resource file carries beside each key id, then `SHA256withRSA` — on every Android version. A value under any other prefix, `ed25519` included, is an unknown scheme, and a key under 2048 bits, its size read from the key the platform imported, verifies nothing.
- No cryptography library is a dependency: BouncyCastle was one while the scheme was Ed25519, and cost an app that does not shrink its code 2.2 MB.
- A download stays on the URL the core pinned and never follows a redirect; a streamed delta the updates host does not serve gives way to the envelope's full pack.
- A pack entry is named by ustar's prefix and name fields, `prefix/name` when the prefix is not empty: a content hash is a file entry, its body the stored gzip object, and `patches/{from}/{to}` a patch entry, its body a BSDIFF40 patch from the content of the file `from` to the content of the file `to`. An entry of any other name is skipped with its body, never an error, so a later entry kind does not break a shipped SDK; a header whose checksum does not add up or a cut archive stays an error.
- A patch applies only to a file of the signed manifest the device lacks, from a base it holds in the file store or the embedded bundle, never past the manifest's size of the target; the store takes the result only when it hashes to the target.
- A patch that does not apply — no base, a malformed patch, another hash, no native library for the ABI, no memory — leaves its file to the single-file fetch after the pack: an update never fails because of a patch.
- The debug screen shows what `DebugReport` renders, section for section and label for label what the Swift package renders, and the share text is the same sections: a fact joins both through `DebugReport`, never the screen alone. The session log lives in memory, the newest two hundred lines, never on disk and never on the wire.
- Nothing here writes a cryptographic primitive or parses a standard format by hand beyond ustar and gzip: `java.security`, `java.util.zip`, `org.json` and Okio's Base64 do that, and FreeBSD's `bspatch.c` applies BSDIFF40 over bzip2's own decompression.
- `bspatch.c` is FreeBSD's with the lower bound on the old file's offset that FreeBSD dropped in 2019 restored, every change listed under its licence header, and byte for byte core-ios's: a change lands in both cores at once, and core-ios's CI fails on a difference.

## Naming

- A name says what the function does on first read: the verb, the object and, where it matters, the qualifier.
- Prefixes: `fetch` HTTP, `resolve` derivations without I/O, `build` functions that assemble a document without sending it, `handle` the lifecycle entry points.
- Alphabetical ordering within a scope.
- One thing per function, its name saying which; never a function that both decides something and phrases the message about it.
- Booleans carry `is` or `has`; a state with a moment is a timestamp such as `pausedAt`, never a boolean.
- Test titles read `should <verb> …`, lowercase, conditions starting with `when`.
- Fixtures and examples carry invented data only.

## Agent workspace

- `.claude/skills/` holds the developer skills copied from `hotcodepush-team/.github`, pinned in `skills-lock.json`.
- Commits are conventional commits; `main` is trunk, CI is the gate, and a commit that lands an issue says `Closes #<n>`.
