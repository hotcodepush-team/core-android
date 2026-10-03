# CLAUDE.md

`com.hotcodepush:protocol-android`, the HotCodePush update-protocol client for Android: the wire types, the evaluator, the downloader, the file store and the state machine every HotCodePush SDK on Android runs, held to the fixture suite of `@hotcodepush/protocol`.
`@hotcodepush/protocol` (`protocol-js`) and the Swift package `HotCodePushProtocol` (`protocol-ios`) implement the same functions and types; a change to one is a change to the other two.
The Capacitor SDK consumes it at a pinned commit through JitPack until its publish decision — `com.github.hotcodepush-team:protocol-android:<sha>` from `https://jitpack.io` — never a branch; it is not the supported API, apps use the SDK for their framework.
Stack: Kotlin 2.2 on the Android Gradle plugin, minSdk 23, OkHttp and coroutines, JUnit 4 on the JVM, Java 21, Node 24 for the fixtures.

The plan is the private `handbook` repo, checked out beside this one: `../handbook/docs/`.
Its `sdk-api.md` (the SDK surface, the state and the functions, statuses and reasons) and `architecture.md` (_The device protocol_, _Packs_, _Evolving the wire format_, _Testing_) are binding here.
When code and plan disagree, stop and surface it; never improvise.

## Layout

```
src/main/java/com/hotcodepush/protocol   the library: no framework import; the manifest carries INTERNET and the backup and data-extraction rules
src/main/res/xml                         the rules that keep the store out of backups and device transfers
src/test/java/com/hotcodepush/protocol   JUnit on the JVM; FixtureTest reads node_modules/@hotcodepush/protocol/fixtures after npm ci
build.gradle                             the library module and the Maven publication JitPack builds
package.json                             private, only the pinned @hotcodepush/protocol the fixtures come from
```

## Commands

| Command          | Does                                                     |
| ---------------- | -------------------------------------------------------- |
| `npm ci`         | installs the protocol package the fixtures are read from |
| `npm run lint`   | `./gradlew lint`                                         |
| `npm test`       | `./gradlew test`                                         |
| `npm run verify` | the lint, the tests and the release build                |

`ci.yml` runs the lint, the tests and `assembleRelease` on every push and pull request; `./gradlew publishToMavenLocal` is what JitPack runs for a commit.
No releases yet: the version stays `0.0.0`, and release-please and the Maven Central publication arrive with the publish decision.
The fixtures move with `package.json`'s pin: a protocol change is a bump of that sha, and the cases the new build adds fail here until the Kotlin follows.

## Rules

- The wire format is additive only and parsed strictly, with no `org.json` coercion: a string is a string, a boolean a boolean, a whole number whole; every v1 field is present, a nullable one as `null`, an absent one refuses the document; a field, a condition type or a platform the reader does not know is kept, and an unknown condition fails closed.
- A value is checked before it names anything: ids are identifiers, hashes lowercase sha256, paths relative with no `.` or `..` segment — split on code units, so nothing hides behind a combining mark — timestamps UTC with a `Z`, URLs absolute, and the pack's ustar headers carry a checksum that must add up.
- The evaluator is `@hotcodepush/protocol`'s, case for case: the outcome and the verdicts behind it come from the fixtures, never from a reading of the plan.
- A downloaded release that has left the cached index — revoked, or gone from it — is discarded before it would install, never applied.
- Safety is on by default and cannot be switched off: the readiness gate, the local blocklist, the automatic rollback.
- Every key in the store is `hotcodepush.<name>`; three identity keys survive everything, the rest is a cache dropped on an unknown `stateVersion`.
- Statuses and reasons are `SCREAMING_SNAKE_CASE` from the one catalog; a method throws a plain error only for a programming mistake.
- `java.time` sits above the API floor: timestamps live as epoch milliseconds and travel through `Iso8601`.
- Nothing here writes a cryptographic primitive or parses a standard format by hand beyond ustar and gzip: `java.security`, `java.util.zip` and `org.json` do that.

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
